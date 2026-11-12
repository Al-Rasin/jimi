package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class SongIdRuleTest {
    private val now = LocalDateTime.of(2026, 11, 12, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)

    @Test fun songId() {
        for (s in listOf("what song is this", "What's this song?", "which song is playing", "identify this song",
            "what is playing right now", "shazam this", "name that tune", "what music is this")) {
            assertEquals(s, ToolCall("identify_song"), m(s))
        }
    }

    @Test fun othersUntouched() {
        assertNotEquals("identify_song", m("play a song")?.tool)
        assertNotEquals("identify_song", m("next song")?.tool)
        assertNotEquals("identify_song", m("pause the music")?.tool)
        assertEquals("ask_screen", m("what is this")?.tool)
    }
}
