package com.alrasin.jimi.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {
    @Test fun raisesWakeThresholdWithoutLoweringStrictSettings() {
        assertEquals(0.9f, BargeIn.wakeThreshold(0.5f), 0.0001f)
        assertEquals(0.93f, BargeIn.wakeThreshold(0.88f), 0.0001f)
        assertEquals(0.95f, BargeIn.wakeThreshold(0.9f), 0.0001f)
        assertEquals(0.99f, BargeIn.wakeThreshold(0.99f), 0.0001f)
        assertEquals(1f, BargeIn.wakeThreshold(Float.NaN), 0f)
    }

    @Test fun acceptsOnlyTwoWordInterruptionsWithoutEchoCanceler() {
        listOf("stop stop", " Jimi, WAIT! ", "\nhey jimi stop.\t", "“wait jimi”", "okay stop", "stop please", "wait stop").forEach {
            assertTrue(it, BargeIn.isInterruption(it))
        }
        listOf("", "...", "stop", "Wait.", "cancel", "stoplight", "waiting", "don't stop", "stop the timer", "st.op").forEach {
            assertFalse(it, BargeIn.isInterruption(it))
        }
    }

    @Test fun acceptsOneWordInterruptionsWithEchoCanceler() {
        listOf("stop", "Wait.", " Cancel! ", "enough", "shut up", "be quiet", "stop stop").forEach {
            assertTrue(it, BargeIn.isInterruption(it, echoCanceled = true))
        }
        listOf("", "...", "stoplight", "waiting", "don't stop", "stop the timer", "cancel the alarm").forEach {
            assertFalse(it, BargeIn.isInterruption(it, echoCanceled = true))
        }
    }

    @Test fun rejectsTriggerWordsSpokenByTheAssistant() {
        assertTrue(BargeIn.isPlaybackEcho("STOP!", "I can stop the timer."))
        assertTrue(BargeIn.isPlaybackEcho("wait", "Wait."))
        assertTrue(BargeIn.isPlaybackEcho("hey jimi", "Say ‘Hey, Jimi!’ to wake me."))
        assertTrue(BargeIn.isPlaybackEcho(" hey\nJIMI ", "Hey Jimi"))
    }

    @Test fun echoMatchingRequiresWholeWordsAndTheWholePhrase() {
        assertFalse(BargeIn.isPlaybackEcho("stop", "A stoplight ahead."))
        assertFalse(BargeIn.isPlaybackEcho("wait", "I am waiting."))
        assertFalse(BargeIn.isPlaybackEcho("hey jimi", "Hey there, Jimi."))
        assertFalse(BargeIn.isPlaybackEcho("hey jimi", "Hey Jiminy!"))
        assertFalse(BargeIn.isPlaybackEcho("...", "Stop."))
        assertFalse(BargeIn.isPlaybackEcho("stop", ""))
    }

    @Test fun stopMixedWithEchoStillInterrupts() {
        val playing = "Bangladeshi describes anything or anyone from Bangladesh, a South Asian nation."
        assertTrue(BargeIn.hasStopOverEcho("anything or anyone stop from bangladesh", playing, echoCanceled = true))
        assertTrue(BargeIn.hasStopOverEcho("from bangladesh shut up", playing, echoCanceled = true))
        assertTrue(BargeIn.hasStopOverEcho("jimi stop", playing, echoCanceled = false))
        assertFalse(BargeIn.hasStopOverEcho("anything or anyone from bangladesh a", playing, echoCanceled = true))
        assertFalse(BargeIn.hasStopOverEcho("anyone stop from bangladesh", playing, echoCanceled = false))
        assertFalse(BargeIn.hasStopOverEcho("you can stop the timer", "You can stop the timer now.", echoCanceled = true))
        assertFalse(BargeIn.hasStopOverEcho("a stoplight", playing, echoCanceled = true))
    }

    @Test fun loudPlaybackRequiresEchoCancellation() {
        assertTrue(BargeIn.canRecognizeStop(false, 0f))
        assertTrue(BargeIn.canRecognizeStop(false, 0.02f))
        assertFalse(BargeIn.canRecognizeStop(false, 0.0201f))
        assertFalse(BargeIn.canRecognizeStop(false, 1f))
        assertTrue(BargeIn.canRecognizeStop(true, 1f))
    }

    @Test fun unknownPlaybackEnergyDoesNotBypassTheEchoGate() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f).forEach {
            assertFalse(BargeIn.canRecognizeStop(false, it))
        }
    }

    @Test fun stripsOnlyALeadingInterruptionFromTheNextCommand() {
        assertEquals("turn on the light", BargeIn.stripInterruption(" Stop, turn on the light "))
        assertEquals("What time is it?", BargeIn.stripInterruption("WAIT! What time is it?"))
        assertEquals("", BargeIn.stripInterruption("wait"))
        assertEquals("what time is it", BargeIn.stripInterruption("Jimi stop, what time is it"))
        assertEquals("open youtube", BargeIn.stripInterruption("stop stop open youtube"))
        assertEquals("stoplight", BargeIn.stripInterruption("stoplight"))
        assertEquals("don't stop", BargeIn.stripInterruption("don't stop"))
        assertEquals("stop's meaning", BargeIn.stripInterruption("stop's meaning"))
    }
}
