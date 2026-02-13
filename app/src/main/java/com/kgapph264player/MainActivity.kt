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
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        private const val TAG = "H264SocketPlayer"
        private const val PORT = 40001
        private const val VIDEO_WIDTH = 1080   // screenrecord 默认横屏宽
        private const val VIDEO_HEIGHT = 2400  // screenrecord 默认竖屏高
        private const val MAX_IDLE_TIME = 3L  // 最大空闲时间（秒）
    }

    private lateinit var textureView: TextureView
    private var codec: MediaCodec? = null
    private var socket: java.net.Socket? = null
    private var lastReceivedTime = System.currentTimeMillis() // 上次接收到数据的时间
    private var tcpThread: Thread? = null
    private var isServerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textureView = TextureView(this)
        setContentView(textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                // 设置显示矩阵，保持比例
                applyTextureTransform(width, height)
                // 启动监听推流
                startServer()
            }
            override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
        }
    }

    private fun applyTextureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()

        // 假设视频是竖屏，比例用推流的默认 1:1 参考即可
        val videoAspect = VIDEO_WIDTH.toFloat() / VIDEO_HEIGHT.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        var scaleX = 1f
        var scaleY = 1f
        var dx = 0f
        var dy = 0f

        if (viewAspect > videoAspect) {
            // 屏幕比视频宽 → 按高度撑满
            scaleY = viewHeight.toFloat() / VIDEO_HEIGHT
            scaleX = scaleY
            dx = (viewWidth - VIDEO_WIDTH * scaleX) / 2f
        } else {
            // 屏幕比视频窄 → 按宽度撑满
            scaleX = viewWidth.toFloat() / VIDEO_WIDTH
            scaleY = scaleX
            dy = (viewHeight - VIDEO_HEIGHT * scaleY) / 2f
        }

        matrix.setScale(scaleX, scaleY)
        matrix.postTranslate(dx, dy)

        textureView.setTransform(matrix)
    }

    private fun startServer() {
        if (isServerRunning) return

        tcpThread = Thread {
            try {
                val server = ServerSocket(PORT)
                Log.i(TAG, "Waiting on port $PORT")
                isServerRunning = true

                while (isServerRunning) {
                    try {
                        val socket = server.accept()
                        Log.i(TAG, "Client connected")

                        val input = BufferedInputStream(socket.getInputStream())
                        initDecoder()

                        val buffer = ByteArray(200 * 1024)
                        val streamBuffer = ByteArray(500 * 1024)
                        var streamLen = 0
                        var ptsUs = 0L

                        // 监听连接状态
                        monitorConnectionStatus(socket)

                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break

                            lastReceivedTime = System.currentTimeMillis()  // 更新接收数据的时间

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
                        Log.e(TAG, "Error in connection", e)
                        cleanup()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server", e)
            }
        }
        tcpThread?.start()
    }

    private fun monitorConnectionStatus(socket: java.net.Socket) {
        val monitorThread = Thread {
            try {
                while (isServerRunning) {
                    if (System.currentTimeMillis() - lastReceivedTime > TimeUnit.SECONDS.toMillis(MAX_IDLE_TIME)) {
                        Log.e(TAG, "TCP connection idle for too long, disconnecting...")
                        cleanup()
                        break
                    }
                    TimeUnit.SECONDS.sleep(1)
                }
            } catch (e: InterruptedException) {
                Log.e(TAG, "Connection monitoring interrupted", e)
            }
        }
        monitorThread.start()
    }

    private fun initDecoder() {
        try {
            val format = MediaFormat.createVideoFormat("video/avc", VIDEO_WIDTH, VIDEO_HEIGHT)
            codec = MediaCodec.createDecoderByType("video/avc")
            codec?.configure(format, android.view.Surface(textureView.surfaceTexture), null, 0)
            codec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing decoder", e)
        }
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

    private fun cleanup() {
        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }

        codec?.stop()
        codec?.release()
        codec = null

        // 重启 TCP 监听线程
        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        cleanup()
        tcpThread?.interrupt()
    }
}