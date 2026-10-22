package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test fun cleanRemovesPunctuationWhitespaceAndEmoji() {
        assertEquals("mom2", Fuzzy.clean("  MOM #2 ❤\n"))
        assertEquals("youtube", Fuzzy.clean("You-Tube!"))
        assertEquals("", Fuzzy.clean("  !? ❤"))
    }

    @Test fun spokenSpacingAndMixedCaseMatchExactly() {
        assertEquals(1.0, Fuzzy.score("you tube", "YouTube"), 0.0)
        assertEquals(1.0, Fuzzy.score("mOm", "Mom ❤"), 0.0)
    }

    @Test fun emptyOrPunctuationOnlyNamesDoNotMatch() {
        for ((query, name) in listOf("" to "", "" to "Maps", "Maps" to "", "?!" to "❤")) {
            assertEquals("$query / $name", 0.0, Fuzzy.score(query, name), 0.0)
        }
    }

    @Test fun exactNameRanksAboveItsLongerVariant() {
        assertTrue(Fuzzy.score("WhatsApp", "WhatsApp") > Fuzzy.score("WhatsApp", "WhatsApp Business"))
        assertEquals("WhatsApp", Fuzzy.best("whats app", listOf("WhatsApp Business", "WhatsApp"), { it }))
    }

    @Test fun aLongerSpokenNameCanMatchItsPrefix() {
        assertTrue(Fuzzy.score("Google Maps", "Google") > 0.6)
        assertEquals(Fuzzy.score("Google Maps", "Google"), Fuzzy.score("Google", "Google Maps"), 0.0)
    }

    @Test fun containedNameScoresAboveAnUnrelatedApp() {
        assertTrue(Fuzzy.score("gram", "Instagram") > Fuzzy.score("gram", "YouTube"))
    }

    @Test fun smallSpeechErrorsStillFindTheRightApp() {
        for (query in listOf("spotfy", "spottify", "spotifi")) {
            assertEquals(query, "Spotify", Fuzzy.best(query, listOf("Camera", "Spotify", "Maps"), { it }))
        }
    }

    @Test fun entirelyDifferentNamesHaveZeroScore() {
        assertEquals(0.0, Fuzzy.score("abc", "xyz"), 0.0)
    }

    @Test fun noCandidatesOrNoGoodMatchReturnsNull() {
        assertNull(Fuzzy.best("Maps", emptyList<String>(), { it }))
        assertNull(Fuzzy.best("zzzz", listOf("Maps", "Camera"), { it }))
        assertNull(Fuzzy.best("", listOf("Maps"), { it }))
    }

    @Test fun thresholdIncludesExactBoundaryAndRejectsLowerScores() {
        val score = Fuzzy.score("spotfy", "Spotify")
        assertEquals("Spotify", Fuzzy.best("spotfy", listOf("Spotify"), { it }, min = score))
        assertNull(Fuzzy.best("spotfy", listOf("Spotify"), { it }, min = score + 0.001))
    }

    @Test fun tiedNamesKeepTheFirstCandidate() {
        assertEquals("Mom ❤", Fuzzy.best("mom", listOf("Mom ❤", "MOM"), { it }))
    }

    @Test fun matchingReturnsOriginalObjectUsingItsDisplayName() {
        data class App(val id: String, val label: String)
        val target = App("video", "YouTube")
        assertEquals(target, Fuzzy.best("you tube", listOf(App("camera", "Camera"), target), { it.label }))
    }
}
