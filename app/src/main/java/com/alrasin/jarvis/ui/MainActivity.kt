package com.alrasin.jarvis.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        intent.getStringExtra("test_dir")?.let { vm.scoreDir(it) }
        intent.getStringExtra("stt_dir")?.let { vm.speechTest(it) }
        intent.getStringExtra("tts_dir")?.let { vm.voiceBench(it) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(vm)
            }
        }
    }
}
