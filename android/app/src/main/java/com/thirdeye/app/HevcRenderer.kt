package com.thirdeye.app

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.meta.wearable.dat.camera.types.VideoFrame

/** Renders DAT's compressed Annex-B HEVC stream to the preview surface. */
internal class HevcRenderer(val width: Int, val height: Int, surface: Surface) {
    private val codec: MediaCodec = createSoftwareDecoder()
    private val parameterSets = mutableMapOf<Int, ByteArray>()
    private val outputInfo = MediaCodec.BufferInfo()
    private var hasKeyframe = false
    private var closed = false

    init {
        codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height), surface, null, 0)
        codec.start()
    }

    fun render(frame: VideoFrame) {
        if (closed || !frame.isCompressed) return
        val buffer = frame.buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        for ((type, nal) in splitAnnexB(bytes)) {
            if (type in 32..34) {
                parameterSets[type] = nal
                if (hasKeyframe && !queue(nal, frame.presentationTimeUs, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                    hasKeyframe = false
                }
                continue
            }
            val keyframe = type in 16..21
            if (!hasKeyframe) {
                if (!keyframe || !listOf(32, 33, 34).all(parameterSets::containsKey)) continue
                if (!listOf(32, 33, 34).all { queue(parameterSets.getValue(it), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }) {
                    continue
                }
                hasKeyframe = true
            }
            if (!queue(nal, frame.presentationTimeUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)) {
                hasKeyframe = false
            }
        }
        drain()
    }

    private fun queue(data: ByteArray, ptsUs: Long, flags: Int): Boolean {
        drain()
        var index = codec.dequeueInputBuffer(20_000)
        if (index < 0) {
            drain()
            index = codec.dequeueInputBuffer(20_000)
        }
        if (index < 0) return false
        val input = codec.getInputBuffer(index) ?: return false
        input.clear()
        if (data.size > input.remaining()) {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            return false
        }
        input.put(data)
        codec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
        return true
    }

    private fun drain() {
        while (!closed) {
            when (val index = codec.dequeueOutputBuffer(outputInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (index >= 0) codec.releaseOutputBuffer(index, outputInfo.size > 0)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w("thirdeye", "Decoder stop failed", e)
        } finally {
            codec.release()
        }
    }

    private fun createSoftwareDecoder(): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val software = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isSoftwareOnly && info.supportedTypes.any { it.equals(mime, true) }
        }
        return if (software != null) MediaCodec.createByCodecName(software.name)
        else MediaCodec.createDecoderByType(mime)
    }
}

/** Keeps each start code with its NAL, including the duplicate-prefix case in DAT's VPS packet. */
internal fun splitAnnexB(data: ByteArray): List<Pair<Int, ByteArray>> {
    val starts = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i + 3 < data.size) {
        val prefix = when {
            data[i] != 0.toByte() || data[i + 1] != 0.toByte() -> 0
            data[i + 2] == 1.toByte() -> 3
            i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
            else -> 0
        }
        if (prefix == 0) i++ else {
            starts.add(i to prefix)
            i += prefix
        }
    }
    return starts.mapIndexedNotNull { index, (start, prefix) ->
        val end = starts.getOrNull(index + 1)?.first ?: data.size
        if (start + prefix >= end) null
        else ((data[start + prefix].toInt() and 0x7E) shr 1) to data.copyOfRange(start, end)
    }
}
