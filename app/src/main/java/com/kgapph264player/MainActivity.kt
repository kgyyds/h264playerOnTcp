package com.kgapph264player

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.media.MediaCodec
import android.media.MediaFormat
import java.net.ServerSocket
import java.net.Socket
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
        val format = MediaFormat.createVideoFormat("video/avc", 1080, 1920)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    private fun startTcpServer() {
        val server = ServerSocket(40001)
        val client: Socket = server.accept()
        val input = client.getInputStream()
        val buffer = ByteArray(1024*16)

        val frameBuffer = mutableListOf<Byte>()

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            for (i in 0 until read) frameBuffer.add(buffer[i])
            
            // 🔹简单 demo：每次满 16k 送一次 MediaCodec
            if (frameBuffer.size >= 16*1024) {
                feedDecoder(frameBuffer.toByteArray())
                frameBuffer.clear()
            }
        }
        // 剩余部分
        if (frameBuffer.isNotEmpty()) feedDecoder(frameBuffer.toByteArray())

        client.close()
        server.close()
    }

    private fun feedDecoder(frame: ByteArray) {
        val inIndex = codec.dequeueInputBuffer(10000)
        if (inIndex >= 0) {
            val buffer: ByteBuffer = codec.getInputBuffer(inIndex)!!
            buffer.clear()
            buffer.put(frame)
            codec.queueInputBuffer(inIndex, 0, frame.size, System.nanoTime()/1000, 0)
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