package com.thirdeye.app

import android.util.Log
import android.view.Surface
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional one-frame-per-second JPEG trace with timestamps and an ffplay playlist. */
internal class DebugRecorder(private val directory: File, private val scope: CoroutineScope) {
    private var lastSavedNs = 0L
    private var pending: Job? = null
    private var count = 0
    private var previousName: String? = null
    private var previousNs = 0L

    init {
        check(directory.mkdirs()) { "Cannot create ${directory.absolutePath}" }
        File(directory, "replay.ffconcat").writeText("ffconcat version 1.0\n")
    }

    fun record(surface: Surface, frame: VideoFrame, timestampNs: Long) {
        if (timestampNs - lastSavedNs < 1_000_000_000L || pending?.isActive == true) return
        lastSavedNs = timestampNs
        val width = 320
        val height = (width.toDouble() * frame.height / frame.width).toInt()
        val presentationTimeUs = frame.presentationTimeUs
        val number = ++count
        pending = scope.launch(Dispatchers.IO) {
            try {
                val jpeg = VisionClient.captureJpeg(surface, width, height, 65).first
                val name = "%06d.jpg".format(number)
                File(directory, name).writeBytes(jpeg)
                File(directory, "frames.jsonl").appendText(
                    "{\"file\":\"$name\",\"elapsed_realtime_ns\":$timestampNs,\"presentation_time_us\":$presentationTimeUs,\"width\":$width,\"height\":$height}\n"
                )
                previousName?.let { previous ->
                    val seconds = (timestampNs - previousNs) / 1_000_000_000.0
                    File(directory, "replay.ffconcat").appendText("file '$previous'\nduration $seconds\n")
                }
                previousName = name
                previousNs = timestampNs
            } catch (e: Exception) {
                Log.e("thirdeye", "Debug recording failed", e)
            }
        }
    }

    suspend fun close() {
        pending?.join()
        withContext(Dispatchers.IO) {
            previousName?.let { File(directory, "replay.ffconcat").appendText("file '$it'\nduration 1.0\n") }
            previousName = null
        }
    }
}
