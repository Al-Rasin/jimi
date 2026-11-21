package com.alrasin.jimi.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeardFixTest {
    @Test fun acceptsRealFix() {
        assertEquals("why is the sky blue", HeardFix.accept("why the sky colour display", "why is the sky blue"))
    }

    @Test fun rejectsSameOrEmptyOrHuge() {
        assertNull(HeardFix.accept("why is the sky blue", "Why is the sky blue?"))
        assertNull(HeardFix.accept("hi", " "))
        assertNull(HeardFix.accept("hi", null))
        assertNull(HeardFix.accept("hi", "x".repeat(60)))
    }

    @Test fun onlySpokenInputIsFixed() {
        assertTrue(HeardFix.isVoice("voice"))
        assertTrue(!HeardFix.isVoice("typed"))
    }

    @Test fun splitsTagFromReply() {
        val (heard, answer) = HeardFix.split("[heard: why is the sky blue] Because blue light scatters more.")
        assertEquals("why is the sky blue", heard)
        assertEquals("Because blue light scatters more.", answer)
    }

    @Test fun replyWithoutTagIsUntouched() {
        assertEquals(null to "Because blue light scatters.", HeardFix.split("Because blue light scatters."))
        assertEquals(null to "[1] is a list", HeardFix.split("[1] is a list"))
    }

    @Test fun streamHoldsTagAcrossPieces() {
        val p = HeardFix.StreamPrefix()
        val out = listOf("[hea", "rd: why is", " the sky blue]", " Because", " light.").joinToString("") { p.feed(it) } + p.flush()
        assertEquals("why is the sky blue", p.heard)
        assertEquals("Because light.", out)
    }

    @Test fun streamPassesNormalTextAtOnce() {
        val p = HeardFix.StreamPrefix()
        assertEquals("Hello", p.feed("Hello"))
        assertEquals(" there", p.feed(" there"))
        assertNull(p.heard)
    }

    @Test fun cloudNoneKeepsHeard() {
        val call = CloudBrain.parseToolCall("{\"tool\":\"none\",\"say\":\"Because light scatters.\",\"heard\":\"why is the sky blue\"}", emptyList())
        assertEquals("why is the sky blue", call?.arg("heard"))
        assertEquals("Because light scatters.", call?.arg("say"))
    }
}
