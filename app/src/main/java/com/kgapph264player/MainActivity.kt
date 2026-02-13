package com.kgapph264player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var csdSent = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val pts = AtomicLong(0)

    // 用于等待 Surface 就绪
    private val surfaceLatch = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                surfaceLatch.countDown() // 通知 Surface 已可用
                Log.d("H264", "Surface created")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
            }
        })

        // 启动 TCP 服务线程
        Thread { serverThread() }.start()
    }

    // ------------------------------------------------------------
    // 1. TCP 服务：接收数据，提取 SPS/PPS，启动解码器，持续喂帧
    // ------------------------------------------------------------
    private fun serverThread() {
        ServerSocket(40001).use { server ->
            Log.d("H264", "监听 40001 ...")
            val socket = server.accept()
            Log.d("H264", "客户端已连接: ${socket.inetAddress}")
            val input = socket.getInputStream()

            // ----- 阶段1：从流头部提取 SPS 和 PPS -----
            extractSpsPps(input)

            // ----- 阶段2：Surface 就绪后，初始化解码器（必须 UI 线程）-----
            surfaceLatch.await() // 等待 Surface 可用
            runOnUiThread { initDecoder() }

            // ----- 阶段3：持续读取剩余流，切分 NALU，喂给解码器 -----
            val nalParser = NalParser()
            val buffer = ByteArray(8192)
            while (true) {
                val len = input.read(buffer)
                if (len <= 0) break
                nalParser.append(buffer, 0, len) { nalu ->
                    feedDecoder(nalu)
                }
            }
        }
    }

    // ------------------------------------------------------------
    // 2. 从 InputStream 中提取 SPS (nal=7) 和 PPS (nal=8)
    //    阻塞直到两个参数集都收到
    // ------------------------------------------------------------
    private fun extractSpsPps(input: InputStream) {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var foundSps = false
        var foundPps = false

        while (!(foundSps && foundPps)) {
            val len = input.read(buf)
            if (len <= 0) break
            baos.write(buf, 0, len)
            val data = baos.toByteArray()

            // 扫描所有 NALU
            var i = 0
            while (i < data.size - 4) {
                val startCodeLen = findStartCode(data, i)
                if (startCodeLen > 0) {
                    var end = i + startCodeLen
                    while (end < data.size - 4) {
                        if (findStartCode(data, end) > 0) break
                        end++
                    }
                    if (end > i) {
                        val nalu = data.copyOfRange(i, end) // 包含起始码
                        val nalType = nalu[startCodeLen].toInt() and 0x1F
                        when (nalType) {
                            7 -> { sps = nalu; foundSps = true }
                            8 -> { pps = nalu; foundPps = true }
                        }
                    }
                    i = end
                } else i++
            }
            // 保留未处理完的数据（可能是不完整的 NALU 尾部）
            if (i < data.size) {
                baos.reset()
                baos.write(data, i, data.size - i)
            } else {
                baos.reset()
            }
        }
        Log.d("H264", "SPS/PPS 提取完成")
    }

    // ------------------------------------------------------------
    // 3. 初始化解码器，设置 csd-0（带起始码的 SPS+PPS）
    // ------------------------------------------------------------
    private fun initDecoder() {
        if (sps == null || pps == null) {
            Log.e("H264", "SPS/PPS 为空，无法初始化")
            return
        }
        if (surface == null) {
            Log.e("H264", "Surface 为空")
            return
        }

        // 从 SPS 中解析宽高（可选，MediaCodec 会自动解析）
        // 这里给个默认值，实际运行时会被解码器输出的实际分辨率覆盖
        val format = MediaFormat.createVideoFormat("video/avc", 720, 1280)

        // csd-0 必须包含带起始码的 SPS 和 PPS，拼接即可
        val csdBuffer = ByteBuffer.allocate(sps!!.size + pps!!.size)
        csdBuffer.put(sps!!)
        csdBuffer.put(pps!!)
        csdBuffer.position(0)
        format.setByteBuffer("csd-0", csdBuffer)

        codec = MediaCodec.createDecoderByType("video/avc")
        codec!!.configure(format, surface, null, 0)
        codec!!.start()
        csdSent = true
        Log.d("H264", "解码器启动成功")

        // 启动独立输出线程
        Thread { outputLoop() }.start()
    }

    // ------------------------------------------------------------
    // 4. 输出循环（必须与输入分离，否则阻塞）
    // ------------------------------------------------------------
    private fun outputLoop() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val codec = codec ?: break
            try {
                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outIndex >= 0 -> {
                        // 立即渲染
                        codec.releaseOutputBuffer(outIndex, true)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        Log.d("H264", "输出格式: ${newFormat.getInteger(MediaFormat.KEY_WIDTH)}x${newFormat.getInteger(MediaFormat.KEY_HEIGHT)}")
                    }
                }
            } catch (e: Exception) {
                Log.e("H264", "输出循环异常", e)
            }
        }
    }

    // ------------------------------------------------------------
    // 5. 向解码器输入一帧完整 NALU（带起始码）
    // ------------------------------------------------------------
    private fun feedDecoder(nalu: ByteArray) {
        if (!csdSent) return
        val codec = codec ?: return
        try {
            val inIdx = codec.dequeueInputBuffer(10000)
            if (inIdx < 0) return
            val inBuf = codec.getInputBuffer(inIdx)!!
            inBuf.clear()
            inBuf.put(nalu)
            // PTS 必须单调递增，单位微秒，假设 30fps ≈ 33333 us
            val ptsUs = pts.addAndGet(33333)
            codec.queueInputBuffer(inIdx, 0, nalu.size, ptsUs, 0)
        } catch (e: Exception) {
            Log.e("H264", "喂帧失败", e)
        }
    }

    // ------------------------------------------------------------
    // 6. 工具类：流式 NALU 解析器，避免重复扫描
    // ------------------------------------------------------------
    inner class NalParser {
        private val buffer = ByteArrayOutputStream()
        private var leftover = ByteArray(0)

        fun append(data: ByteArray, offset: Int, len: Int, onNalu: (ByteArray) -> Unit) {
            buffer.write(data, offset, len)
            val all = buffer.toByteArray()
            val result = parseNalus(all, onNalu)
            // 保留未完整解析的尾部数据
            if (result < all.size) {
                buffer.reset()
                buffer.write(all, result, all.size - result)
            } else {
                buffer.reset()
            }
        }

        // 从字节数组中切出完整 NALU，每切出一个调用 onNalu
        private fun parseNalus(data: ByteArray, onNalu: (ByteArray) -> Unit): Int {
            var i = 0
            val size = data.size
            while (i < size - 4) {
                val startLen = findStartCode(data, i)
                if (startLen > 0) {
                    var end = i + startLen
                    while (end < size - 4) {
                        if (findStartCode(data, end) > 0) break
                        end++
                    }
                    if (end > i) {
                        val nalu = data.copyOfRange(i, end)
                        onNalu(nalu)
                    }
                    i = end
                } else i++
            }
            return i // 返回已处理完的位置
        }
    }

    // ------------------------------------------------------------
    // 7. 查找起始码，支持 0x000001 和 0x00000001
    // ------------------------------------------------------------
    private fun findStartCode(data: ByteArray, offset: Int): Int {
        if (offset + 3 > data.size) return 0
        if (data[offset] == 0x00.toByte() && data[offset + 1] == 0x00.toByte() &&
            data[offset + 2] == 0x00.toByte() && data[offset + 3] == 0x01.toByte()) {
            return 4
        }
        if (offset + 2 > data.size) return 0
        if (data[offset] == 0x00.toByte() && data[offset + 1] == 0x00.toByte() &&
            data[offset + 2] == 0x01.toByte()) {
            return 3
        }
        return 0
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}