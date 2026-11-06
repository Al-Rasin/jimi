package com.alrasin.jarvis.actions

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockDbTest {
    private val out = """
        Row: 0 _id=1, enabled=1, hour=5, minutes=15
        Row: 1 _id=2, enabled=0, hour=5, minutes=10
        Row: 2 _id=7, enabled=1, hour=23, minutes=30
    """.trimIndent()

    @Test fun keepsOnlyEnabledRows() {
        assertEquals(listOf(ClockDb.Row(1, 5, 15), ClockDb.Row(7, 23, 30)), ClockDb.parseEnabled(out))
    }

    @Test fun emptyOrNoRootOutputGivesNoRows() {
        assertEquals(emptyList<ClockDb.Row>(), ClockDb.parseEnabled(""))
        assertEquals(emptyList<ClockDb.Row>(), ClockDb.parseEnabled("No result found."))
    }

    @Test fun rowsAtMatchesHourAndMinute() {
        val rows = ClockDb.parseEnabled(out)
        assertEquals(listOf(ClockDb.Row(7, 23, 30)), rowsAt(rows, LocalTime.of(23, 30)))
        assertEquals(emptyList<ClockDb.Row>(), rowsAt(rows, LocalTime.of(5, 10)))
    }

    @Test
    fun countsOnlyClockAlarmClocks() {
        val out = """
            showIntent=PendingIntent{a481022: PendingIntentRecord{1e56ab3 com.xiaomi.calendar startService}}
            showIntent=PendingIntent{e18a8c2: PendingIntentRecord{bf726d3 com.android.deskclock startService}}
            showIntent=PendingIntent{3f29d4b: PendingIntentRecord{bf726d3 com.android.deskclock startService}}
        """.trimIndent()
        assertEquals(2, ClockDb.countOwned(out))
        assertEquals(0, ClockDb.countOwned(""))
    }
}
