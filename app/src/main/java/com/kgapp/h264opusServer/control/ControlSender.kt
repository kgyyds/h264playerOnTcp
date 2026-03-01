package com.kgapp.h264opusServer.control

import android.util.Log
import java.io.OutputStream
import java.util.concurrent.Executors

class ControlSender {
    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var outputStream: OutputStream? = null

    fun bindOutputStream(stream: OutputStream?) {
        outputStream = stream
    }

    fun send(packet: ByteArray) {
        io.execute {
            val stream = outputStream ?: return@execute
            runCatching {
                stream.write(packet)
                stream.flush()
            }.onFailure {
                Log.w(TAG, "control send failed: ${it.message}")
                outputStream = null
            }
        }
    }

    fun close() {
        outputStream = null
        io.shutdown()
    }

    companion object {
        private const val TAG = "ControlSender"
    }
}
