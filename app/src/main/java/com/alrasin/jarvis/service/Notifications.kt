package com.alrasin.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.R
import com.alrasin.jarvis.data.Reminder
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.ui.AssistantActivity
import com.alrasin.jarvis.ui.MainActivity

object Notifications {
    private const val CH_SERVICE = "service"
    private const val CH_WAKE_OLD = "wake"          // was a heads-up pop-up; user found it too fast and noisy
    private const val CH_WAKE = "wake_quiet"
    private const val CH_REMINDER = "reminder"
    private const val CH_REPLY = "reply"
    private const val ID_REPLY = 2
    private const val ID_REMINDER_BASE = 500
    const val ID_SERVICE = 1
    private const val ID_WAKE_BASE = 1000
    private const val FEEDBACK_TIMEOUT_MS = 30 * 60_000L   // stays in the shade, answer any time

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
        nm.deleteNotificationChannel(CH_WAKE_OLD)
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Wake word check", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REPLY, "Answers", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun listening(context: Context, text: String): Notification {
        val missed = PendingIntent.getService(
            context, 1,
            Intent(context, JarvisCoreService::class.java).setAction(JarvisCoreService.ACTION_MISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 2,
            Intent(context, JarvisCoreService::class.java).setAction(JarvisCoreService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis is listening")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Missed it", missed).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** "Was that you?" prompt after a voice wake (D-023). */
    fun showWakeCheck(context: Context, eventId: Long, score: Float, heard: String? = null) {
        fun answer(verdict: String, req: Int) = PendingIntent.getBroadcast(
            context, (eventId * 10 + req).toInt(),
            Intent(context, WakeFeedbackReceiver::class.java)
                .putExtra(WakeFeedbackReceiver.EXTRA_ID, eventId)
                .putExtra(WakeFeedbackReceiver.EXTRA_VERDICT, verdict),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CH_WAKE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(if (heard == null) "Hey Jimi?" else "You said: $heard")
            .setContentText("Did you call \"Hey Jimi\"? (score ${"%.2f".format(score)}) — not about the text")
            .setOnlyAlertOnce(true)   // updated with the heard text: no second pop-up
            .setContentIntent(openApp(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(FEEDBACK_TIMEOUT_MS)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "✓ I called", answer(WakeEvent.VERDICT_YES, 1)).build())
            .addAction(Notification.Action.Builder(null, "✗ I didn't call", answer(WakeEvent.VERDICT_NO, 2)).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(wakeId(eventId), n)
    }

    fun cancelWakeCheck(context: Context, eventId: Long) =
        context.getSystemService(NotificationManager::class.java).cancel(wakeId(eventId))

    /** Last heard text + answer, quiet, so it can be read later from the lock screen. */
    fun showReply(context: Context, heard: String, reply: String) {
        val n = Notification.Builder(context, CH_REPLY)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(heard.ifBlank { "Jimi" })
            .setContentText(reply)
            .setStyle(Notification.BigTextStyle().bigText(reply))
            .setContentIntent(openPanel(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REPLY, n)
    }

    fun showReminder(context: Context, r: Reminder) {
        val n = Notification.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Reminder")
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setContentIntent(openApp(context))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REMINDER_BASE + (r.id % 10_000).toInt(), n)
    }

    private fun openPanel(context: Context) = PendingIntent.getActivity(
        context, 3, Intent(context, AssistantActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun wakeId(eventId: Long) = ID_WAKE_BASE + (eventId % 100_000).toInt()

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}
