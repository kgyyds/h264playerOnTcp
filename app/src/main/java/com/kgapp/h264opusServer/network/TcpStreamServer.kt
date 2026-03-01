package com.kgapp.h264opusServer.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket

class TcpStreamServer(
    private val videoPort: Int,
    private val audioPort: Int,
    private val onVideoBytes: (ByteArray, Int) -> Unit,
    private val onAudioBytes: (ByteArray, Int) -> Unit
) {
    private var videoServerSocket: ServerSocket? = null
    private var audioServerSocket: ServerSocket? = null
    private var videoAcceptJob: Job? = null
    private var audioAcceptJob: Job? = null

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
            }
        }.start()
    }

    fun stop() {
        Log.d(TAG, "stop server")
        videoAcceptJob?.cancel()
        audioAcceptJob?.cancel()
        videoAcceptJob = null
        audioAcceptJob = null
        runCatching { videoServerSocket?.close() }
        runCatching { audioServerSocket?.close() }
        videoServerSocket = null
        audioServerSocket = null
    }

    companion object {
        private const val TAG = "TcpStreamServer"
    }
}
