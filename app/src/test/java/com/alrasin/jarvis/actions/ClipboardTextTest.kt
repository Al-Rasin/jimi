package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardTextTest {
    @Test fun rootClipDataGivesText() {
        assertEquals("hello world", ClipboardText.fromRoot("ClipData { text/plain {T:hello world} }"))
    }

    @Test fun rootPlainOutputIsText() {
        assertEquals("meeting at 5", ClipboardText.fromRoot("meeting at 5\n"))
    }

    @Test fun rootErrorsGiveNull() {
        assertNull(ClipboardText.fromRoot(""))
        assertNull(ClipboardText.fromRoot("null"))
        assertNull(ClipboardText.fromRoot("Unknown command: get-primary-clip"))
        assertNull(ClipboardText.fromRoot("java.lang.SecurityException: denied"))
    }

    @Test fun longTextIsCut() {
        val say = ClipboardText.say("a ".repeat(500))
        assertTrue(say.endsWith("… and more."))
        assertEquals("Your clipboard says: hi there", ClipboardText.say("hi\n  there"))
    }
}
