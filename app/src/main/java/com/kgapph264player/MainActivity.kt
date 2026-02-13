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
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val TAG = "H264SocketPlayer"
        private const val PORT = 40001
    }

    private lateinit var textureView: TextureView
    private var codec: MediaCodec? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var initialized = false
    private val streamBuffer = ByteArray(1024 * 1024)
    private var streamLen = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textureView = TextureView(this)
        setContentView(textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                startServer()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
        }
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
                var ptsUs = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break

                    System.arraycopy(buffer, 0, streamBuffer, streamLen.toInt(), read)
                    streamLen += read

                    var offset = 0
                    while (offset + 4 < streamLen) {
                        if (streamBuffer[offset] == 0.toByte() &&
                            streamBuffer[offset + 1] == 0.toByte() &&
                            streamBuffer[offset + 2] == 1.toByte()
                        ) {
                            var next = offset + 3
                            var foundNext = false
                            while (next + 3 < streamLen) {
                                if (streamBuffer[next] == 0.toByte() &&
                                    streamBuffer[next + 1] == 0.toByte() &&
                                    streamBuffer[next + 2] == 1.toByte()
                                ) {
                                    val naluLen = next - offset
                                    processNALU(streamBuffer, offset, naluLen, ptsUs++)
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
                        System.arraycopy(streamBuffer, offset, streamBuffer, 0, (streamLen - offset).toInt())
                        streamLen -= offset
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in server", e)
            }
        }
    }

    private fun processNALU(data: ByteArray, offset: Int, len: Int, pts: Long) {
        if (!initialized) {
            // SPS NALU 类型为 7
            if ((data[offset + 3].toInt() and 0x1F) == 7) {
                val spsData = data.copyOfRange(offset, offset + len)
                val widthHeight = parseSPS(spsData)
                if (widthHeight != null) {
                    videoWidth = widthHeight.first
                    videoHeight = widthHeight.second
                    runOnUiThread { applyTextureTransform() }
                    initDecoder()
                    initialized = true
                }
            }
            return
        }

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

    private fun initDecoder() {
        val format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight)
        codec = MediaCodec.createDecoderByType("video/avc")
        codec?.configure(format, android.view.Surface(textureView.surfaceTexture), null, 0)
        codec?.start()
    }

    private fun applyTextureTransform() {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
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

    // 简单解析 SPS 获取宽高（H.264 Annex B）
    private fun parseSPS(sps: ByteArray): Pair<Int, Int>? {
        try {
            val spsReader = SpsParser(sps)
            return Pair(spsReader.width, spsReader.height)
        } catch (e: Exception) {
            Log.e(TAG, "SPS parse error", e)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        codec?.stop()
        codec?.release()
        codec = null
    }
}