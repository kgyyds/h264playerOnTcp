package com.kgapph264player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

class AudioDecoder(
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 2
) {
    companion object {
        private const val TAG = "AudioDecoder"
        private const val MIME_OPUS = "audio/opus"
    }

    private var codec: MediaCodec? = null

    @Volatile
    private var started = false

    @Synchronized
    fun start() {
        if (started) return

        val format = MediaFormat.createAudioFormat(MIME_OPUS, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }

        codec = MediaCodec.createDecoderByType(MIME_OPUS).apply {
            configure(format, null, null, 0)
            start()
        }
        started = true
        Log.i(TAG, "Opus decoder started")
    }

    fun decode(opusFrame: ByteArray, ptsUs: Long): List<ByteArray> {
        val mediaCodec = codec ?: return emptyList()

        try {
            val inputIndex = mediaCodec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val input = mediaCodec.getInputBuffer(inputIndex) ?: return emptyList()
                input.clear()
                input.put(opusFrame)
                mediaCodec.queueInputBuffer(inputIndex, 0, opusFrame.size, ptsUs, 0)
            }

            return drainOutput(mediaCodec)
        } catch (e: Exception) {
            Log.e(TAG, "decode failed", e)
        }

        return emptyList()
    }

    private fun drainOutput(mediaCodec: MediaCodec): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val info = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = mediaCodec.dequeueOutputBuffer(info, 0)
            when {
                outputIndex >= 0 -> {
                    val buffer = mediaCodec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        copyBuffer(buffer, info, pcm)
                        out.add(pcm)
                    }
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${mediaCodec.outputFormat}")
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }

        return out
    }

    private fun copyBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo, out: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            buffer.get(out)
        } else {
            val duplicate = buffer.duplicate()
            duplicate.position(info.offset)
            duplicate.limit(info.offset + info.size)
            duplicate.get(out)
        }
    }

    @Synchronized
    fun stop() {
        started = false
        try {
            codec?.stop()
        } catch (ignored: Exception) {
        }
        try {
            codec?.release()
        } catch (ignored: Exception) {
        }
        codec = null
        Log.i(TAG, "Opus decoder released")
    }
}
