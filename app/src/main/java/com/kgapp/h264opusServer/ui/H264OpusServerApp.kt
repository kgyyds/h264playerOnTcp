package com.kgapp.h264opusServer.ui

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kgapp.h264opusServer.PlayerController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H264OpusServerApp(controller: PlayerController) {
    var showSettings by remember { mutableStateOf(false) }
    var videoPortText by rememberSaveable { mutableStateOf("27183") }
    var audioPortText by rememberSaveable { mutableStateOf("27184") }

    val parsedVideoPort = videoPortText.toIntOrNull() ?: 27183
    val parsedAudioPort = audioPortText.toIntOrNull() ?: 27184
    val videoSize by controller.videoSize.collectAsState()

    LaunchedEffect(parsedVideoPort, parsedAudioPort) {
        controller.start(parsedVideoPort, parsedAudioPort)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("H264 Opus TCP Player") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            VideoSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                videoWidth = videoSize.first,
                videoHeight = videoSize.second,
                controller = controller
            )
        }

        if (showSettings) {
            SettingsDialog(
                videoPortText = videoPortText,
                audioPortText = audioPortText,
                onVideoPortChanged = { videoPortText = it },
                onAudioPortChanged = { audioPortText = it },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun VideoSurface(
    modifier: Modifier,
    videoWidth: Int,
    videoHeight: Int,
    controller: PlayerController
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            AspectFitSurfaceView(context).apply {
                setVideoAspect(videoWidth, videoHeight)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d("VideoSurface", "surfaceCreated")
                        controller.attachSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d("VideoSurface", "surfaceDestroyed")
                        controller.detachSurface()
                    }
                })
            }
        },
        update = { view ->
            view.setVideoAspect(videoWidth, videoHeight)
        }
    )
}

@Composable
private fun SettingsDialog(
    videoPortText: String,
    audioPortText: String,
    onVideoPortChanged: (String) -> Unit,
    onAudioPortChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Box {
                Column {
                    OutlinedTextField(
                        value = videoPortText,
                        onValueChange = onVideoPortChanged,
                        label = { Text("Video Port") }
                    )
                    OutlinedTextField(
                        value = audioPortText,
                        onValueChange = onAudioPortChanged,
                        label = { Text("Audio Port") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
