package com.thirdeye.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class VisionResult(
    val answer: String,
    val copyMs: Long,
    val jpegMs: Long,
    val httpMs: Long,
    val modelMs: Double?,
    val totalMs: Long,
)

/** The camera side only supplies a displayed Surface; the backend can be replaced at the URL. */
internal object VisionClient {
    suspend fun analyze(
        surface: Surface,
        width: Int,
        height: Int,
        endpoint: String,
        prompt: String,
        started: Long,
    ): VisionResult {
        require(width > 0 && height > 0 && surface.isValid) { "Preview unavailable" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copyStarted = SystemClock.elapsedRealtimeNanos()
        val jpeg: ByteArray
        val copyMs: Long
        val jpegMs: Long
        try {
            suspendCoroutine<Unit> { continuation ->
                PixelCopy.request(surface, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException("PixelCopy failed: $result"))
                }, Handler(Looper.getMainLooper()))
            }
            copyMs = elapsed(copyStarted)
            val jpegStarted = SystemClock.elapsedRealtimeNanos()
            jpeg = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "JPEG encode failed" }
                    output.toByteArray()
                }
            }
            jpegMs = elapsed(jpegStarted)
        } finally {
            bitmap.recycle()
        }
        val httpStarted = SystemClock.elapsedRealtimeNanos()
        val response = withContext(Dispatchers.IO) { post(endpoint, prompt, jpeg, width, height) }
        return VisionResult(
            answer = response.getString("answer"),
            copyMs = copyMs,
            jpegMs = jpegMs,
            httpMs = elapsed(httpStarted),
            modelMs = if (response.has("model_ms") && !response.isNull("model_ms")) response.getDouble("model_ms") else null,
            totalMs = elapsed(started),
        )
    }

    private fun post(endpoint: String, prompt: String, jpeg: ByteArray, width: Int, height: Int): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val request = JSONObject()
                .put("prompt", prompt)
                .put("image_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                .put("width", width)
                .put("height", height)
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            check(code in 200..299) { "HTTP $code: $body" }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun elapsed(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
}
