package com.kgapp.h264opusServer.ui

import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kgapp.h264opusServer.PlayerController
import com.kgapp.h264opusServer.control.MotionAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H264OpusServerApp(controller: PlayerController) {
    var showSettings by remember { mutableStateOf(false) }
    var videoPortText by rememberSaveable { mutableStateOf("27183") }
    var audioPortText by rememberSaveable { mutableStateOf("27184") }
    var controlPortText by rememberSaveable { mutableStateOf("27185") }

    val parsedVideoPort = videoPortText.toIntOrNull() ?: 27183
    val parsedAudioPort = audioPortText.toIntOrNull() ?: 27184
    val parsedControlPort = controlPortText.toIntOrNull() ?: 27185
    val videoSize by controller.videoSize.collectAsState()

    LaunchedEffect(parsedVideoPort, parsedAudioPort, parsedControlPort) {
        controller.start(parsedVideoPort, parsedAudioPort, parsedControlPort)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("H264 Opus TCP Player") },
                actions = {
                    IconButton(onClick = { controller.disconnectAllConnections() }) {
                        Icon(Icons.Default.LinkOff, contentDescription = "disconnect all")
                    }
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
                    .weight(1f)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                videoWidth = videoSize.first,
                videoHeight = videoSize.second,
                controller = controller
            )
            ControlButtons(
                modifier = Modifier.fillMaxWidth(),
                controller = controller
            )
        }

        if (showSettings) {
            SettingsDialog(
                videoPortText = videoPortText,
                audioPortText = audioPortText,
                controlPortText = controlPortText,
                onVideoPortChanged = { videoPortText = it },
                onAudioPortChanged = { audioPortText = it },
                onControlPortChanged = { controlPortText = it },
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
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> controller.onRenderTouch(
                            action = MotionAction.DOWN,
                            x = event.x,
                            y = event.y,
                            viewWidth = view.width,
                            viewHeight = view.height,
                            pressure = 1f
                        )

                        MotionEvent.ACTION_MOVE -> controller.onRenderTouch(
                            action = MotionAction.MOVE,
                            x = event.x,
                            y = event.y,
                            viewWidth = view.width,
                            viewHeight = view.height,
                            pressure = 1f
                        )

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> controller.onRenderTouch(
                            action = MotionAction.UP,
                            x = event.x,
                            y = event.y,
                            viewWidth = view.width,
                            viewHeight = view.height,
                            pressure = 0f
                        )
                    }
                    true
                }
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
private fun ControlButtons(modifier: Modifier, controller: PlayerController) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FilledTonalIconButton(onClick = { controller.sendPower() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Power")
        }
        FilledTonalIconButton(onClick = { controller.sendVolumeDown() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.VolumeDown, contentDescription = "Volume down")
        }
        FilledTonalIconButton(onClick = { controller.sendVolumeUp() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.VolumeUp, contentDescription = "Volume up")
        }
        FilledTonalIconButton(onClick = { controller.sendBack() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
        FilledTonalIconButton(onClick = { controller.sendHome() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Home, contentDescription = "Home")
        }
        FilledTonalIconButton(onClick = { controller.sendAppSwitch() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SwapHoriz, contentDescription = "App switch")
        }
    }
}

@Composable
private fun SettingsDialog(
    videoPortText: String,
    audioPortText: String,
    controlPortText: String,
    onVideoPortChanged: (String) -> Unit,
    onAudioPortChanged: (String) -> Unit,
    onControlPortChanged: (String) -> Unit,
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
                    OutlinedTextField(
                        value = controlPortText,
                        onValueChange = onControlPortChanged,
                        label = { Text("Control Port") }
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
