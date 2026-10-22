package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAgentInterruptTest {
    @Test fun acceptsOnlyShortInterruptPhrases() {
        mapOf(
            "stop" to "stop", "cancel" to "cancel", "panic" to "panic",
            "Hey, Jimi, STOP!" to "stop", "jimi cancel" to "cancel",
            "Hey Jimmy panic." to "panic", "hey stop" to "stop",
            "  JIMMY   STOP  " to "stop",
        ).forEach { (text, expected) -> assertEquals(text, expected, ScreenAgentInterrupt.phrase(text)) }
    }

    @Test fun rejectsNegationSubstringsAndOtherCommands() {
        listOf("", "don't stop", "do not cancel", "stop music", "cancel alarm", "nonstop",
            "stopping", "panic attack", "hey jimi don't stop", "please don't stop",
            "stop stop", "jimi hey stop", "hey jimi stop the music", "stop123", "stop🙂",
        ).forEach { assertNull(it, ScreenAgentInterrupt.phrase(it)) }
    }

    @Test fun acceptsEndpointImmediately() {
        assertEquals("stop", ScreenAgentInterrupt().accept("hey jimi stop", true, 10))
    }

    @Test fun waitsForStablePartial() {
        val listener = ScreenAgentInterrupt()
        assertNull(listener.accept("cancel", false, 100))
        assertNull(listener.accept("cancel", false, 339))
        assertEquals("cancel", listener.accept("cancel", false, 340))
    }

    @Test fun invalidContinuationDiscardsCandidate() {
        val listener = ScreenAgentInterrupt()
        assertNull(listener.accept("stop", false, 100))
        assertNull(listener.accept("stop music", false, 350))
        assertNull(listener.accept("stop", false, 400))
        assertEquals("stop", listener.accept("stop", false, 640))
    }

    @Test fun changedPhraseRestartsStabilityTimer() {
        val listener = ScreenAgentInterrupt()
        assertNull(listener.accept("stop", false, 100))
        assertNull(listener.accept("cancel", false, 350))
        assertNull(listener.accept("cancel", false, 589))
        assertEquals("cancel", listener.accept("cancel", false, 590))
    }

    @Test fun explicitResetDiscardsPartial() {
        val listener = ScreenAgentInterrupt()
        listener.accept("stop", false, 100)
        listener.reset()
        assertNull(listener.accept("stop", false, 400))
    }

    @Test fun onlyListensDuringAgentWork() {
        val listener = ScreenAgentInterrupt()
        assertFalse(listener.canListen(false, false, 0))
        assertTrue(listener.canListen(true, false, 0))
        listener.accept("stop", false, 0)
        assertFalse(listener.canListen(false, false, 200))
        assertTrue(listener.canListen(true, false, 300))
        assertNull(listener.accept("stop", false, 300))
    }

    @Test fun speechAndEchoTailDiscardPartials() {
        val listener = ScreenAgentInterrupt()
        listener.accept("stop", false, 0)
        assertFalse(listener.canListen(true, true, 200))
        assertFalse(listener.canListen(true, true, 500))
        assertFalse(listener.canListen(true, false, 600))
        assertFalse(listener.canListen(true, false, 899))
        assertTrue(listener.canListen(true, false, 900))
        assertNull(listener.accept("stop", false, 900))
        assertEquals("stop", listener.accept("stop", false, 1140))
    }
}
