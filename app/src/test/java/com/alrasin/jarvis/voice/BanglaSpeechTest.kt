package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BanglaSpeechTest {
    @Test fun usedOnlyWhenOnReadyAndSensibleLength() {
        val second = SpeechToText.SAMPLE_RATE
        assertTrue(BanglaSpeech.use(enabled = true, ready = true, samples = 3 * second))
        assertFalse(BanglaSpeech.use(enabled = false, ready = true, samples = 3 * second))
        assertFalse(BanglaSpeech.use(enabled = true, ready = false, samples = 3 * second))
        assertFalse(BanglaSpeech.use(enabled = true, ready = true, samples = second / 10))
        assertFalse(BanglaSpeech.use(enabled = true, ready = true, samples = 40 * second))
    }

    @Test fun cleanDropsTagsAndMadeUpLines() {
        assertEquals("Call mom.", BanglaSpeech.clean(" [Music] Call mom. (laughs) "))
        assertNull(BanglaSpeech.clean("[BLANK_AUDIO]"))
        assertNull(BanglaSpeech.clean("♪♪"))
        assertNull(BanglaSpeech.clean("Thank you for watching!"))
        assertNull(BanglaSpeech.clean("you"))
        assertEquals("Thank you", BanglaSpeech.clean("Thank you"))
        assertEquals("Turn on the flashlight", BanglaSpeech.clean("Turn on the flashlight"))
    }

    @Test fun loopedOutputKeepsFirstCopy() {
        assertEquals("call mom", BanglaSpeech.repeatCut("call mom call mom call mom call"))
        assertEquals("open youtube now", BanglaSpeech.repeatCut("open youtube now"))
        assertEquals("no no", BanglaSpeech.repeatCut("no no"))
    }
}
