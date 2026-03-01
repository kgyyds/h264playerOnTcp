package com.kgapp.h264opusServer.control

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors

class ControlClient {
    private val io = Executors.newSingleThreadExecutor()
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var outputStream: OutputStream? = null

    fun connect(port: Int) {
        io.execute {
            closeSocketLocked()
            runCatching {
                val target = Socket(HOST, port)
                socket = target
                outputStream = target.getOutputStream()
                Log.d(TAG, "control connected to $HOST:$port")
            }.onFailure {
                Log.w(TAG, "control connect failed: ${it.message}")
            }
        }
    }

    fun send(packet: ByteArray) {
        io.execute {
            val stream = outputStream ?: return@execute
            runCatching {
                stream.write(packet)
                stream.flush()
            }.onFailure {
                Log.w(TAG, "control send failed: ${it.message}")
                closeSocketLocked()
            }
        }
    }

    fun close() {
        io.execute {
            closeSocketLocked()
        }
        io.shutdown()
    }

    private fun closeSocketLocked() {
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        outputStream = null
        socket = null
    }

    companion object {
        private const val TAG = "ControlClient"
        private const val HOST = "127.0.0.1"
    }
}
