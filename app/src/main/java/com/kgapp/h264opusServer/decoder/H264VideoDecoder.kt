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
    private var streamWidth: Int = 1920
    private var streamHeight: Int = 1080

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
                7 -> {
                    sps = nalu.payload
                    parseSpsSize(nalu.payload)?.let { (w, h) ->
                        streamWidth = w
                        streamHeight = h
                        onVideoSizeChanged(w, h)
                    }
                }
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
                val width = if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                    format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                } else {
                    format.getInteger(MediaFormat.KEY_WIDTH)
                }
                val height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                    format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                } else {
                    format.getInteger(MediaFormat.KEY_HEIGHT)
                }
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

        val format = MediaFormat.createVideoFormat(MIME_TYPE, streamWidth, streamHeight).apply {
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

    private fun parseSpsSize(sps: ByteArray): Pair<Int, Int>? {
        return runCatching {
            val rbsp = removeEmulationPreventionBytes(sps)
            val bits = BitReader(rbsp)
            bits.readBits(8)
            val profileIdc = bits.readBits(8)
            bits.readBits(8)
            bits.readBits(8)
            bits.readUE()

            if (profileIdc in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chromaFormatIdc = bits.readUE()
                if (chromaFormatIdc == 3) {
                    bits.readBits(1)
                }
                bits.readUE()
                bits.readUE()
                bits.readBits(1)
                if (bits.readBits(1) == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (bits.readBits(1) == 1) {
                            var lastScale = 8
                            var nextScale = 8
                            val size = if (it < 6) 16 else 64
                            repeat(size) {
                                if (nextScale != 0) {
                                    val deltaScale = bits.readSE()
                                    nextScale = (lastScale + deltaScale + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            bits.readUE()
            val picOrderCntType = bits.readUE()
            if (picOrderCntType == 0) {
                bits.readUE()
            } else if (picOrderCntType == 1) {
                bits.readBits(1)
                bits.readSE()
                bits.readSE()
                repeat(bits.readUE()) { bits.readSE() }
            }

            bits.readUE()
            bits.readBits(1)
            val picWidthInMbsMinus1 = bits.readUE()
            val picHeightInMapUnitsMinus1 = bits.readUE()
            val frameMbsOnlyFlag = bits.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                bits.readBits(1)
            }
            bits.readBits(1)

            var frameCropLeftOffset = 0
            var frameCropRightOffset = 0
            var frameCropTopOffset = 0
            var frameCropBottomOffset = 0
            if (bits.readBits(1) == 1) {
                frameCropLeftOffset = bits.readUE()
                frameCropRightOffset = bits.readUE()
                frameCropTopOffset = bits.readUE()
                frameCropBottomOffset = bits.readUE()
            }

            val width = (picWidthInMbsMinus1 + 1) * 16 - (frameCropLeftOffset + frameCropRightOffset) * 2
            val heightFactor = if (frameMbsOnlyFlag == 1) 1 else 2
            val height = (picHeightInMapUnitsMinus1 + 1) * 16 * heightFactor - (frameCropTopOffset + frameCropBottomOffset) * 2
            width to height
        }.getOrNull()
    }

    private fun removeEmulationPreventionBytes(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            if (i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 3.toByte()) {
                out.write(0)
                out.write(0)
                i += 3
                continue
            }
            out.write(data[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun readBits(count: Int): Int {
            var result = 0
            repeat(count) {
                val byteIndex = bitPos / 8
                val bitIndex = 7 - (bitPos % 8)
                val bit = (data[byteIndex].toInt() shr bitIndex) and 1
                result = (result shl 1) or bit
                bitPos++
            }
            return result
        }

        fun readUE(): Int {
            var zeros = 0
            while (readBits(1) == 0) {
                zeros++
            }
            var value = 1
            repeat(zeros) {
                value = (value shl 1) or readBits(1)
            }
            return value - 1
        }

        fun readSE(): Int {
            val codeNum = readUE()
            return if (codeNum % 2 == 0) -(codeNum / 2) else (codeNum + 1) / 2
        }
    }

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
