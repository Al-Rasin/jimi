package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.JarvisLog

/**
 * Root fallback for the MIUI Clock alarm table when Clock ignores DISMISS_ALARM.
 * MIUI's provider ignores `--where` on update (it changes every row), so rows are changed one by one
 * through `alarm/<id>`, then a TIME_SET broadcast makes Clock reschedule from the table.
 */
internal object ClockDb {
    private const val PKG = "com.android.deskclock"
    private const val URI = "content://$PKG/alarm"

    data class Row(val id: Long, val hour: Int, val minute: Int)

    suspend fun enabledRows(): List<Row> =
        parseEnabled(RootShell.run("content query --uri $URI --projection _id:enabled:hour:minutes", log = false).out)

    /** Switches off [rows] and lets Clock reschedule. Returns the rows that are still on afterwards. */
    suspend fun disable(rows: List<Row>): List<Row> {
        if (rows.isEmpty()) return emptyList()
        rows.forEach { RootShell.run("content update --uri $URI/${it.id} --bind enabled:i:0", log = false) }
        RootShell.run("am broadcast -a android.intent.action.TIME_SET -p $PKG", log = false)
        val ids = rows.map { it.id }.toSet()
        val left = enabledRows().filter { it.id in ids }
        JarvisLog.i("ClockDb: disabled ${rows.size - left.size}/${rows.size} alarm rows")
        return left
    }

    /**
     * Alarm-clock entries owned by Clock (alarms, pre-alerts, timers), or null without root.
     * AlarmManager.nextAlarmClock only shows the earliest entry of any app, and MIUI Calendar adds one every midnight.
     */
    suspend fun scheduledCount(): Int? {
        val res = RootShell.run("dumpsys alarm | grep 'showIntent=' || true", log = false)
        return if (res.ok) countOwned(res.out) else null
    }

    internal fun countOwned(showIntentLines: String): Int = showIntentLines.lines().count { " $PKG " in it }

    internal fun parseEnabled(out: String): List<Row> =
        Regex("_id=(\\d+), enabled=1, hour=(\\d+), minutes=(\\d+)").findAll(out).map {
            Row(it.groupValues[1].toLong(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }.toList()
}
