package com.alrasin.jimi.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alrasin.jimi.brain.Persona
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.data.Reminder
import com.alrasin.jimi.data.ReminderDao
import com.alrasin.jimi.service.JimiCoreService
import com.alrasin.jimi.service.Notifications
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Exact alarms for reminders (PLAN 6.2). Survives reboot via [rescheduleAll] from BootReceiver. */
@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context, private val dao: ReminderDao) {
    private val am: AlarmManager by lazy { context.getSystemService(AlarmManager::class.java) }

    fun schedule(r: Reminder) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMs, pending(r.id))
        } catch (e: SecurityException) {
            // exact alarm access removed: a few minutes late is better than never
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMs, pending(r.id))
        }
        JimiLog.i("reminder ${r.id} at ${Instant.ofEpochMilli(r.dueMs)}")
    }

    fun cancel(id: Long) = am.cancel(pending(id))

    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        dao.pending().forEach { r ->
            // missed while off: fire soon instead of never
            schedule(if (r.dueMs < now) r.copy(dueMs = now + 30_000) else r)
        }
    }

    private fun pending(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        fun next(r: Reminder, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
            val days = when (r.repeat) {
                "daily" -> 1L
                "weekly" -> 7L
                else -> null
            } ?: return null
            val at = Instant.ofEpochMilli(r.dueMs).atZone(zone).toLocalDateTime()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            var periods = (ChronoUnit.DAYS.between(at.toLocalDate(), today) / days).coerceAtLeast(1)
            var next = at.plusDays(periods * days).atZone(zone).toInstant().toEpochMilli()
            // Deliver one overdue reminder after reboot, then skip missed repeats.
            while (next <= nowMs) {
                periods++
                next = at.plusDays(periods * days).atZone(zone).toInstant().toEpochMilli()
            }
            return next
        }
    }
}

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var dao: ReminderDao
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = dao.get(id) ?: return@launch
                if (r.done) return@launch
                Notifications.showReminder(context, r)
                JimiCoreService.say(context, "${Persona.ADDRESS}, reminder: ${r.text}")
                val next = ReminderScheduler.next(r)
                if (next != null) {
                    val n = r.copy(dueMs = next)
                    dao.update(n)
                    scheduler.schedule(n)
                } else dao.update(r.copy(done = true))
            } finally {
                done.finish()
            }
        }
    }

    companion object { const val EXTRA_ID = "reminder_id" }
}
