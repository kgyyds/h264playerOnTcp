package com.kgapph264player

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val TAG = "H264SocketPlayer"
        private const val PORT = 40001
    }

    private lateinit var surfaceView: SurfaceView
    private var codec: MediaCodec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startServer(holder)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) { }

            override fun surfaceDestroyed(holder: SurfaceHolder) { }
        })
    }

    private fun startServer(holder: SurfaceHolder) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val server = ServerSocket(PORT)
                Log.i(TAG, "Waiting on port $PORT")
                val socket = server.accept()
                Log.i(TAG, "Client connected")

                val input = BufferedInputStream(socket.getInputStream())

                // 解码器初始化
                initDecoder(holder)

                // 缓冲池
                val buffer = ByteArray(200 * 1024)
                val streamBuffer = ByteArray(500 * 1024)
                var streamLen = 0

                var ptsUs = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break

                    // 叠加到流缓存
                    System.arraycopy(buffer, 0, streamBuffer, streamLen, read)
                    streamLen += read

                    // 找 start code
                    var offset = 0
                    while (offset + 4 < streamLen) {
                        if (streamBuffer[offset] == 0.toByte()
                            && streamBuffer[offset + 1] == 0.toByte()
                            && streamBuffer[offset + 2] == 1.toByte()
                        ) {
                            // 找到 start code, 寻找下一个
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
                Log.e(TAG, "Error in server", e)
            }
        }
    }

    private fun initDecoder(holder: SurfaceHolder) {
        // MIME + 你期望的宽高
        val format = MediaFormat.createVideoFormat("video/avc", 1080, 2400)

        codec = MediaCodec.createDecoderByType("video/avc")
        codec?.configure(format, holder.surface, null, 0)
        codec?.start()
    }

    private fun processNALU(data: ByteArray, offset: Int, len: Int, pts: Long) {
        val inputBufferId = codec?.dequeueInputBuffer(10_000L) ?: return
        if (inputBufferId >= 0) {
            val buffer = codec!!.getInputBuffer(inputBufferId)!!
            buffer.clear()
            buffer.put(data, offset, len)
            codec!!.queueInputBuffer(
                inputBufferId,
                0,
                len,
                pts * 1000,
                0
            )
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