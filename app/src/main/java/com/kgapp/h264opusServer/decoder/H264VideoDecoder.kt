package com.kgapp.h264opusServer.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class H264VideoDecoder(
    private val onVideoSizeChanged: (Int, Int) -> Unit
) {
    @Volatile
    private var codec: MediaCodec? = null
    @Volatile
    private var outputSurface: Surface? = null
    private val pendingBytes = ByteArrayOutputStream()
    private var configured = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun setSurface(surface: Surface) {
        synchronized(this) {
            outputSurface = surface
            maybeConfigureCodecLocked()
        }
    }

    fun clearSurface() {
        synchronized(this) {
            outputSurface = null
            releaseCodecLocked()
        }
    }

    fun queueVideo(buffer: ByteArray, size: Int) {
        if (size <= 0) return
        synchronized(pendingBytes) {
            pendingBytes.write(buffer, 0, size)
        }
        consumeNalus()
    }

    private fun consumeNalus() {
        val snapshot = synchronized(pendingBytes) { pendingBytes.toByteArray() }
        val nalus = extractNalus(snapshot)
        if (nalus.isEmpty()) return

        val consumed = nalus.sumOf { it.totalLength }
        synchronized(pendingBytes) {
            val source = pendingBytes.toByteArray()
            val remains = source.copyOfRange(consumed.coerceAtMost(source.size), source.size)
            pendingBytes.reset()
            pendingBytes.write(remains)
        }

        nalus.forEach { nalu ->
            val type = nalu.payload.firstOrNull()?.toInt()?.and(0x1F) ?: -1
            when (type) {
                7 -> sps = nalu.payload
                8 -> pps = nalu.payload
            }
            synchronized(this) {
                maybeConfigureCodecLocked()
            }
            queueNaluToCodec(nalu.fullData)
        }
    }

    private fun queueNaluToCodec(nalu: ByteArray) {
        val localCodec = codec ?: return
        try {
            val inputIndex = localCodec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer = localCodec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                val direct = ByteBuffer.allocateDirect(nalu.size)
                direct.put(nalu)
                direct.flip()
                inputBuffer.put(direct)
                localCodec.queueInputBuffer(inputIndex, 0, nalu.size, System.nanoTime() / 1_000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                localCodec.releaseOutputBuffer(outputIndex, true)
                outputIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0)
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = localCodec.outputFormat
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                Log.d(TAG, "output format changed width=$width height=$height")
                onVideoSizeChanged(width, height)
            }
        } catch (ex: Exception) {
            Log.d(TAG, "queueNaluToCodec error: ${ex.message}")
        }
    }

    private fun maybeConfigureCodecLocked() {
        if (configured) return
        val surface = outputSurface ?: return
        val localSps = sps
        val localPps = pps
        if (localSps == null || localPps == null) {
            Log.d(TAG, "waiting SPS/PPS before configure")
            return
        }

        val format = MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(localSps)))
            setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(localPps)))
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        }

        codec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
            configure(format, surface, null, 0)
            start()
        }
        configured = true
        Log.d(TAG, "video decoder configured")
    }

    fun release() {
        synchronized(this) {
            releaseCodecLocked()
            pendingBytes.reset()
            sps = null
            pps = null
        }
    }

    private fun releaseCodecLocked() {
        configured = false
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private fun extractNalus(data: ByteArray): List<NaluPacket> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i <= data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                starts.add(i)
                i += 4
            } else {
                i++
            }
        }

        if (starts.size < 2) return emptyList()
        val packets = mutableListOf<NaluPacket>()
        for (index in 0 until starts.lastIndex) {
            val start = starts[index]
            val next = starts[index + 1]
            if (next > start + 4) {
                val full = data.copyOfRange(start, next)
                val payload = data.copyOfRange(start + 4, next)
                packets.add(NaluPacket(fullData = full, payload = payload, totalLength = next - start))
            }
        }
        return packets
    }

    private fun withStartCode(bytes: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + bytes

    private data class NaluPacket(
        val fullData: ByteArray,
        val payload: ByteArray,
        val totalLength: Int
    )

    companion object {
        private const val TAG = "H264VideoDecoder"
        private const val MIME_TYPE = "video/avc"
    }
}
