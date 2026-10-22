package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class TimeParserTest {
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)   // Thursday
    private fun at(text: String) = TimeParser.parse(NumberWords.normalize(text), now)?.at

    @Test fun clock() {
        assertEquals(LocalDateTime.of(2026, 10, 22, 17, 0), at("at 5 pm"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 19, 30), at("at 7 30 pm"))
        assertEquals(LocalDateTime.of(2026, 10, 23, 7, 0), at("at 7 am"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 21, 0), at("at 9"))   // 9 am passed → 9 pm
        assertEquals(LocalDateTime.of(2026, 10, 22, 11, 0), at("at 11"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 12, 0), at("at noon"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 21, 30), at("at half past nine"))
    }

    @Test fun days() {
        assertEquals(LocalDateTime.of(2026, 10, 23, 17, 0), at("tomorrow at 5 pm"))
        assertEquals(LocalDateTime.of(2026, 10, 23, 8, 0), at("tomorrow morning at 8"))
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), at("on monday"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 20, 0), at("tonight at 8"))
    }

    @Test fun relative() {
        assertEquals(LocalDateTime.of(2026, 10, 22, 12, 0), at("in two hours"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 30), at("in half an hour"))
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 5), at("in 5 minutes"))
    }

    @Test fun restAndRepeat() {
        val m = TimeParser.parse("to call mom every friday at 6 pm", now)!!
        assertEquals("weekly", m.repeat)
        assertEquals("to call mom", m.rest)
        assertEquals(LocalDateTime.of(2026, 10, 23, 18, 0), m.at)
    }

    @Test fun none() {
        assertNull(at("call mom"))
        assertNull(at("buy 5 apples"))
    }

    @Test fun durations() {
        assertEquals(90L, TimeParser.duration("90 seconds"))
        assertEquals(3900L, TimeParser.duration("1 hour and 5 minutes"))
        assertEquals(1800L, TimeParser.duration("half an hour"))
    }

    @Test fun numberWords() {
        assertEquals("set alarm for 7 30 pm", NumberWords.normalize("set alarm for seven thirty pm"))
        assertEquals("25 minutes", NumberWords.normalize("twenty five minutes"))
        assertEquals("oh no", NumberWords.normalize("oh no"))
        assertEquals("7 05", NumberWords.normalize("seven oh five").replace("7 0 5", "7 05"))
    }
}
