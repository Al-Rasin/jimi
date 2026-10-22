package com.alrasin.jarvis.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { appVm.resumeListening() }
        }
        intent.getStringExtra("test_dir")?.let { vm.scoreDir(it) }
        intent.getStringExtra("stt_dir")?.let {
            vm.speechTest(it, intent.getStringExtra("stt_model"), speak = !intent.hasExtra("no_speak"))
        }
        intent.getStringExtra("voice_bench")?.let { vm.voiceBench(it) }
        setContent {
            JimiTheme {
                AppShell(appVm, vm)
            }
        }
    }
}
