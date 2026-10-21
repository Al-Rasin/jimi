package com.alrasin.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.R
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.ui.MainActivity

object Notifications {
    private const val CH_SERVICE = "service"
    private const val CH_WAKE = "wake"
    const val ID_SERVICE = 1
    private const val ID_WAKE_BASE = 1000
    private const val FEEDBACK_TIMEOUT_MS = 120_000L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Wake word check", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)   // the service plays its own chime
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
            .setContentText("Was that you calling? (score ${"%.2f".format(score)})")
            .setOnlyAlertOnce(true)   // updated with the heard text: no second pop-up
            .setContentIntent(openApp(context))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(FEEDBACK_TIMEOUT_MS)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "✓ Yes", answer(WakeEvent.VERDICT_YES, 1)).build())
            .addAction(Notification.Action.Builder(null, "✗ No", answer(WakeEvent.VERDICT_NO, 2)).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(wakeId(eventId), n)
    }

    fun cancelWakeCheck(context: Context, eventId: Long) =
        context.getSystemService(NotificationManager::class.java).cancel(wakeId(eventId))

    private fun wakeId(eventId: Long) = ID_WAKE_BASE + (eventId % 100_000).toInt()

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
}
