package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherRealPhrasesTest {
    @Test fun repairsObservedFlashlightCommand() {
        val expected = ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on"))
        assertEquals(expected, RuleMatcher.match("on flushed light"))
        assertEquals(expected, RuleMatcher.match("Jimi, please on flushed light."))
    }

    @Test fun preservesPhraseInsideNoteAndDictation() {
        assertEquals(
            ToolCall("create_note", mapOf("text" to "on flushed light")),
            RuleMatcher.match("take a note on flushed light"),
        )
        assertEquals(
            ToolCall("type_text", mapOf("text" to "on flushed light")),
            RuleMatcher.match("type on flushed light"),
        )
    }

    @Test fun doesNotGuessMissingDirectionOrEmbeddedChat() {
        assertNull(RuleMatcher.match("n flash light"))
        assertNull(RuleMatcher.match("what does on flushed light mean"))
    }

    @Test fun observedChatFailuresDoNotMatchTools() {
        listOf("do you love me", "fun fact about the moon", "joke about cats").forEach {
            assertNull(it, RuleMatcher.match(it))
        }
    }
}
