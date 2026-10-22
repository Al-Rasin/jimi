package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SttPhoneMishearTest {
    private val corrector = SttCorrector()

    @Test fun correctsBatteryPercentageMishear() {
        assertEquals("james the battery percentage", corrector.correct("james the battery for centers"))
        assertEquals("check the battery percentage", corrector.correct("check the battery for centers"))
        assertEquals("battery percentage", corrector.correct("battery for centers"))
    }

    @Test fun correctsTurnVerbOnlyWithKnownTool() {
        assertEquals("turn on flashlight", corrector.correct("turned on flashlight"))
        assertEquals("turn on flashlight", corrector.correct("done on flashlight"))
        assertEquals("Please turn on the flashlight!", corrector.correct("Please done on the flushlight!"))
        assertEquals("done on homework", corrector.correct("done on homework"))
    }

    @Test fun preservesDictationAndSearches() {
        listOf(
            "search battery for centers",
            "search for done on flashlight",
            "note battery for centers",
            "text Alice saying battery for centers",
            "tell me why it turned on flashlight",
            "don't turn on flashlight",
        ).forEach { assertEquals(it, corrector.correct(it)) }
    }

    @Test fun correctionsRequireAvailableToolVocabulary() {
        val withoutTools = SttCorrector(toolWords = emptyList())
        assertEquals("battery for centers", withoutTools.correct("battery for centers"))
        assertEquals("done on flashlight", withoutTools.correct("done on flashlight"))
    }
}
