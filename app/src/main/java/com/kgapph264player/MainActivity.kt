package com.kgapph264player

import android.app.Activity
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val TAG = "H264SocketPlayer"
        private const val PORT = 40001
    }

    private lateinit var textureView: TextureView
    private var codec: MediaCodec? = null
    private var videoWidth: Int = 1080
    private var videoHeight: Int = 2400
    private var decoderStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textureView = TextureView(this)
        setContentView(textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                // TextureView 准备好
                startServer()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
        }
    }

    private fun applyTextureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        val scaleX = viewWidth.toFloat() / videoWidth
        val scaleY = viewHeight.toFloat() / videoHeight
        val scale = Math.min(scaleX, scaleY)
        val dx = (viewWidth - videoWidth * scale) / 2f
        val dy = (viewHeight - videoHeight * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        textureView.setTransform(matrix)
    }

    private fun startServer() {
        Executors.newSingleThreadExecutor().execute {
            try {
                val server = ServerSocket(PORT)
                Log.i(TAG, "Waiting on port $PORT")
                val socket = server.accept()
                Log.i(TAG, "Client connected")

                val input = BufferedInputStream(socket.getInputStream())
                val buffer = ByteArray(200 * 1024)
                val streamBuffer = ByteArray(500 * 1024)
                var streamLen = 0
                var ptsUs = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break

                    System.arraycopy(buffer, 0, streamBuffer, streamLen, read)
                    streamLen += read

                    var offset = 0
                    while (offset + 4 < streamLen) {
                        // 找 start code
                        if (streamBuffer[offset] == 0.toByte()
                            && streamBuffer[offset + 1] == 0.toByte()
                            && streamBuffer[offset + 2] == 1.toByte()
                        ) {
                            var next = offset + 3
                            var foundNext = false
                            while (next + 3 < streamLen) {
                                if (streamBuffer[next] == 0.toByte()
                                    && streamBuffer[next + 1] == 0.toByte()
                                    && streamBuffer[next + 2] == 1.toByte()
                                ) {
                                    val naluType = streamBuffer[offset + 3].toInt() and 0x1F
                                    // 7 = SPS
                                    if (naluType == 7 && !decoderStarted) {
                                        val sps = ByteArray(next - offset)
                                        System.arraycopy(streamBuffer, offset + 3, sps, 0, next - offset - 3)
                                        parseSPS(sps)
                                        runOnUiThread { applyTextureTransform(textureView.width, textureView.height) }
                                        initDecoder()
                                        decoderStarted = true
                                    }

                                    processNALU(streamBuffer, offset, next - offset, ptsUs++)
                                    offset = next
                                    foundNext = true
                                    break
                                }
                                next++
                            }
                            if (!foundNext) break
                        } else {
                            offset++
                        }
                    }

                    // 剩余尾部
                    if (offset > 0 && offset < streamLen) {
                        System.arraycopy(streamBuffer, offset, streamBuffer, 0, streamLen - offset)
                        streamLen -= offset
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in server", e)
            }
        }
    }

    private fun initDecoder() {
        val format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight)
        codec = MediaCodec.createDecoderByType("video/avc")
        codec?.configure(format, android.view.Surface(textureView.surfaceTexture), null, 0)
        codec?.start()
    }

    private fun processNALU(data: ByteArray, offset: Int, len: Int, pts: Long) {
        val inputBufferId = codec?.dequeueInputBuffer(10_000L) ?: return
        if (inputBufferId >= 0) {
            val buffer = codec!!.getInputBuffer(inputBufferId)!!
            buffer.clear()
            buffer.put(data, offset, len)
            codec!!.queueInputBuffer(inputBufferId, 0, len, pts * 1000, 0)
        }

        val info = MediaCodec.BufferInfo()
        var outIndex = codec!!.dequeueOutputBuffer(info, 10_000L)
        while (outIndex >= 0) {
            codec!!.releaseOutputBuffer(outIndex, true)
            outIndex = codec!!.dequeueOutputBuffer(info, 0)
        }
    }

    private fun parseSPS(sps: ByteArray) {
        // 解析 SPS 获取真实宽高（只支持 baseline/main/progressive）
        // 这里只解析 pic_width_in_mbs_minus1 & pic_height_in_map_units_minus1 简单处理
        if (sps.size < 4) return
        var width = 0
        var height = 0

        try {
            val profileIdc = sps[0].toInt() and 0xFF
            val constraintSet = sps[1].toInt() and 0xFF
            val levelIdc = sps[2].toInt() and 0xFF
            var offset = 3

            // 先跳过 SPS 的 Exp-Golomb 头部
            val bits = BitReader(sps, offset)
            bits.readUE() // seq_parameter_set_id
            val picOrderCntType = bits.readUE()
            if (picOrderCntType == 0) bits.readUE()
            else if (picOrderCntType == 1) bits.readBits(1 + 1) // simplified
            bits.readUE() // log2_max_frame_num_minus4
            bits.readUE() // log2_max_pic_order_cnt_lsb_minus4

            val picWidthInMbsMinus1 = bits.readUE()
            val picHeightInMapUnitsMinus1 = bits.readUE()
            val frameMbsOnlyFlag = bits.readBit()

            width = (picWidthInMbsMinus1 + 1) * 16
            height = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

        } catch (e: Exception) {
            Log.e(TAG, "SPS parse error", e)
        }

        videoWidth = width
        videoHeight = height
        Log.i(TAG, "Parsed SPS: width=$videoWidth, height=$videoHeight")
    }

    override fun onDestroy() {
        super.onDestroy()
        codec?.stop()
        codec?.release()
        codec = null
    }
}

// 简单 BitReader，用于解析 SPS
class BitReader(private val data: ByteArray, startByte: Int = 0) {
    private var byteOffset = startByte
    private var bitOffset = 0

    fun readBit(): Int {
        val bit = (data[byteOffset].toInt() shr (7 - bitOffset)) and 1
        bitOffset++
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        return bit
    }

    fun readBits(count: Int): Int {
        var result = 0
        for (i in 0 until count) result = (result shl 1) or readBit()
        return result
    }

    fun readUE(): Int {
        var zeros = 0
        while (readBit() == 0) zeros++
        var value = 1
        for (i in 0 until zeros) value = (value shl 1) or readBit()
        return value - 1
    }
}