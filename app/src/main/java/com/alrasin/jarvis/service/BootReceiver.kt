package com.alrasin.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.data.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Starts listening after reboot if it was on before. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: Settings

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (runBlocking { settings.listeningEnabled.first() }) JarvisCoreService.start(context)
    }
}
