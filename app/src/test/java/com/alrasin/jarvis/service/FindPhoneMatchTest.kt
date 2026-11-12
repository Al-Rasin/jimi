package com.alrasin.jarvis.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPhoneMatchTest {
    private val list = "01712 345678, +8801812-000111; junk"

    @Test fun trustedParsesTails() {
        assertEquals(listOf("712345678", "812000111"), FindPhoneMatch.trusted(list))
    }

    @Test fun matchesTrustedNumberWithWord() {
        assertTrue(FindPhoneMatch.matches("+8801712345678", "Bluebird where are you", list, "bluebird"))
        assertTrue(FindPhoneMatch.matches("01812000111", "find: BLUE  BIRD!", list, "blue bird"))
    }

    @Test fun rejectsWrongSenderOrWord() {
        assertFalse(FindPhoneMatch.matches("01999999999", "bluebird", list, "bluebird"))
        assertFalse(FindPhoneMatch.matches("01712345678", "bluebirds", list, "bluebird"))
        assertFalse(FindPhoneMatch.matches("01712345678", "hello", list, "bluebird"))
        assertFalse(FindPhoneMatch.matches(null, "bluebird", list, "bluebird"))
    }

    @Test fun offWhenNotSet() {
        assertFalse(FindPhoneMatch.matches("01712345678", "bluebird", null, "bluebird"))
        assertFalse(FindPhoneMatch.matches("01712345678", "bluebird", list, null))
        assertFalse(FindPhoneMatch.matches("01712345678", "abc", list, "abc"))
    }

    @Test fun replyText() {
        assertTrue(FindPhoneMatch.reply(null).contains("not available"))
        assertTrue(FindPhoneMatch.reply("1.0, 2.0").endsWith("Location: 1.0, 2.0"))
    }
}
