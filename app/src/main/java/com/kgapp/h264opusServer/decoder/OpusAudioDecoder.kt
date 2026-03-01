package com.kgapp.h264opusServer.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class OpusAudioDecoder {
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        configureCodecIfNeeded()
        Thread({ decodeLoop() }, "opus-decode-thread").start()
    }

    fun queueAudio(buffer: ByteArray, size: Int) {
        if (!running || size <= 0) return
        val packet = ByteArray(size)
        System.arraycopy(buffer, 0, packet, 0, size)
        queue.offer(packet)
    }

    private fun configureCodecIfNeeded() {
        if (codec != null && audioTrack != null) return

        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        codec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
            configure(format, null, null, 0)
            start()
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .apply {
                play()
            }

        Log.d(TAG, "Opus decoder and AudioTrack configured")
    }

    private fun decodeLoop() {
        val localCodec = codec ?: return
        val localTrack = audioTrack ?: return
        val info = MediaCodec.BufferInfo()

        while (running) {
            try {
                val packet = queue.take()
                val inputIndex = localCodec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    localCodec.getInputBuffer(inputIndex)?.let { inputBuffer ->
                        inputBuffer.clear()
                        inputBuffer.put(packet)
                        localCodec.queueInputBuffer(inputIndex, 0, packet.size, System.nanoTime() / 1_000, 0)
                    }
                }

                var outputIndex = localCodec.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    val outputBuffer: ByteBuffer = localCodec.getOutputBuffer(outputIndex) ?: break
                    val pcm = ByteArray(info.size)
                    outputBuffer.get(pcm)
                    outputBuffer.clear()
                    localTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                    localCodec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = localCodec.dequeueOutputBuffer(info, 0)
                }
            } catch (ex: Exception) {
                Log.d(TAG, "decodeLoop error: ${ex.message}")
            }
        }
    }

    fun release() {
        running = false
        queue.clear()
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null

        audioTrack?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioTrack = null
        Log.d(TAG, "Opus decoder released")
    }

    companion object {
        private const val TAG = "OpusAudioDecoder"
        private const val MIME_TYPE = "audio/opus"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 2
    }
}
