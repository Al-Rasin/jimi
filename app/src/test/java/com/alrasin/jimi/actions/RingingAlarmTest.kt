package com.alrasin.jimi.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class RingingAlarmTest {
    @Test fun ringingAlarmButtons() {
        assertEquals(1, ringingActionIndex(listOf("Snooze", "Stop"), snooze = false))
        assertEquals(0, ringingActionIndex(listOf("Snooze", "Dismiss"), snooze = true))
        assertEquals(2, ringingActionIndex(listOf("7:00", "Snooze 10 min", "Turn off"), snooze = false))
    }

    @Test fun upcomingAlarmNoticeIsNeverPressed() {
        assertEquals(-1, ringingActionIndex(listOf("Dismiss"), snooze = false))
        assertEquals(-1, ringingActionIndex(listOf("Dismiss now"), snooze = false))
        assertEquals(-1, ringingActionIndex(emptyList(), snooze = true))
    }
}
