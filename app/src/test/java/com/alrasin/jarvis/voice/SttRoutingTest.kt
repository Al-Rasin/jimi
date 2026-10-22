package com.alrasin.jarvis.voice

import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class SttRoutingTest {
    private val corrector = SttCorrector(
        contacts = listOf("Mohammed Rahman"),
        apps = listOf("Spotify"),
    )

    @Test fun correctsFlashlightBeforeRouting() {
        val raw = "down on flushlight"
        assertEquals(
            ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on")),
            RuleMatcher.match(corrector.correct(raw)),
        )
        assertEquals("down on flushlight", raw)
    }

    @Test fun correctsVolumeBeforeRouting() {
        assertEquals(
            ToolCall("set_volume", mapOf("level" to "0")),
            RuleMatcher.match(corrector.correct("sound of")),
        )
    }

    @Test fun routesCorrectedContactName() {
        assertEquals(
            ToolCall("call", mapOf("contact" to "mohammed rahman")),
            RuleMatcher.match(corrector.correct("call Muhammed Rahman")),
        )
    }

    @Test fun routesCorrectedAppLabel() {
        assertEquals(
            ToolCall("open_app", mapOf("app" to "spotify")),
            RuleMatcher.match(corrector.correct("open Spotifi")),
        )
    }

    @Test fun noteContentDoesNotGainEntityCorrections() {
        assertEquals(
            ToolCall("create_note", mapOf("text" to "muhammed likes spotifi")),
            RuleMatcher.match(corrector.correct("take a note Muhammed likes Spotifi")),
        )
    }
}
