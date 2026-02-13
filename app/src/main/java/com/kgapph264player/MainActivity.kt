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

    // 视频真实宽高，SPS解析后更新
    private var videoWidth = 1080
    private var videoHeight = 2400
    private var spsParsed = false

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

    private fun applyTextureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()

        val scaleX = viewWidth.toFloat() / videoWidth
        val scaleY = viewHeight.toFloat() / videoHeight
        matrix.setScale(scaleX, scaleY)

        // 视频旋转90度时可调整
        if (videoWidth < videoHeight) {
            matrix.postRotate(90f, viewWidth / 2f, viewHeight / 2f)
        }

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
                                    // 判断是否SPS
                                    val nalType = streamBuffer[offset + 3].toInt() and 0x1F
                                    if (!spsParsed && nalType == 7) {
                                        parseSPS(streamBuffer, offset + 4, next - offset - 4)
                                        spsParsed = true
                                        runOnUiThread {
                                            applyTextureTransform(textureView.width, textureView.height)
                                        }
                                        // 初始化解码器
                                        initDecoder()
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

    private fun parseSPS(data: ByteArray, offset: Int, len: Int) {
        // 简化解析，仅获取宽高
        try {
            val sps = data.copyOfRange(offset, offset + len)
            val width = ((sps[6].toInt() and 0xFF) shl 8) or (sps[7].toInt() and 0xFF) // 粗略解析
            val height = ((sps[8].toInt() and 0xFF) shl 8) or (sps[9].toInt() and 0xFF)
            videoWidth = width
            videoHeight = height
            Log.i(TAG, "Parsed SPS: width=$videoWidth height=$videoHeight")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SPS", e)
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

    override fun onDestroy() {
        super.onDestroy()
        codec?.stop()
        codec?.release()
        codec = null
    }
}