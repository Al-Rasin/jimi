package com.alrasin.jimi.actions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerControlTest {
    @After fun reset() = TimerControl.cleared()

    @Test fun secondsLeftRoundsUpAndEnds() {
        TimerControl.started(10_000)
        assertEquals(10, TimerControl.secondsLeft(0))
        assertEquals(1, TimerControl.secondsLeft(9_500))
        assertNull(TimerControl.secondsLeft(10_000))
    }

    @Test fun clearedForgetsTimer() {
        TimerControl.started(10_000)
        TimerControl.cleared()
        assertNull(TimerControl.secondsLeft(0))
        assertNull(TimerControl.pausedSeconds())
    }

    @Test fun findsPauseAndResumeButtons() {
        assertEquals(0, timerButtonIndex(listOf("Pause", "+1:00", "Reset"), pause = true))
        assertEquals(1, timerButtonIndex(listOf("Reset", "Resume"), pause = false))
        assertEquals(-1, timerButtonIndex(listOf("Dismiss", "Snooze"), pause = true))
        assertEquals(-1, timerButtonIndex(listOf("Pause"), pause = false))
        assertEquals(-1, timerButtonIndex(listOf(null), pause = true))
    }
}
