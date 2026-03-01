package com.kgapp.h264opusServer

import android.util.Log
import android.view.Surface
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

class PlayerController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tcpServer: TcpStreamServer? = null
    private var currentSurface: Surface? = null

    private val videoDecoder = H264VideoDecoder(
        onVideoSizeChanged = { width, height ->
            _videoSize.value = width to height
        }
    )
    private val audioDecoder = OpusAudioDecoder()

    private val _videoSize = MutableStateFlow(16 to 9)
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    fun start(videoPort: Int, audioPort: Int) {
        Log.d(TAG, "start called videoPort=$videoPort audioPort=$audioPort")
        stopServerOnly()

        scope.launch {
            audioDecoder.start()
            currentSurface?.let { videoDecoder.setSurface(it) }
            tcpServer = TcpStreamServer(
                videoPort = videoPort,
                audioPort = audioPort,
                onVideoBytes = { bytes, size -> videoDecoder.queueVideo(bytes, size) },
                onAudioBytes = { bytes, size -> audioDecoder.queueAudio(bytes, size) }
            ).also { it.start(scope) }
        }
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

    companion object {
        private const val TAG = "PlayerController"
    }
}
