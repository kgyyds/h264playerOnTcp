package com.kgapp.h264opusServer

import android.util.Log
import android.view.Surface
import com.kgapp.h264opusServer.control.AndroidKeyCode
import com.kgapp.h264opusServer.control.ControlSender
import com.kgapp.h264opusServer.control.DeviceFrameSize
import com.kgapp.h264opusServer.control.KeyAction
import com.kgapp.h264opusServer.control.encodeInjectKeycode
import com.kgapp.h264opusServer.control.encodeInjectTouch
import com.kgapp.h264opusServer.control.mapViewToVideo
import com.kgapp.h264opusServer.decoder.H264VideoDecoder
import com.kgapp.h264opusServer.decoder.OpusAudioDecoder
import com.kgapp.h264opusServer.network.TcpStreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PlayerController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tcpServer: TcpStreamServer? = null
    private var currentSurface: Surface? = null
    private val controlSender = ControlSender()
    private val hasVideoConnection = AtomicBoolean(false)
    private val hasControlConnection = AtomicBoolean(false)

    private val videoDecoder = H264VideoDecoder(
        onVideoSizeChanged = { width, height ->
            _videoSize.value = width to height
        }
    )
    private val audioDecoder = OpusAudioDecoder()

    private val _videoSize = MutableStateFlow(16 to 9)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    fun start(videoPort: Int, audioPort: Int, controlPort: Int) {
        Log.d(TAG, "start called videoPort=$videoPort audioPort=$audioPort controlPort=$controlPort")
        stopServerOnly()
        hasVideoConnection.set(false)
        hasControlConnection.set(false)
        controlSender.bindOutputStream(null)

        scope.launch {
            audioDecoder.start()
            currentSurface?.let { videoDecoder.setSurface(it) }
            tcpServer = TcpStreamServer(
                videoPort = videoPort,
                audioPort = audioPort,
                controlPort = controlPort,
                onVideoBytes = { bytes, size -> videoDecoder.queueVideo(bytes, size) },
                onAudioBytes = { bytes, size -> audioDecoder.queueAudio(bytes, size) },
                onVideoConnected = { connected -> hasVideoConnection.set(connected) },
                onControlOutput = { output -> onControlOutputChanged(output) },
            ).also { it.start(scope) }
        }
    }

    fun onRenderTouch(action: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int, pressure: Float) {
        val (videoWidth, videoHeight) = _videoSize.value
        val frame = DeviceFrameSize(videoWidth, videoHeight)
        val point = mapViewToVideo(
            vx = x,
            vy = y,
            viewW = viewWidth,
            viewH = viewHeight,
            videoW = videoWidth,
            videoH = videoHeight
        ) ?: return

        if (!canSendControl()) return

        controlSender.send(
            encodeInjectTouch(
                action = action,
                pointerId = 0L,
                p = point,
                frame = frame,
                pressure = pressure
            )
        )
    }

    fun sendKeyTap(keycode: Int) {
        if (!canSendControl()) return
        controlSender.send(encodeInjectKeycode(action = KeyAction.DOWN, keycode = keycode))
        controlSender.send(encodeInjectKeycode(action = KeyAction.UP, keycode = keycode))
    }

    fun sendPower() = sendKeyTap(AndroidKeyCode.POWER)

    fun sendVolumeUp() = sendKeyTap(AndroidKeyCode.VOLUME_UP)

    fun sendVolumeDown() = sendKeyTap(AndroidKeyCode.VOLUME_DOWN)

    fun sendBack() = sendKeyTap(AndroidKeyCode.BACK)

    fun sendHome() = sendKeyTap(AndroidKeyCode.HOME)

    fun sendAppSwitch() = sendKeyTap(AndroidKeyCode.APP_SWITCH)

    fun disconnectAllConnections() {
        Log.d(TAG, "disconnect all active tcp connections")
        tcpServer?.disconnectAllClients()
        hasVideoConnection.set(false)
        hasControlConnection.set(false)
        controlSender.bindOutputStream(null)
    }

    fun attachSurface(surface: Surface) {
        Log.d(TAG, "attachSurface")
        currentSurface = surface
        scope.launch { videoDecoder.setSurface(surface) }
    }

    fun detachSurface() {
        Log.d(TAG, "detachSurface")
        currentSurface = null
        scope.launch { videoDecoder.clearSurface() }
    }

    fun release() {
        Log.d(TAG, "release all resources")
        stopServerOnly()
        controlSender.close()
        scope.launch {
            videoDecoder.release()
            audioDecoder.release()
            scope.coroutineContext.cancel()
        }
    }

    private fun stopServerOnly() {
        tcpServer?.stop()
        tcpServer = null
    }

    private fun onControlOutputChanged(output: OutputStream?) {
        hasControlConnection.set(output != null)
        controlSender.bindOutputStream(output)
    }

    private fun canSendControl(): Boolean {
        return hasVideoConnection.get() && hasControlConnection.get()
    }

    companion object {
        private const val TAG = "PlayerController"
    }
}
