package com.kgapph264player

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.media.MediaCodec
import android.media.MediaFormat
import java.net.ServerSocket
import java.nio.ByteBuffer

class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private var codec: MediaCodec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // ✅ 先开端口监听
                Thread { startTcpServer(holder) }.start()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startTcpServer(holder: SurfaceHolder) {
        val server = ServerSocket(40001)
        println("🌐 Listening on port 40001...")
        val client = server.accept()
        println("✅ Client connected!")

        val input = client.getInputStream()
        val buffer = ByteArray(16 * 1024)
        val nalBuffer = mutableListOf<Byte>()
        var firstFrame = true

        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                for (i in 0 until read) nalBuffer.add(buffer[i])

                // 🔹 收到数据就 feed，第一次收到则创建 MediaCodec
                if (nalBuffer.isNotEmpty()) {
                    val data = nalBuffer.toByteArray()
                    if (firstFrame) {
                        // ⚡ 第一次收到数据，创建 decoder
                        startDecoder(holder.surface, data)
                        firstFrame = false
                    } else {
                        feedDecoder(data)
                    }
                    nalBuffer.clear()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
            server.close()
            println("🛑 Server closed")
        }
    }

    private fun startDecoder(surface: android.view.Surface, firstData: ByteArray) {
        codec = MediaCodec.createDecoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080) // 横屏 1080p
        codec!!.configure(format, surface, null, 0)
        codec!!.start()
        println("🎬 Decoder started")
        feedDecoder(firstData)
    }

    private fun feedDecoder(data: ByteArray) {
        val c = codec ?: return
        val inIndex = c.dequeueInputBuffer(10000)
        if (inIndex >= 0) {
            val buffer: ByteBuffer = c.getInputBuffer(inIndex)!!
            buffer.clear()
            buffer.put(data)
            c.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
        }

        val info = MediaCodec.BufferInfo()
        var outIndex = c.dequeueOutputBuffer(info, 0)
        while (outIndex >= 0) {
            c.releaseOutputBuffer(outIndex, true)
            outIndex = c.dequeueOutputBuffer(info, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
    }
}