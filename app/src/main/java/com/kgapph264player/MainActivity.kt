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
    private var codec: MediaCodec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mSurface = holder.surface
                Log.d("H264", "Surface OK")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        Thread {
            val server = ServerSocket(40001)
            Log.d("H264", "TCP 40001 等待连接...")
            while (true) {
                val socket = server.accept()
                Log.d("H264", "客户端已连接！")
                val input = socket.getInputStream()

                // 直接创建解码器，不等 SPS/PPS
                codec = MediaCodec.createDecoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", 0, 0)
                codec!!.configure(format, mSurface, null, 0)
                codec!!.start()
                Log.d("H264", "🔥 解码器已启动！")

                val buffer = ByteArray(4096)
                while (true) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    feedData(buffer, len)
                }
            }
        }.start()
    }

    private fun feedData(data: ByteArray, len: Int) {
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(10000)
        if (idx >= 0) {
            val b = c.getInputBuffer(idx)!!
            b.clear()
            b.put(data, 0, len)
            c.queueInputBuffer(idx, 0, len, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx < 0) break
            c.releaseOutputBuffer(outIdx, true)
        }
    }
}
