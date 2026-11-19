package com.alrasin.jimi.actions

import com.alrasin.jimi.actions.a11y.findAll
import com.alrasin.jimi.actions.a11y.label
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.notify.JimiNotificationListener

/**
 * An alarm that is ringing right now. "Stop the alarm" must only silence this one, never switch off the
 * user's other (repeating) alarms. Uses the Clock notification buttons, else the buttons on the alarm screen.
 */
object RingingAlarm {
    /** Presses Stop (or Snooze) on a ringing alarm; false when no alarm is ringing. */
    suspend fun press(snooze: Boolean): Boolean = pressNotification(snooze) || pressScreen(snooze)

    private fun pressNotification(snooze: Boolean): Boolean {
        val listener = JimiNotificationListener.instance ?: return false
        val all = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        for (sbn in all) {
            if (!sbn.packageName.contains("clock", ignoreCase = true)) continue
            val actions = sbn.notification.actions.orEmpty()
            val i = ringingActionIndex(actions.map { it.title?.toString() }, snooze)
            if (i < 0) continue
            val sent = runCatching { actions[i].actionIntent.send(); true }.getOrDefault(false)
            JimiLog.i("ringing alarm: notification button ${actions[i].title} sent=$sent")
            if (sent) return true
        }
        return false
    }

    private suspend fun pressScreen(snooze: Boolean): Boolean {
        val service = JimiAccessibilityService.instance ?: return false
        val root = service.agentRoot() ?: return false
        if (root.packageName?.toString()?.contains("clock", ignoreCase = true) != true) return false
        val labels = root.findAll { it.isVisibleToUser }.map { it.label() }
        val i = ringingActionIndex(labels, snooze)
        if (i < 0) return false
        JimiLog.i("ringing alarm: screen button ${labels[i]}")
        return service.clickText(labels[i])
    }
}

private val SNOOZE_WORDS = Regex("(?i)^\\s*snooze\\b.*")
private val STOP_WORDS = Regex("(?i)^\\s*(?:stop|dismiss|turn off|off)\\s*$")

/**
 * Index of the button to press, or -1. A ringing alarm shows both Snooze and Stop/Dismiss; an
 * "upcoming alarm" notice shows only Dismiss, so it is never touched.
 */
internal fun ringingActionIndex(titles: List<String?>, snooze: Boolean): Int {
    val snoozeAt = titles.indexOfFirst { it != null && SNOOZE_WORDS.matches(it) }
    val stopAt = titles.indexOfFirst { it != null && STOP_WORDS.matches(it) }
    if (snoozeAt < 0 || stopAt < 0) return -1
    return if (snooze) snoozeAt else stopAt
}
