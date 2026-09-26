package com.thirdeye.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoderLock = Any()
    private var renderer: HevcRenderer? = null
    private var previewSurface: Surface? = null
    private val availableSurface = MutableStateFlow<Surface?>(null)
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var sessionJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var frameJob: Job? = null
    private var streamJob: Job? = null
    private var streamErrorJob: Job? = null
    private var sessionState = DeviceSessionState.IDLE
    private var streamState = StreamState.STOPPED
    private var registered = false
    private var initialized = false
    private var destroyed = false
    private var hasFrame = false
    private var analyzing = false
    private var voiceStream: VoiceInvocationsStream? = null
    private var voiceInvocationJob: Job? = null
    private var voiceErrorJob: Job? = null
    private var voiceStateJob: Job? = null
    private var invocationSession: InvocationSession? = null
    private var oneShotStarting = false
    private var recordNextSession = false
    private var recorder: DebugRecorder? = null
    private var endpoint = "http://127.0.0.1:8765/analyze"
    private var prompt = "Describe what you see."

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var connectButton: Button
    private lateinit var sessionButton: Button
    private lateinit var previewButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var describeButton: Button
    private lateinit var recordButton: Button

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) initializeDat() else showError("Bluetooth permission is required")
        }

    private val cameraPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.fold(
                onSuccess = { permission ->
                    if (permission == PermissionStatus.Granted && sessionState == DeviceSessionState.STARTED) {
                        addCamera()
                    } else if (permission == PermissionStatus.Granted) {
                        showError("Camera allowed. Start a new session, then preview.")
                    } else {
                        showError("Camera permission denied")
                    }
                },
                onFailure = { error, _ -> showError(error.description) },
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (isVoiceInvocationsIntent(intent)) message.text = "Voice launch received; waiting for Meta invocation"
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            initializeDat()
        } else {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVoiceInvocationsIntent(intent)) message.text = "Voice launch received; waiting for Meta invocation"
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
            val height = bottom
            val width = (height * 9f / 16f).toInt()
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                surfaceView.layoutParams = params
            }
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(decoderLock) { previewSurface = holder.surface }
                availableSurface.value = holder.surface
                updateControls()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(decoderLock) {
                    previewSurface = null
                    renderer?.close()
                    renderer = null
                }
                availableSurface.value = null
                camera?.stop()
                invocationSession?.stop()
                updateControls()
            }
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 20)
            setBackgroundColor(Color.argb(185, 0, 0, 0))
        }
        message = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        connectButton = Button(this).apply { text = "Connect my glasses"; setOnClickListener { Wearables.startRegistration(this@MainActivity) } }
        sessionButton = Button(this).apply { setOnClickListener { if (session == null) startSession() else session?.stop() } }
        previewButton = Button(this).apply { setOnClickListener { if (camera == null) startPreview() else camera?.stop() } }
        analyzeButton = Button(this).apply { text = "Analyze current view"; setOnClickListener { showAnalyzeDialog() } }
        describeButton = Button(this).apply {
            text = "Describe once (debug)"
            setOnClickListener {
                scope.launch {
                    try {
                        if (!startOneShot(SystemClock.elapsedRealtimeNanos(), "debug")) showError("Describe needs idle, registered glasses and camera access")
                    } catch (e: Exception) { showError("Describe: ${e.message}") }
                }
            }
        }
        recordButton = Button(this).apply {
            text = "Record next session: off"
            setOnClickListener {
                recordNextSession = !recordNextSession
                text = "Record next session: ${if (recordNextSession) "on" else "off"}"
            }
        }
        controls.addView(message)
        controls.addView(connectButton)
        controls.addView(sessionButton)
        controls.addView(previewButton)
        controls.addView(analyzeButton)
        controls.addView(describeButton)
        controls.addView(recordButton)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            status.setPadding(20, bars.top + 16, 20, 16)
            controls.setPadding(20, 16, 20, bars.bottom + 16)
            insets
        }
        setContentView(root)
        updateControls()
    }

    private fun initializeDat() {
        if (initialized) return
        Wearables.initialize(this).fold(
            onSuccess = {
                initialized = true
                scope.launch {
                    Wearables.registrationState.collect { state ->
                        registered = state == RegistrationState.REGISTERED
                        updateControls()
                    }
                }
                startVoiceListener()
                updateControls()
            },
            onFailure = { error, _ -> showError("DAT initialization: ${error.description}") },
        )
    }

    private fun startVoiceListener() {
        if (destroyed) return
        voiceInvocationJob?.cancel()
        voiceErrorJob?.cancel()
        voiceStateJob?.cancel()
        voiceStream?.close()
        val listening = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
        voiceStream = listening
        voiceInvocationJob = scope.launch {
            listening.invocations.collect { invocation ->
                when (invocation) {
                    is LaunchApp -> {
                        val receivedNs = SystemClock.elapsedRealtimeNanos()
                        val started = try {
                            startOneShot(receivedNs, "invocation")
                        } catch (e: Exception) {
                            showError("Voice launch: ${e.message}")
                            false
                        }
                        val delivered = if (started) invocation.responseHandle.sendSuccess(null)
                            else invocation.responseHandle.sendFailure("ThirdEye is not ready")
                        if (!delivered) Log.w("thirdeye", "Meta invocation acknowledgment was not delivered")
                    }
                }
            }
        }
        voiceErrorJob = scope.launch { listening.errors.collect { error -> showError("Voice invocation: ${error.description}") } }
        voiceStateJob = scope.launch { listening.state.collect { state -> Log.i("thirdeye", "Voice invocation stream: $state") } }
    }

    private suspend fun startOneShot(receivedNs: Long, origin: String): Boolean {
        if (!registered || session != null || oneShotStarting || invocationSession?.isActive == true || analyzing) return false
        oneShotStarting = true
        updateControls()
        try {
            val surface = withTimeoutOrNull(5_000) {
                availableSurface.first { it != null && it.isValid && surfaceView.width > 0 && surfaceView.height > 0 }
            } ?: return false
            val cameraAllowed = Wearables.checkPermissionStatus(Permission.CAMERA).fold(
                onSuccess = { it == PermissionStatus.Granted },
                onFailure = { _, _ -> false },
            )
            if (!cameraAllowed) return false
            val recording = if (recordNextSession) newRecorder() else null
            var sessionStartedNs = 0L
            val active = InvocationSession(
                scope, surface, recording,
                onStarted = { startedNs ->
                    sessionStartedNs = startedNs
                    Log.i("thirdeye", "${origin}_to_session_ms=${(startedNs - receivedNs) / 1_000_000}")
                },
                onFrameReady = { frameNs ->
                    Log.i("thirdeye", "session_to_frame_ms=${(frameNs - sessionStartedNs) / 1_000_000}")
                },
                onFinished = { failure, cleanupMs ->
                    if (failure != null) showError("Describe: ${failure.message}")
                    Log.i("thirdeye", "${origin}_cleanup_ms=$cleanupMs")
                    invocationSession = null
                    startVoiceListener()
                    updateControls()
                },
            )
            val mode = OneShotDescribeMode(surface, surfaceView.width, surfaceView.height, endpoint) { result, answeredNs ->
                message.text = "${result.answer}\nFrame → answer ${result.totalMs} ms · model ${result.modelMs ?: "n/a"} ms · $origin → answer ${(answeredNs - receivedNs) / 1_000_000} ms"
                Log.i("thirdeye", "${origin}_to_answer_ms=${(answeredNs - receivedNs) / 1_000_000} frame_to_answer_ms=${result.totalMs}")
            }
            invocationSession = active
            val started = active.start(mode)
            if (!started) invocationSession = null
            return started
        } finally {
            oneShotStarting = false
            updateControls()
        }
    }

    private fun newRecorder(): DebugRecorder {
        val root = getExternalFilesDir("replays") ?: File(filesDir, "replays")
        return DebugRecorder(File(root, "session-${System.currentTimeMillis()}"), scope)
    }

    private fun startSession() {
        if (!initialized || !registered || session != null || oneShotStarting || invocationSession?.isActive == true) return
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { created ->
                session = created
                recorder = if (recordNextSession) runCatching { newRecorder() }
                    .onFailure { showError("Debug recorder: ${it.message}") }.getOrNull() else null
                sessionState = DeviceSessionState.STARTING
                sessionJob = scope.launch {
                    created.state.collect { state ->
                        if (session !== created) return@collect
                        sessionState = state
                        if (state == DeviceSessionState.STOPPED) {
                            clearStream()
                            val finishedRecorder = recorder
                            recorder = null
                            scope.launch { finishedRecorder?.close() }
                            session = null
                            sessionErrorJob?.cancel()
                            startVoiceListener()
                        }
                        updateControls()
                    }
                }
                sessionErrorJob = scope.launch {
                    created.errors.collect { error -> showError("Session: ${error.description}") }
                }
                created.start()
                updateControls()
            },
            onFailure = { error, _ -> showError("Start session: ${error.description}") },
        )
    }

    private fun startPreview() {
        if (sessionState != DeviceSessionState.STARTED || previewSurface == null || camera != null) return
        scope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA).fold(
                onSuccess = { permission ->
                    if (permission == PermissionStatus.Granted) addCamera()
                    else cameraPermission.launch(Permission.CAMERA)
                },
                onFailure = { error, _ -> showError("Camera permission: ${error.description}") },
            )
        }
    }

    private fun addCamera() {
        val current = session ?: return
        if (sessionState != DeviceSessionState.STARTED || camera != null || previewSurface == null) return
        current.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24, compressVideo = true))
            .fold(
                onSuccess = { added ->
                    camera = added
                    val opened = added.stream
                    stream = opened
                    frameJob = scope.launch(Dispatchers.Default.limitedParallelism(1)) {
                        opened.videoStream.collect { renderFrame(it) }
                    }
                    streamJob = scope.launch {
                        var wasActive = false
                        opened.state.collect { state ->
                            if (stream !== opened) return@collect
                            streamState = state
                            if (state != StreamState.STOPPED && state != StreamState.CLOSED) wasActive = true
                            if (wasActive && (state == StreamState.STOPPED || state == StreamState.CLOSED)) clearStream()
                            updateControls()
                        }
                    }
                    streamErrorJob = scope.launch {
                        opened.errorStream.collect { error -> showError("Stream: ${error.description}") }
                    }
                    streamState = StreamState.STARTING
                    opened.start().onFailure { error, _ ->
                        showError("Start preview: ${error.description}")
                        clearStream()
                    }
                    updateControls()
                },
                onFailure = { error, _ -> showError("Add camera: ${error.description}") },
            )
    }

    private fun renderFrame(frame: VideoFrame) {
        if (!frame.isCompressed) return
        try {
            val rendered = synchronized(decoderLock) {
                val surface = previewSurface ?: return
                if (renderer?.width != frame.width || renderer?.height != frame.height) {
                    renderer?.close()
                    renderer = HevcRenderer(frame.width, frame.height, surface)
                }
                renderer?.render(frame) == true
            }
            if (!frame.isCodecConfig && rendered) {
                previewSurface?.let { recorder?.record(it, frame, SystemClock.elapsedRealtimeNanos()) }
            }
            if (!frame.isCodecConfig && rendered && !hasFrame) {
                hasFrame = true
                runOnUiThread { updateControls() }
            }
        } catch (e: Exception) {
            Log.e("thirdeye", "Preview decode failed", e)
            runOnUiThread { showError("Preview decode: ${e.message}") }
        }
    }

    private fun clearStream() {
        frameJob?.cancel(); frameJob = null
        streamJob?.cancel(); streamJob = null
        streamErrorJob?.cancel(); streamErrorJob = null
        synchronized(decoderLock) {
            renderer?.close()
            renderer = null
        }
        val oldCamera = camera
        camera = null
        stream = null
        oldCamera?.close()
        streamState = StreamState.STOPPED
        hasFrame = false
        updateControls()
    }

    private fun showAnalyzeDialog() {
        if (analyzing || streamState != StreamState.STREAMING || !hasFrame) return
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val endpointField = EditText(this).apply { setSingleLine(true); setText(endpoint); hint = "Vision endpoint" }
        val promptField = EditText(this).apply { setText(prompt); hint = "Prompt" }
        fields.addView(endpointField)
        fields.addView(promptField)
        android.app.AlertDialog.Builder(this)
            .setTitle("Analyze current view")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Analyze") { _, _ ->
                endpoint = endpointField.text.toString()
                prompt = promptField.text.toString()
                analyze()
            }
            .show()
    }

    private fun analyze() {
        val surface = previewSurface ?: return
        if (analyzing || streamState != StreamState.STREAMING || !hasFrame || !surface.isValid) return
        analyzing = true
        updateControls()
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        scope.launch {
            try {
                val result = VisionClient.analyze(surface, surfaceView.width, surfaceView.height, endpoint, prompt, started)
                message.text = "${result.answer}\nCopy ${result.copyMs} ms · JPEG ${result.jpegMs} ms · HTTP ${result.httpMs} ms · model ${result.modelMs ?: "n/a"} ms · total ${result.totalMs} ms"
            } catch (e: Exception) {
                showError("Analyze: ${e.message}")
            } finally {
                analyzing = false
                updateControls()
            }
        }
    }

    private fun updateControls() {
        if (!::status.isInitialized) return
        status.text = "Session: ${sessionState.name.lowercase()}\nStream: ${streamState.name.lowercase()}${if (hasFrame) " · frames received" else ""}"
        connectButton.visibility = if (registered) android.view.View.GONE else android.view.View.VISIBLE
        connectButton.isEnabled = initialized
        sessionButton.text = if (session == null) "Start session" else "End session"
        sessionButton.isEnabled = registered && (session == null || sessionState == DeviceSessionState.STARTED)
        if (oneShotStarting || invocationSession?.isActive == true) sessionButton.isEnabled = false
        previewButton.text = if (camera == null) "Start preview" else "Stop preview"
        previewButton.isEnabled = if (camera == null) sessionState == DeviceSessionState.STARTED && previewSurface != null else streamState == StreamState.STREAMING
        if (oneShotStarting || invocationSession?.isActive == true) previewButton.isEnabled = false
        analyzeButton.isEnabled = streamState == StreamState.STREAMING && hasFrame && !analyzing
        describeButton.isEnabled = registered && session == null && !oneShotStarting && invocationSession?.isActive != true
    }

    private fun showError(text: String) {
        Log.e("thirdeye", text)
        message.text = text
    }

    override fun onDestroy() {
        destroyed = true
        clearStream()
        invocationSession?.stop()
        voiceInvocationJob?.cancel()
        voiceErrorJob?.cancel()
        voiceStateJob?.cancel()
        voiceStream?.close()
        session?.stop()
        sessionJob?.cancel()
        sessionErrorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
