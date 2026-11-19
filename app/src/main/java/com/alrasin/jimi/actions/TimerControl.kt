package com.alrasin.jimi.actions

import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.notify.JimiNotificationListener

/**
 * The timer Jimi started: "how much time is left", "pause the timer", "resume the timer".
 * Clock has no public API to read or pause a timer, so Jimi remembers the end time it asked for and
 * presses the Pause/Resume button on Clock's timer notification.
 */
object TimerControl {
    @Volatile private var endAt: Long? = null
    @Volatile private var pausedLeftMs: Long? = null

    fun started(end: Long) {
        endAt = end
        pausedLeftMs = null
    }

    fun cleared() {
        endAt = null
        pausedLeftMs = null
    }

    /** Seconds left on Jimi's timer (rounded up), or null when Jimi knows of no running timer. */
    fun secondsLeft(now: Long = System.currentTimeMillis()): Int? =
        endAt?.let { it - now }?.takeIf { it > 0 }?.let { ((it + 999) / 1000).toInt() }

    fun pausedSeconds(): Int? = pausedLeftMs?.let { ((it + 999) / 1000).toInt() }

    /** Presses Pause (or Resume) on Clock's timer notification; false when there is none. */
    fun press(pause: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val listener = JimiNotificationListener.instance ?: return false
        val all = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        for (sbn in all) {
            if (!sbn.packageName.contains("clock", ignoreCase = true)) continue
            val actions = sbn.notification.actions.orEmpty()
            val i = timerButtonIndex(actions.map { it.title?.toString() }, pause)
            if (i < 0) continue
            val sent = runCatching { actions[i].actionIntent.send(); true }.getOrDefault(false)
            JimiLog.i("timer: notification button ${actions[i].title} sent=$sent")
            if (!sent) continue
            if (pause) {
                pausedLeftMs = endAt?.let { it - now }?.takeIf { it > 0 }
                endAt = null
            } else {
                pausedLeftMs?.let { endAt = now + it }
                pausedLeftMs = null
            }
            return true
        }
        return false
    }
}

private val PAUSE_WORDS = Regex("(?i)^\\s*pause\\s*$")
private val RESUME_WORDS = Regex("(?i)^\\s*(?:resume|continue|start)\\s*$")

/** Index of the Pause (or Resume) button on a timer notification, or -1. Alarms have no such button. */
internal fun timerButtonIndex(titles: List<String?>, pause: Boolean): Int {
    val words = if (pause) PAUSE_WORDS else RESUME_WORDS
    return titles.indexOfFirst { it != null && words.matches(it) }
}
