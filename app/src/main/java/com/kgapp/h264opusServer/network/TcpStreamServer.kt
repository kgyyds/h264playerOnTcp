package com.kgapp.h264opusServer.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class TcpStreamServer(
    private val videoPort: Int,
    private val audioPort: Int,
    private val controlPort: Int,
    private val onVideoBytes: (ByteArray, Int) -> Unit,
    private val onAudioBytes: (ByteArray, Int) -> Unit,
    private val onVideoConnected: (Boolean) -> Unit,
    private val onControlOutput: (OutputStream?) -> Unit,
) {
    private var videoServerSocket: ServerSocket? = null
    private var audioServerSocket: ServerSocket? = null
    private var videoAcceptJob: Job? = null
    private var audioAcceptJob: Job? = null
    private var controlAcceptJob: Job? = null
    private var controlServerSocket: ServerSocket? = null

    fun start(scope: CoroutineScope) {
        videoAcceptJob = scope.launch(Dispatchers.IO) {
            runServer(
                type = "video",
                port = videoPort,
                onServerCreated = { videoServerSocket = it },
                onBytes = onVideoBytes
            )
        }
        audioAcceptJob = scope.launch(Dispatchers.IO) {
            runServer(
                type = "audio",
                port = audioPort,
                onServerCreated = { audioServerSocket = it },
                onBytes = onAudioBytes
            )
        }
        controlAcceptJob = scope.launch(Dispatchers.IO) {
            runControlServer(controlPort)
        }
    }

    private suspend fun runServer(
        type: String,
        port: Int,
        onServerCreated: (ServerSocket) -> Unit,
        onBytes: (ByteArray, Int) -> Unit
    ) {
        try {
            val server = ServerSocket(port)
            onServerCreated(server)
            Log.d(TAG, "$type server listening at $port")
            while (true) {
                val socket = server.accept()
                Log.d(TAG, "$type connection established from ${socket.inetAddress.hostAddress}")
                if (type == "video") onVideoConnected(true)
                handleClient(type, socket, onBytes)
            }
        } catch (ex: Exception) {
            Log.d(TAG, "$type server stopped: ${ex.message}")
        }
    }

    private fun handleClient(
        type: String,
        socket: Socket,
        onBytes: (ByteArray, Int) -> Unit
    ) {
        Thread {
            try {
                socket.use { client ->
                    val input = BufferedInputStream(client.getInputStream())
                    val direct = ByteArray(8 * 1024)
                    while (true) {
                        val size = input.read(direct)
                        if (size <= 0) break
                        onBytes(direct, size)
                    }
                }
            } catch (ex: Exception) {
                Log.d(TAG, "$type client disconnected: ${ex.message}")
            } finally {
                if (type == "video") onVideoConnected(false)
            }
        }.start()
    }

    private fun runControlServer(port: Int) {
        try {
            val server = ServerSocket(port)
            controlServerSocket = server
            Log.d(TAG, "control server listening at $port")
            while (true) {
                val socket = server.accept()
                Log.d(TAG, "control connection established from ${socket.inetAddress.hostAddress}")
                Thread {
                    socket.use { controlSocket ->
                        onControlOutput(controlSocket.getOutputStream())
                        try {
                            controlSocket.soTimeout = 1000
                            val inStream = controlSocket.getInputStream()
                            while (true) {
                                val read = runCatching { inStream.read() }.getOrElse {
                                    if (it is java.net.SocketTimeoutException) {
                                        return@getOrElse Int.MIN_VALUE
                                    }
                                    -1
                                }
                                if (read == Int.MIN_VALUE) continue
                                if (read < 0) break
                            }
                        } catch (_: Exception) {
                            // ignore
                        } finally {
                            onControlOutput(null)
                            Log.d(TAG, "control client disconnected")
                        }
                    }
                }.start()
            }
        } catch (ex: Exception) {
            Log.d(TAG, "control server stopped: ${ex.message}")
            onControlOutput(null)
        }
    }

    fun stop() {
        Log.d(TAG, "stop server")
        videoAcceptJob?.cancel()
        audioAcceptJob?.cancel()
        controlAcceptJob?.cancel()
        videoAcceptJob = null
        audioAcceptJob = null
        controlAcceptJob = null
        runCatching { videoServerSocket?.close() }
        runCatching { audioServerSocket?.close() }
        runCatching { controlServerSocket?.close() }
        videoServerSocket = null
        audioServerSocket = null
        controlServerSocket = null
        onVideoConnected(false)
        onControlOutput(null)
    }

    companion object {
        private const val TAG = "TcpStreamServer"
    }
}
