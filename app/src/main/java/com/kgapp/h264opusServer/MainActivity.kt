package com.kgapp.h264opusServer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.kgapp.h264opusServer.ui.H264OpusServerApp
import com.kgapp.h264opusServer.ui.theme.H264OpusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            H264OpusTheme {
                val controller = remember { PlayerController() }
                DisposableEffect(Unit) {
                    onDispose {
                        controller.release()
                    }
                }
                H264OpusServerApp(controller = controller)
            }
        }
    }
}
