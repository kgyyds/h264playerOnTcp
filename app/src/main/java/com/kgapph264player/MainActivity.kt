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
    private lateinit var codec: MediaCodec

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startDecoder(holder.surface)
                Thread { startTcpServer() }.start()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startDecoder(surface: android.view.Surface) {
        codec = MediaCodec.createDecoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080) // 横屏 1080p
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    private fun startTcpServer() {
        val server = ServerSocket(40001)
        println("🌐 Listening on 40001...")
        val client = server.accept()
        println("✅ Client connected!")
        val input = client.getInputStream()
        val buffer = ByteArray(16*1024)

        val nalBuffer = mutableListOf<Byte>()

        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                for (i in 0 until read) nalBuffer.add(buffer[i])

                // 简单策略：每次满 16KB 或者数据足够就 feed
                if (nalBuffer.size >= 16*1024) {
                    feedDecoder(nalBuffer.toByteArray())
                    nalBuffer.clear()
                }
            }

            if (nalBuffer.isNotEmpty()) feedDecoder(nalBuffer.toByteArray())

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
            server.close()
            println("🛑 Server closed")
        }
    }

    private fun feedDecoder(data: ByteArray) {
        val inIndex = codec.dequeueInputBuffer(10000)
        if (inIndex >= 0) {
            val buffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
            buffer.clear()
            buffer.put(data)
            codec.queueInputBuffer(inIndex, 0, data.size, System.nanoTime()/1000, 0)
        }

        val info = MediaCodec.BufferInfo()
        var outIndex = codec.dequeueOutputBuffer(info, 0)
        while (outIndex >= 0) {
            codec.releaseOutputBuffer(outIndex, true)
            outIndex = codec.dequeueOutputBuffer(info, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { codec.stop(); codec.release() } catch (_: Exception) {}
    }
}