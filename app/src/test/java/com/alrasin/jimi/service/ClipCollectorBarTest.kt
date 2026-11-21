package com.alrasin.jimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipCollectorBarTest {
    @Test fun lowSliderWakesAtOnceOnlyFromTheSureScoreWhenTheNameCanBeChecked() {
        // D-099: at 0.66, TV talk scored 0.66–0.81; it must say the name before waking.
        assertEquals(0.88f, ClipCollector.instantBar(0.66f, canCheck = true), 0f)
        assertEquals(0.66f, ClipCollector.checkBar(0.66f), 0f)
    }

    @Test fun normalOrHighSliderIsUnchanged() {
        assertEquals(0.88f, ClipCollector.instantBar(0.88f, canCheck = true), 0f)
        assertEquals(0.93f, ClipCollector.instantBar(0.93f, canCheck = true), 0f)
        assertEquals(0.7f, ClipCollector.checkBar(0.88f), 0f)
    }

    @Test fun withoutSpeechToTextTheSliderAloneDecides() {
        assertEquals(0.66f, ClipCollector.instantBar(0.66f, canCheck = false), 0f)
    }

    @Test fun doubtedHighScoreGoesToTheNameCheck() {
        // D-100: the verifier blocks an instant wake only when the name can still be checked.
        assertTrue(ClipCollector.wakesNow(0.95f, 0.88f, verified = true, canCheck = true))
        assertFalse(ClipCollector.wakesNow(0.95f, 0.88f, verified = false, canCheck = true))
        assertTrue(ClipCollector.wakesNow(0.95f, 0.88f, verified = false, canCheck = false))
        assertFalse(ClipCollector.wakesNow(0.80f, 0.88f, verified = true, canCheck = true))
    }
}
