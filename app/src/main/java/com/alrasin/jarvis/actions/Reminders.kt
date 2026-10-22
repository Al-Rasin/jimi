package com.alrasin.jarvis.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Reminder
import com.alrasin.jarvis.data.ReminderDao
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.service.Notifications
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Exact alarms for reminders (PLAN 6.2). Survives reboot via [rescheduleAll] from BootReceiver. */
@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context, private val dao: ReminderDao) {
    private val am = context.getSystemService(AlarmManager::class.java)

    fun schedule(r: Reminder) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.dueMs, pending(r.id))
        JarvisLog.i("reminder ${r.id} at ${Instant.ofEpochMilli(r.dueMs)}")
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
        fun next(r: Reminder): Long? {
            val z = ZoneId.systemDefault()
            val at = Instant.ofEpochMilli(r.dueMs).atZone(z)
            return when (r.repeat) {
                "daily" -> at.plusDays(1)
                "weekly" -> at.plusWeeks(1)
                else -> null
            }?.toInstant()?.toEpochMilli()
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
                JarvisCoreService.say(context, "Boss, reminder: ${r.text}")
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
