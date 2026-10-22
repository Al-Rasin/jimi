package com.alrasin.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.actions.ReminderScheduler
import com.alrasin.jarvis.data.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** After reboot: starts listening if it was on before, and sets reminder alarms again (alarms die on reboot). */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var reminders: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runBlocking { reminders.rescheduleAll() }
        if (runBlocking { settings.listeningEnabled.first() }) JarvisCoreService.start(context)
    }
}
