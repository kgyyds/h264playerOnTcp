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
                Thread { startTcpServer(holder.surface) }.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startTcpServer(surface: android.view.Surface) {
        val server = ServerSocket(40001)
        println("🌐 Listening on 40001...")

        val client = server.accept()
        println("✅ Client connected!")

        val input = client.getInputStream()
        val buffer = ByteArray(16 * 1024)
        val nalBuffer = mutableListOf<Byte>()

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var decoderStarted = false

        try {
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                nalBuffer.addAll(buffer.take(read))

                // 🔹按 0x00000001 分割 NAL 单元
                val units = extractNALUnits(nalBuffer)
                for (nal in units) {
                    val type = nalType(nal)
                    when (type) {
                        7 -> sps = nal // SPS
                        8 -> pps = nal // PPS
                        else -> {
                            if (!decoderStarted && sps != null && pps != null) {
                                startDecoder(surface, sps, pps)
                                decoderStarted = true
                            }
                            if (decoderStarted) feedDecoder(nal)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            client.close()
            server.close()
            codec?.stop()
            codec?.release()
            println("🛑 Server closed")
        }
    }

    private fun startDecoder(surface: android.view.Surface, sps: ByteArray, pps: ByteArray) {
        codec = MediaCodec.createDecoderByType("video/avc")
        val format = MediaFormat.createVideoFormat("video/avc", 1080, 1920)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        codec!!.configure(format, surface, null, 0)
        codec!!.start()
        println("🎬 Decoder started")
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

    private fun nalType(nal: ByteArray): Int {
        // 第一个字节就是 nal header: F | NRI | Type
        if (nal.isEmpty()) return -1
        return nal[0].toInt() and 0x1F
    }

    private fun extractNALUnits(buffer: MutableList<Byte>): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < buffer.size - 3) {
            if (buffer[i] == 0.toByte() && buffer[i+1] == 0.toByte() &&
                buffer[i+2] == 0.toByte() && buffer[i+3] == 1.toByte()) {
                if (start != -1) {
                    units.add(buffer.subList(start, i).toByteArray())
                }
                start = i + 4
                i += 4
            } else i++
        }
        // 剩余部分保留
        if (start != -1) {
            val remaining = buffer.subList(start, buffer.size).toByteArray()
            buffer.clear()
            buffer.addAll(remaining.toList())
        }
        return units
    }

    override fun onDestroy() {
        super.onDestroy()
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
    }
}