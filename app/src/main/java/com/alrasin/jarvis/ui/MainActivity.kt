package com.alrasin.jarvis.ui

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.doOnPreDraw
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    private val appVm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Compose only gets keyboard insets edge-to-edge; without it the ArcDial thinks the keyboard is open and hides.
        // always light (D-057): dark status / nav bar icons even when the phone is in dark mode
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        super.onCreate(savedInstanceState)
        intent.getStringExtra("test_dir")?.let { vm.scoreDir(it) }
        intent.getStringExtra("stt_dir")?.let {
            vm.speechTest(it, intent.getStringExtra("stt_model"), speak = !intent.hasExtra("no_speak"))
        }
        intent.getStringExtra("voice_bench")?.let { vm.voiceBench(it) }
        window.decorView.doOnPreDraw {
            window.decorView.post {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.RESUMED) { appVm.resumeListening() }
                }
            }
        }
        setContent {
            JimiTheme {
                AppShell(appVm, vm)
            }
        }
    }
}
