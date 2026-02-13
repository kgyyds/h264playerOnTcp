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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("H264", "Surface创建成功")
                Thread { startTcpServer(holder.surface) }.start()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun startTcpServer(surface: android.view.Surface) {
        val server = ServerSocket(40001)
        Log.d("H264", "TCP服务启动 40001")

        val client = server.accept()
        Log.d("H264", "客户端已连接")

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

                // 同时支持 00 00 01 和 00 00 00 01 切割
                val units = extractNALUnits(nalBuffer)
                for (nal in units) {
                    val type = nalType(nal)
                    Log.d("H264", "收到NAL类型: $type")

                    when (type) {
                        7 -> {
                            sps = nal
                            Log.d("H264", "拿到SPS")
                        }
                        8 -> {
                            pps = nal
                            Log.d("H264", "拿到PPS")
                        }
                        5, 1 -> { // 5=I帧,1=P帧
                            if (!decoderStarted && sps != null && pps != null) {
                                startDecoder(surface, sps, pps)
                                decoderStarted = true
                                Log.d("H264", "解码器启动")
                            }
                            if (decoderStarted) {
                                feedDecoder(nal, type == 5)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("H264", "错误: ${e.message}")
        } finally {
            client.close()
            server.close()
            codec?.release()
        }
    }

    private fun startDecoder(surface: android.view.Surface, sps: ByteArray, pps: ByteArray) {
        codec = MediaCodec.createDecoderByType("video/avc")

        // 横屏播放：分辨率反过来！
        val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

        codec!!.configure(format, surface, null, 0)
        codec!!.start()
    }

    // 关键：标记是否是I帧
    private fun feedDecoder(data: ByteArray, isIFrame: Boolean) {
        val c = codec ?: return
        val inIndex = c.dequeueInputBuffer(10000)
        if (inIndex >= 0) {
            val buf = c.getInputBuffer(inIndex)!!
            buf.clear()
            buf.put(data)

            val flags = if (isIFrame) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
            c.queueInputBuffer(inIndex, 0, data.size, 0, flags)
        }

        val info = MediaCodec.BufferInfo()
        var out = c.dequeueOutputBuffer(info, 10000)
        while (out >= 0) {
            c.releaseOutputBuffer(out, true)
            out = c.dequeueOutputBuffer(info, 0)
        }
    }

    private fun nalType(nal: ByteArray): Int {
        return if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else -1
    }

    // ✅ 修复：同时支持 00 00 01 和 00 00 00 01
    private fun extractNALUnits(buffer: MutableList<Byte>): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = 0

        while (i < buffer.size - 2) {
            val is3Byte = (i + 2 < buffer.size &&
                    buffer[i] == 0.toByte() &&
                    buffer[i+1] == 0.toByte() &&
                    buffer[i+2] == 1.toByte())

            val is4Byte = (i + 3 < buffer.size &&
                    buffer[i] == 0.toByte() &&
                    buffer[i+1] == 0.toByte() &&
                    buffer[i+2] == 0.toByte() &&
                    buffer[i+3] == 1.toByte())

            if (is3Byte || is4Byte) {
                if (lastStart < i) {
                    val unit = buffer.subList(lastStart, i).toByteArray()
                    if (unit.isNotEmpty()) units.add(unit)
                }
                lastStart = i + if (is4Byte) 4 else 3
                i = if (is4Byte) i + 4 else i + 3
            } else {
                i++
            }
        }

        val remaining = buffer.subList(lastStart, buffer.size).toByteArray()
        buffer.clear()
        buffer.addAll(remaining.toList())
        return units
    }
}
