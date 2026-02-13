package com.kgapph264player

import android.app.Activity
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var codec: MediaCodec

    private val TAG = "H264Player"

    // H.264 NAL 起始码 0x00000001
    private val NAL_PREFIX = byteArrayOf(0x00,0x00,0x00,0x01)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created, starting decoder and TCP server")
                startDecoder(holder.surface)
                Thread { startTcpServer() }.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startDecoder(surface: Surface) {
        codec = MediaCodec.createDecoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 1080, 1920)
        codec.configure(format, surface, null, 0)
        codec.start()
        Log.i(TAG, "Decoder started")
    }

    private fun startTcpServer() {
        try {
            val server = ServerSocket(40001)
            Log.i(TAG, "TCP server listening on port 40001")

            val client: Socket = server.accept()
            Log.i(TAG, "Client connected: ${client.inetAddress.hostAddress}")

            val input = client.getInputStream()
            val buffer = ByteArray(16 * 1024)
            val stream = mutableListOf<Byte>()

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                for (i in 0 until read) stream.add(buffer[i])

                // 检查 NAL 单元
                processStream(stream)
            }

            Log.i(TAG, "Client disconnected")
            client.close()
            server.close()
            Log.i(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "TCP server error", e)
        }
    }

    // 查找 NAL 单元并 feed 给 MediaCodec
    private fun processStream(stream: MutableList<Byte>) {
        while (true) {
            val start = findNalPrefix(stream, 0)
            if (start == -1 || start + 4 >= stream.size) break

            val next = findNalPrefix(stream, start + 4)
            if (next == -1) break

            val nalUnit = stream.subList(start, next).toByteArray()
            feedDecoder(nalUnit)
            // 移除已送的数据
            for (i in 0 until next) stream.removeAt(0)
        }
    }

    private fun findNalPrefix(data: List<Byte>, from: Int): Int {
        for (i in from until data.size - 3) {
            if (data[i] == 0.toByte() &&
                data[i+1] == 0.toByte() &&
                data[i+2] == 0.toByte() &&
                data[i+3] == 1.toByte()) return i
        }
        return -1
    }

    private fun feedDecoder(nal: ByteArray) {
        try {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
                buffer.clear()
                buffer.put(nal)
                codec.queueInputBuffer(inIndex, 0, nal.size, System.nanoTime()/1000, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true)
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder feed error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { codec.stop(); codec.release() } catch (_: Exception) {}
        Log.i(TAG, "Activity destroyed, codec released")
    }
}