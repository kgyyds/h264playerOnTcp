package com.kgapph264player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

class AudioPlayer(
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 2
) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private val channelConfig = if (channelCount == 1) {
        AudioFormat.CHANNEL_OUT_MONO
    } else {
        AudioFormat.CHANNEL_OUT_STEREO
    }

    private val minBufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(sampleRate / 10)

    @Volatile
    private var isStarted = false

    @Volatile
    private var isPaused = false

    private var audioTrack: AudioTrack? = null

    @Synchronized
    fun start() {
        if (isStarted) return

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize * 4)
            .build()

        track.play()
        audioTrack = track
        isStarted = true
        isPaused = false
        Log.i(TAG, "AudioTrack started, buffer=${minBufferSize * 4}")
    }

    fun writePcm(pcm: ByteArray, offset: Int = 0, size: Int = pcm.size) {
        if (!isStarted || isPaused) return
        val track = audioTrack ?: return

        var written = 0
        while (written < size) {
            val count = track.write(pcm, offset + written, size - written, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) {
                Log.w(TAG, "AudioTrack write failed: $count")
                break
            }
            written += count
        }
    }

    @Synchronized
    fun pause() {
        if (!isStarted || isPaused) return
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            isPaused = true
        } catch (e: Exception) {
            Log.e(TAG, "pause failed", e)
        }
    }

    @Synchronized
    fun resume() {
        if (!isStarted || !isPaused) return
        try {
            audioTrack?.play()
            isPaused = false
        } catch (e: Exception) {
            Log.e(TAG, "resume failed", e)
        }
    }

    @Synchronized
    fun stop() {
        isPaused = false
        isStarted = false
        try {
            audioTrack?.stop()
        } catch (ignored: Exception) {
        }
        try {
            audioTrack?.flush()
        } catch (ignored: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (ignored: Exception) {
        }
        audioTrack = null
        Log.i(TAG, "AudioTrack released")
    }
}
