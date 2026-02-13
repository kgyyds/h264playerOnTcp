package com.kgapph264player

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.media.MediaCodec
import android.media.MediaFormat
import java.net.ServerSocket
import java.io.InputStream
import java.nio.ByteBuffer
import android.util.Log

class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private var mSurface: android.view.Surface? = null
    private var mCodec: MediaCodec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mSurface = holder.surface
                Log.d("H264", "Surface 准备完毕")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        // 启动 TCP
        Thread {
            val server = ServerSocket(40001)
            Log.d("H264", "TCP 监听 40001")
            while (true) {
                val client = server.accept()
                Log.d("H264", "客户端已连接")
                readAllStream(client.getInputStream())
            }
        }.start()
    }

    private fun readAllStream(stream: InputStream) {
        val buf = ByteArray(4096)
        val temp = mutableListOf<Byte>()

        try {
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                for (i in 0 until n) temp.add(buf[i])

                // 简单粗暴：找 00 00 01
                val bytes = temp.toByteArray()
                for (i in 0 until bytes.size - 3) {
                    if (bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 1.toByte()) {
                        val nal = bytes.copyOfRange(i, bytes.size)
                        feedRawNal(nal)
                        temp.clear()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun feedRawNal(nal: ByteArray) {
        if (mSurface == null) return

        if (mCodec == null) {
            mCodec = MediaCodec.createDecoderByType("video/avc")
            val f = MediaFormat.createVideoFormat("video/avc", 0, 0)
            mCodec!!.configure(f, mSurface, null, 0)
            mCodec!!.start()
            Log.d("H264", "🔥 解码器启动成功！！！")
        }

        val c = mCodec!!
        val idx = c.dequeueInputBuffer(10000)
        if (idx >= 0) {
            val b = c.getInputBuffer(idx)!!
            b.clear()
            b.put(nal)
            c.queueInputBuffer(idx, 0, nal.size, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            if (out < 0) break
            c.releaseOutputBuffer(out, true)
        }
    }
}
