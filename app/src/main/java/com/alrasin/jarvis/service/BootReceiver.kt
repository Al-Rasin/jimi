package com.alrasin.jarvis.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.alrasin.jarvis.actions.ReminderScheduler
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores reminders after reboot. Android 14+ requires a visible activity to resume microphone
 * access; older devices retain their supported boot/watchdog startup with failure handling.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var reminders: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        reminders.rescheduleAll()
                        if (settings.setupComplete.first() && settings.listeningEnabled.first()) {
                            resume(context)
                        }
                        schedule(context)
                    }
                    ACTION_WATCHDOG -> if (!CoreState.running.value &&
                        settings.setupComplete.first() && settings.listeningEnabled.first()) {
                        JarvisLog.i("watchdog: microphone service was not running")
                        resume(context)
                    }
                }
            } catch (failure: Exception) {
                JarvisLog.e("background resume failed", failure)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.alrasin.jarvis.WATCHDOG"

        private fun resume(context: Context) {
            if (Build.VERSION.SDK_INT >= 34) Notifications.showResumeListening(context)
            else JarvisCoreService.start(context)
        }

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
