package com.alrasin.jarvis.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.alrasin.jarvis.actions.ReminderScheduler
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * After reboot: starts listening if it was on before, and sets reminder alarms again (alarms die on reboot).
 * Also the watchdog (PLAN 2.4): every ~15 min [ACTION_WATCHDOG] restarts the service if MIUI killed it.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var reminders: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                runBlocking { reminders.rescheduleAll() }
                if (runBlocking { settings.listeningEnabled.first() }) JarvisCoreService.start(context)
                schedule(context)
            }
            ACTION_WATCHDOG -> if (!JarvisCoreService.running.value && runBlocking { settings.listeningEnabled.first() }) {
                JarvisLog.i("watchdog: service was not running, restarting")
                runCatching { JarvisCoreService.start(context) }.onFailure { JarvisLog.e("watchdog restart failed", it) }
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.alrasin.jarvis.WATCHDOG"

        /** Inexact repeating alarm: cheap on battery, survives the app process dying. */
        fun schedule(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi,
            )
        }
    }
}
