package com.thirdeye.app

import android.os.SystemClock
import android.view.Surface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface SessionMode {
    val isDone: Boolean
    suspend fun onSessionStarted()
    suspend fun onFrame(frame: VideoFrame, timestampNs: Long)
    suspend fun onStop()
}

internal class OneShotDescribeMode(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val endpoint: String,
    private val onAnswer: (VisionResult, Long) -> Unit,
) : SessionMode {
    override var isDone = false
        private set

    override suspend fun onSessionStarted() = Unit

    override suspend fun onFrame(frame: VideoFrame, timestampNs: Long) {
        if (isDone) return
        val result = VisionClient.analyze(
            surface, width, height, endpoint,
            "Describe what I am looking at in one concise sentence.", timestampNs,
        )
        isDone = true
        withContext(Dispatchers.Main) { onAnswer(result, SystemClock.elapsedRealtimeNanos()) }
    }

    override suspend fun onStop() = Unit
}

/** Owns every resource opened for a mode. Manual preview remains a separate debug path. */
internal class InvocationSession(
    private val scope: CoroutineScope,
    private val surface: Surface,
    private val recorder: DebugRecorder?,
    private val onStarted: (Long) -> Unit,
    private val onFrameReady: (Long) -> Unit,
    private val onFinished: (Throwable?, Long) -> Unit,
) {
    private var job: Job? = null
    val isActive get() = job?.isActive == true

    fun start(mode: SessionMode): Boolean {
        if (isActive) return false
        job = scope.launch(Dispatchers.Default) { run(mode) }
        return true
    }

    fun stop() { job?.cancel() }

    private suspend fun run(mode: SessionMode) {
        var session: DeviceSession? = null
        var camera: Camera? = null
        var renderer: HevcRenderer? = null
        var failure: Throwable? = null
        try {
            Wearables.createSession(AutoDeviceSelector()).fold(
                onSuccess = { session = it },
                onFailure = { failure, _ -> throw IllegalStateException("Create session: ${failure.description}") },
            )
            val activeSession = checkNotNull(session)
            activeSession.start()
            val state = withTimeout(30_000) {
                activeSession.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
            }
            check(state == DeviceSessionState.STARTED) { "DeviceSession stopped before STARTED" }
            withContext(Dispatchers.Main) { onStarted(SystemClock.elapsedRealtimeNanos()) }
            mode.onSessionStarted()
            activeSession.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24, compressVideo = true)).fold(
                onSuccess = { camera = it },
                onFailure = { failure, _ -> throw IllegalStateException("Add camera: ${failure.description}") },
            )
            val activeCamera = checkNotNull(camera)
            val stream = activeCamera.stream
            var rendered = 0
            coroutineScope {
                // Subscribe before start so the decoder receives the initial codec configuration.
                val frames = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(60_000) {
                        stream.videoStream.first { frame ->
                            if (!frame.isCompressed) return@first false
                            if (renderer?.width != frame.width || renderer?.height != frame.height) {
                                renderer?.close()
                                renderer = HevcRenderer(frame.width, frame.height, surface)
                            }
                            val frameRendered = renderer?.render(frame) == true
                            if (stream.state.value != StreamState.STREAMING || frame.isCodecConfig || !frameRendered) return@first false
                            rendered++
                            // The second output gives Surface a completed current frame for PixelCopy.
                            if (rendered < 2) return@first false
                            val timestampNs = SystemClock.elapsedRealtimeNanos()
                            withContext(Dispatchers.Main) { onFrameReady(timestampNs) }
                            recorder?.record(surface, frame, timestampNs)
                            mode.onFrame(frame, timestampNs)
                            mode.isDone
                        }
                    }
                }
                stream.start().onFailure { failure, _ -> throw IllegalStateException("Start camera: ${failure.description}") }
                withTimeout(20_000) { stream.state.first { it == StreamState.STREAMING } }
                frames.await()
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            val cleanupStarted = SystemClock.elapsedRealtimeNanos()
            withContext(NonCancellable) {
                try { mode.onStop() } catch (e: Exception) { if (failure == null) failure = e }
                try { renderer?.close() } catch (e: Exception) { if (failure == null) failure = e }
                try { camera?.stop() } catch (e: Exception) { if (failure == null) failure = e }
                try { camera?.close() } catch (e: Exception) { if (failure == null) failure = e }
                try {
                    session?.stop()
                    session?.let { withTimeout(5_000) { it.state.first { state -> state == DeviceSessionState.STOPPED } } }
                } catch (e: Exception) { if (failure == null) failure = e }
                try { recorder?.close() } catch (e: Exception) { if (failure == null) failure = e }
                withContext(Dispatchers.Main) {
                    onFinished(failure, (SystemClock.elapsedRealtimeNanos() - cleanupStarted) / 1_000_000)
                }
            }
        }
    }
}
