package com.alrasin.jarvis.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCueTest {
    @Test fun slowReplyGetsOnlyOneCueAfterSixSeconds() {
        val cue = ThinkingCue()
        cue.start(1, 100)
        assertFalse(cue.take(1_600, 1, true))
        assertFalse(cue.take(6_099, 1, true))
        assertTrue(cue.take(6_100, 1, true))
        assertFalse(cue.take(12_100, 1, true))
    }

    @Test fun completedReplyNeverChimes() {
        val cue = ThinkingCue()
        cue.start(1, 0)
        cue.finish(1)
        assertFalse(cue.take(7_000, 1, true))
    }

    @Test fun interruptedOrSpeakingReplyNeverChimes() {
        val cue = ThinkingCue()
        cue.start(1, 0)
        assertFalse(cue.take(7_000, 2, true))
        cue.start(2, 0)
        assertFalse(cue.take(7_000, 2, false))
        assertFalse(cue.take(8_000, 2, true))
    }

    @Test fun oldReplyCannotCancelNewReplyCue() {
        val cue = ThinkingCue()
        cue.start(1, 0)
        cue.start(2, 5_000)
        cue.finish(1)
        assertFalse(cue.take(6_000, 2, true))
        assertTrue(cue.take(11_000, 2, true))
    }
}
