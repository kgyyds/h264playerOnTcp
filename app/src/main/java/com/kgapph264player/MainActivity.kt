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
    private var codec: MediaCodec? = null
    private var surface: android.view.Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                Log.d("H264", "Surface OK")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
        })

        Thread { runTcpServer() }.start()
    }

    private fun runTcpServer() {
        val server = ServerSocket(40001)
        Log.d("H264", "TCP waiting 40001...")

        while (true) {
            try {
                val client = server.accept()
                Log.d("H264", "Client connected")
                readStream(client.getInputStream())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun readStream(stream: InputStream) {
        val buffer = ByteArray(4096)
        val tempBuffer = mutableListOf<Byte>()

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var decoderReady = false

        try {
            while (true) {
                val r = stream.read(buffer)
                if (r <= 0) break
                tempBuffer.addAll(buffer.take(r))

                val nalList = parseAnnexB(tempBuffer)
                for (nal in nalList) {
                    val type = nal[0].toInt() and 0x1F

                    if (type == 7) sps = nal
                    if (type == 8) pps = nal

                    if (!decoderReady && sps != null && pps != null && surface != null) {
                        codec = MediaCodec.createDecoderByType("video/avc")

                        // ✅ 关键修复：不写死分辨率，让解码器自动识别
                        val f = MediaFormat.createVideoFormat("video/avc", 0, 0)
                        f.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                        f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

                        codec!!.configure(f, surface, null, 0)
                        codec!!.start()
                        decoderReady = true
                        Log.d("H264", "✅ Decoder started!!!")
                    }

                    if (decoderReady) {
                        feedOneNal(nal)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx < 0) break
            c.releaseOutputBuffer(outIdx, true)
        }
    }

    private fun parseAnnexB(data: MutableList<Byte>): List<ByteArray> {
        val res = mutableListOf<ByteArray>()
        var i = 0
        val size = data.size
        var last = 0

        while (i <= size - 3) {
            val is4 = (i + 3 < size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte())
            val is3 = (i + 2 < size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte())

            if (is4 || is3) {
                if (last < i) {
                    val chunk = data.subList(last, i).toByteArray()
                    if (chunk.isNotEmpty()) {
                        res.add(chunk)
                    }
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
