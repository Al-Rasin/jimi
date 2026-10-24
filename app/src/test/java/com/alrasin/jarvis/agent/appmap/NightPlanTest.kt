package com.alrasin.jarvis.agent.appmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Night learning picks (ROADMAP §J.5). */
class NightPlanTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    @Test fun onlyAtNight() {
        assertTrue(NightPlan.nightHour(1))
        assertTrue(NightPlan.nightHour(4))
        assertFalse(NightPlan.nightHour(0))
        assertFalse(NightPlan.nightHour(5))
        assertFalse(NightPlan.nightHour(14))
    }

    @Test fun leastKnownFirstAndNotTwiceAWeek() {
        val candidates = listOf("a" to 9, "b" to 0, "c" to 2, "d" to 0)
        val explored = mapOf("b" to now - day, "c" to now - 8 * day)
        assertEquals(listOf("d", "c"), NightPlan.pick(candidates, explored, now))
    }

    @Test fun roundTripAndDropOld() {
        val text = NightPlan.format(mapOf("a" to now - day, "old" to now - 30 * day), now)
        assertEquals(mapOf("a" to now - day), NightPlan.parse(text))
        assertEquals(emptyMap<String, Long>(), NightPlan.parse(""))
        assertEquals(mapOf("x" to 5L), NightPlan.parse("x=5;broken;=3;y=z"))
    }
}
