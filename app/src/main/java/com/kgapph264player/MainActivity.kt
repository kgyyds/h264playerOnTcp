package com.kgapph264player

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.media.MediaCodec
import android.media.MediaFormat
import java.net.ServerSocket
import java.nio.ByteBuffer
import android.util.Log

class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private var codec: MediaCodec? = null
    private val PORT = 40001

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Thread {
                    startH264Server(holder.surface)
                }.start()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startH264Server(surface: android.view.Surface) {
        val server = ServerSocket(PORT)
        Log.d("H264", "TCP 监听 $PORT")

        val socket = server.accept()
        val input = socket.getInputStream()
        val buffer = ByteArray(4096)
        val tempBuffer = mutableListOf<Byte>()

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var decoderConfigured = false

        while (true) {
            val r = input.read(buffer)
            if (r <= 0) break
            tempBuffer.addAll(buffer.take(r))

            val nalList = parseAnnexB(tempBuffer)
            for (nal in nalList) {
                val type = nal[0].toInt() and 0x1F

                if (type == 7) sps = nal
                if (type == 8) pps = nal

                if (!decoderConfigured && sps != null && pps != null) {
                    configureDecoder(surface, sps, pps)
                    decoderConfigured = true
                    Log.d("H264", "解码器启动成功")
                }

                if (decoderConfigured) {
                    feedOneNal(nal)
                }
            }
        }
    }

    private fun configureDecoder(surface: android.view.Surface, sps: ByteArray, pps: ByteArray) {
        codec = MediaCodec.createDecoderByType("video/avc")
        val f = MediaFormat.createVideoFormat("video/avc", 1920, 1080)

        f.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

        codec!!.configure(f, surface, null, 0)
        codec!!.start()
    }

    private fun feedOneNal(nal: ByteArray) {
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(10000)
        if (idx < 0) return

        val buf = c.getInputBuffer(idx)!!
        buf.clear()
        buf.put(nal)

        val type = nal[0].toInt() and 0x1F
        val flags = if (type == 5) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0

        c.queueInputBuffer(idx, 0, nal.size, 0, flags)

        // 👇 这一步是你黑屏的核心：必须循环取输出
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx < 0) break
            c.releaseOutputBuffer(outIdx, true) // 👈 真正渲染到屏幕
        }
    }

    // 👇 最重要：保留 00 00 01 / 00 00 00 01 起始码！
    private fun parseAnnexB(data: MutableList<Byte>): List<ByteArray> {
        val res = mutableListOf<ByteArray>()
        var i = 0
        val size = data.size
        var last = 0

        while (i <= size - 3) {
            val is4 = (i+3 < size && data[i]==0.toByte() && data[i+1]==0.toByte() && data[i+2]==0.toByte() && data[i+3]==1.toByte())
            val is3 = (i+2 < size && data[i]==0.toByte() && data[i+1]==0.toByte() && data[i+2]==1.toByte())

            if (is4 || is3) {
                if (last < i) {
                    val chunk = data.subList(last, i).toByteArray()
                    if (chunk.isNotEmpty()) res.add(chunk)
                }
                last = i
                i += if (is4) 4 else 3
            } else {
                i++
            }
        }

        val left = data.subList(last, data.size).toByteArray()
        data.clear()
        data.addAll(left.toList())
        return res
    }
}
