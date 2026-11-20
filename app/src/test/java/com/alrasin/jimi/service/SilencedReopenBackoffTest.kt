package com.alrasin.jimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilencedReopenBackoffTest {
    /** Times (ms after silence starts) at which a reopen happens, reading every 250 ms. */
    private fun reopenTimes(b: SilencedReopenBackoff, untilMs: Long): List<Long> =
        (0..untilMs step 250).filter { b.shouldReopen(it) }

    @Test fun waitsGrowThenCapAt30s() {
        val b = SilencedReopenBackoff()
        // gaps 3, 6, 12, 24, 30, 30 s
        assertEquals(listOf(3_000L, 9_000L, 21_000L, 45_000L, 75_000L, 105_000L), reopenTimes(b, 105_000))
        assertEquals(30_000L, b.waitMs())
    }

    @Test fun tenMinutesOfSilenceReopensRarely() {
        val b = SilencedReopenBackoff()
        assertTrue(reopenTimes(b, 600_000).size <= 23)   // was 200 at a fixed 3 s
    }

    @Test fun audioBackResetsToFirstWait() {
        val b = SilencedReopenBackoff()
        reopenTimes(b, 60_000)
        assertTrue(b.waiting)
        b.recovered()
        assertFalse(b.waiting)
        assertEquals(3_000L, b.waitMs())
        assertFalse(b.shouldReopen(100_000))
        assertFalse(b.shouldReopen(102_999))
        assertTrue(b.shouldReopen(103_000))
    }
}
