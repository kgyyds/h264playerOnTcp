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
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        private const val TAG = "H264SocketPlayer"
        private const val PORT = 40001
        private const val VIDEO_WIDTH = 1080
        private const val VIDEO_HEIGHT = 2400
        private const val MAX_IDLE_TIME = 3L
    }

    private lateinit var textureView: TextureView
    private var codec: MediaCodec? = null
    private var socket: java.net.Socket? = null
    private var lastReceivedTime = System.currentTimeMillis()
    private var tcpThread: Thread? = null
    private var isServerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textureView = TextureView(this)
        setContentView(textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                applyTextureTransform(width, height)
                startServer()
            }
            override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {}
        }
    }

    private fun applyTextureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        val videoAspect = VIDEO_WIDTH.toFloat() / VIDEO_HEIGHT.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        var scaleX = 1f
        var scaleY = 1f
        var dx = 0f
        var dy = 0f

        if (viewAspect > videoAspect) {
            scaleY = viewHeight.toFloat() / VIDEO_HEIGHT
            scaleX = scaleY
            dx = (viewWidth - VIDEO_WIDTH * scaleX) / 2f
        } else {
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
                        this.socket = socket
                        Log.i(TAG, "Client connected")

                        val input = BufferedInputStream(socket.getInputStream())

                        // 这里确保是全新解码器
                        releaseDecoder()
                        initDecoder()

                        val buffer = ByteArray(200 * 1024)
                        val streamBuffer = ByteArray(500 * 1024)
                        var streamLen = 0
                        var ptsUs = 0L

                        lastReceivedTime = System.currentTimeMillis()
                        monitorConnectionStatus(socket)

                        while (isServerRunning) {
                            val read = input.read(buffer)
                            if (read <= 0) break

                            lastReceivedTime = System.currentTimeMillis()

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

                            if (offset > 0 && offset < streamLen) {
                                System.arraycopy(streamBuffer, offset, streamBuffer, 0, streamLen - offset)
                                streamLen -= offset
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Connection error", e)
                    } finally {
                        // 一次连接结束 → 彻底清理
                        fullCleanup()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
        tcpThread?.start()
    }

    private fun monitorConnectionStatus(socket: java.net.Socket) {
        Thread {
            try {
                while (isServerRunning) {
                    if (System.currentTimeMillis() - lastReceivedTime > TimeUnit.SECONDS.toMillis(MAX_IDLE_TIME)) {
                        Log.e(TAG, "Idle timeout, disconnect")
                        try {
                            socket.close()
                        } catch (ignored: Exception) {
                        }
                        break
                    }
                    TimeUnit.SECONDS.sleep(1)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.start()
    }

    private fun releaseDecoder() {
        try {
            codec?.stop()
        } catch (ignored: Exception) {
        }
        try {
            codec?.release()
        } catch (ignored: Exception) {
        }
        codec = null
    }

    private fun initDecoder() {
        if (codec != null) return
        try {
            val format = MediaFormat.createVideoFormat("video/avc", VIDEO_WIDTH, VIDEO_HEIGHT)
            codec = MediaCodec.createDecoderByType("video/avc")
            codec?.configure(format, android.view.Surface(textureView.surfaceTexture), null, 0)
            codec?.start()
            Log.i(TAG, "Decoder initialized (fresh new instance)")
        } catch (e: Exception) {
            Log.e(TAG, "initDecoder failed", e)
        }
    }

    private fun processNALU(data: ByteArray, offset: Int, len: Int, pts: Long) {
        val codec = codec ?: return
        try {
            val inputBufferId = codec.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val buffer = codec.getInputBuffer(inputBufferId)!!
                buffer.clear()
                buffer.put(data, offset, len)
                codec.queueInputBuffer(inputBufferId, 0, len, pts * 1000, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true)
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "processNALU error", e)
        }
    }

    private fun fullCleanup() {
        Log.i(TAG, "Doing FULL cleanup")

        // 关闭 socket
        try {
            socket?.close()
        } catch (ignored: Exception) {
        }
        socket = null

        // 彻底释放解码器（保证下一次是全新实例）
        releaseDecoder()

        lastReceivedTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        fullCleanup()
        try {
            tcpThread?.interrupt()
        } catch (ignored: Exception) {
        }
    }
}
