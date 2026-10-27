package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTextTest {
    private val pkg = Regex("\\b[a-z]+\\.[a-z]+")

    @Test fun fillerFollowUpsAreNoise() {
        listOf("okay", "Okay.", "hmm", "sounds", "I said he", "sounds good", "yeah okay").forEach {
            assertTrue(it, ReplyText.isNoise(it))
        }
    }

    @Test fun realFollowUpsAreNotNoise() {
        listOf("what", "mom", "turn on wifi", "open YouTube", "set alarm for 7", "what time is it").forEach {
            assertFalse(it, ReplyText.isNoise(it))
        }
    }

    @Test fun packageNamesBecomeLabelOrThatApp() {
        assertEquals("I can't open that app.", ReplyText.withoutPackages("I can't open com.miui.home."))
        assertEquals("Opened that app.", ReplyText.withoutPackages("Opened miui.home."))
        assertEquals("Opened WhatsApp.", ReplyText.withoutPackages("Opened com.whatsapp.") { "WhatsApp" })
        assertEquals("Opened that app.", ReplyText.withoutPackages("Opened com.whatsapp.") { "com.whatsapp" })
        assertEquals("It is 7.30 now.", ReplyText.withoutPackages("It is 7.30 now."))
    }

    @Test fun everyFailingRouteIsAPlainSentence() {
        listOf("start", "smalltalk", "follow_up", "chat", "llm", "tool:set_alarm", "tool:open_app", "router").forEach { stage ->
            val s = ReplyText.routeFailed(stage)
            assertTrue(s, s.endsWith("."))
            assertFalse(s, s.contains("went wrong"))
            assertFalse(s, s.contains('_'))
            assertFalse(s, pkg.containsMatchIn(s))
        }
    }
}
