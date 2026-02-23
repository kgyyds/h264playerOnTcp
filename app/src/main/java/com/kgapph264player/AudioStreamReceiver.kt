package com.kgapph264player

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class AudioStreamReceiver(
    private val port: Int = 40002,
    private val mode: AudioMode = AudioMode.OPUS,
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 2,
    private val idleTimeoutMs: Long = TimeUnit.SECONDS.toMillis(3)
) {
    companion object {
        private const val TAG = "AudioStreamReceiver"
        private const val SOCKET_TIMEOUT_MS = 1_000
    }

    enum class AudioMode {
        OPUS,
        PCM
    }

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var lastReceivedTimeMs = 0L

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var receiverThread: Thread? = null
    private var watchdogThread: Thread? = null

    private val audioPlayer = AudioPlayer(sampleRate = sampleRate, channelCount = channelCount)
    private val audioDecoder = if (mode == AudioMode.OPUS) {
        AudioDecoder(sampleRate = sampleRate, channelCount = channelCount)
    } else {
        null
    }

    fun start() {
        if (running) return
        running = true
        paused = false

        receiverThread = Thread {
            runAcceptLoop()
        }.apply { start() }
    }

    fun pause() {
        paused = true
        audioPlayer.pause()
    }

    fun resume() {
        if (!running) return
        paused = false
        audioPlayer.resume()
    }

    fun stop() {
        running = false
        paused = false

        closeClientSocket()
        closeServerSocket()

        try {
            receiverThread?.interrupt()
        } catch (ignored: Exception) {
        }

        try {
            watchdogThread?.interrupt()
        } catch (ignored: Exception) {
        }

        fullCleanup()
    }

    private fun runAcceptLoop() {
        try {
            serverSocket = ServerSocket(port)
            Log.i(TAG, "Audio server listening on port $port, mode=$mode")

            while (running) {
                try {
                    val accepted = serverSocket?.accept() ?: break
                    clientSocket = accepted
                    accepted.soTimeout = SOCKET_TIMEOUT_MS
                    Log.i(TAG, "Audio client connected")

                    startPlaybackPipeline()
                    lastReceivedTimeMs = System.currentTimeMillis()
                    startWatchdog(accepted)

                    handleClient(accepted)
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Audio connection error", e)
                } finally {
                    fullCleanup()
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Audio server failure", e)
        } finally {
            closeServerSocket()
            running = false
        }
    }

    private fun handleClient(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        var ptsUs = 0L

        while (running && !socket.isClosed) {
            if (paused) {
                Thread.sleep(20)
                continue
            }

            when (mode) {
                AudioMode.OPUS -> {
                    val frame = readLengthPrefixedFrame(input) ?: break
                    if (frame.isEmpty()) continue
                    lastReceivedTimeMs = System.currentTimeMillis()

                    val pcmFrames = audioDecoder?.decode(frame, ptsUs).orEmpty()
                    for (pcm in pcmFrames) {
                        audioPlayer.writePcm(pcm)
                    }

                    ptsUs += 20_000
                }

                AudioMode.PCM -> {
                    val pcm = readPcmChunk(input) ?: break
                    if (pcm.isEmpty()) continue
                    lastReceivedTimeMs = System.currentTimeMillis()
                    audioPlayer.writePcm(pcm)
                }
            }
        }
    }

    private fun readLengthPrefixedFrame(input: DataInputStream): ByteArray? {
        return try {
            val length = input.readInt()
            if (length <= 0 || length > 65_536) {
                Log.w(TAG, "Invalid Opus frame length: $length")
                null
            } else {
                val frame = ByteArray(length)
                input.readFully(frame)
                frame
            }
        } catch (e: java.net.SocketTimeoutException) {
            ByteArray(0)
        } catch (e: Exception) {
            null
        }
    }

    private fun readPcmChunk(input: DataInputStream): ByteArray? {
        return try {
            val buffer = ByteArray(4096)
            val read = input.read(buffer)
            if (read <= 0) null else buffer.copyOf(read)
        } catch (e: java.net.SocketTimeoutException) {
            ByteArray(0)
        } catch (e: Exception) {
            null
        }
    }

    private fun startPlaybackPipeline() {
        try {
            audioPlayer.start()
            audioDecoder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback pipeline", e)
        }
    }

    private fun startWatchdog(socket: Socket) {
        watchdogThread = Thread {
            while (running && !socket.isClosed) {
                try {
                    val idle = System.currentTimeMillis() - lastReceivedTimeMs
                    if (lastReceivedTimeMs > 0 && idle > idleTimeoutMs) {
                        Log.w(TAG, "Audio idle timeout, closing client")
                        closeClientSocket()
                        break
                    }
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }.apply { start() }
    }

    private fun fullCleanup() {
        closeClientSocket()
        audioDecoder?.stop()
        audioPlayer.stop()
        lastReceivedTimeMs = 0L
    }

    private fun closeClientSocket() {
        try {
            clientSocket?.close()
        } catch (ignored: Exception) {
        }
        clientSocket = null
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null
    }
}
