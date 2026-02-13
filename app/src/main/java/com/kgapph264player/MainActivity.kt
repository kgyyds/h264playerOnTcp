package com.kgapph264player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.TextureView
import android.widget.EditText
import android.widget.LinearLayout
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
    private var videoWidth = 1080
    private var videoHeight = 2400

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textureView = TextureView(this)
        setContentView(textureView)

        // 弹窗输入宽高
        showInputDialog()
    }

    private fun showInputDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL

        val widthInput = EditText(this)
        widthInput.hint = "Video Width"
        widthInput.inputType = InputType.TYPE_CLASS_NUMBER
        val heightInput = EditText(this)
        heightInput.hint = "Video Height"
        heightInput.inputType = InputType.TYPE_CLASS_NUMBER

        layout.addView(widthInput)
        layout.addView(heightInput)

        AlertDialog.Builder(this)
            .setTitle("Enter Video Resolution")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                try {
                    videoWidth = widthInput.text.toString().toInt()
                    videoHeight = heightInput.text.toString().toInt()
                } catch (e: Exception) {
                    videoWidth = 1080
                    videoHeight = 2400
                }
                // 设置显示矩阵并开始推流监听
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
            .show()
    }

    private fun applyTextureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()

        // 计算缩放比例，保持视频宽高比
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
                initDecoder()

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