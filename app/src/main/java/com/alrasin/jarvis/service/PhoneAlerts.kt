package com.alrasin.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.TelephonyManager
import com.alrasin.jarvis.actions.CallText
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.ContextNow
import com.alrasin.jarvis.data.NotificationLog
import com.alrasin.jarvis.notify.Proactive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Things Jimi says on its own from phone broadcasts: low battery, who is calling, late-night nudge, missed alerts. */
class PhoneAlerts(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listeningEnabled: suspend () -> Boolean,
    private val contactName: suspend (String) -> String,
    private val proactive: suspend () -> Boolean = { false },
    private val importantSince: suspend (Long) -> List<NotificationLog> = { emptyList() },
    private val announce: (String) -> Unit,
) {
    private var batteryWarnedAt = 100
    private var ringAnnounced = false
    @Volatile private var screenOffAt = 0L
    @Volatile private var nudgedAt = 0L

    fun register() {
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        context.registerReceiver(ringing, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        context.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) })
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(battery) }
        runCatching { context.unregisterReceiver(ringing) }
        runCatching { context.unregisterReceiver(screen) }
    }

    /** Unlock after a long break: missed alerts first, else the late-night nudge (ROADMAP #44). One line at most. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            if (intent.action == Intent.ACTION_SCREEN_OFF) { screenOffAt = now; return }
            val offAt = screenOffAt
            screenOffAt = 0L
            scope.launch {
                if (!listeningEnabled() || !proactive()) return@launch
                val quiet = ContextNow.mode.quiet
                val away = if (offAt == 0L) 0L else now - offAt
                val summary = if (away >= Proactive.AWAY_MS) Proactive.awaySummary(away, withContext(Dispatchers.IO) { importantSince(offAt) }, quiet) else null
                val cal = Calendar.getInstance()
                val line = summary ?: Proactive.lateNight(now, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), nudgedAt, quiet)
                    ?.also { nudgedAt = now }
                line?.let(announce)
            }
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged || level > 20) { batteryWarnedAt = 100; return }
            val step = if (level <= 5) 5 else 15
            if (level <= step && batteryWarnedAt > step) {
                batteryWarnedAt = step
                announce("${Persona.ADDRESS}, battery is at $level percent. Please charge me.")
            }
        }
    }

    /** Says who is calling (ROADMAP B §I.1). The number only comes with READ_CALL_LOG; Android sends the broadcast twice, so say it once per ring. */
    private val ringing = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) { ringAnnounced = false; return }
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
            if (ringAnnounced) return
            ringAnnounced = true
            scope.launch {
                if (!listeningEnabled()) return@launch   // Jimi is off: stay quiet
                val name = withContext(Dispatchers.IO) { if (number.isBlank()) "" else contactName(number) }
                announce(CallText.ringing(name))
            }
        }
    }
}
