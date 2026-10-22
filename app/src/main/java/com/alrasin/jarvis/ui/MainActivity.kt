package com.alrasin.jarvis.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    private val appVm: AppViewModel by viewModels()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT,
            ),
        )   // Settings → "Set up with root" grants the rest without dialogs
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
