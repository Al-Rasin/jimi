package com.alrasin.jimi.brain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickFactsTest {
    // Friday 20 November 2026, 10:00 in Dhaka (04:00 UTC).
    private val now = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, ZoneId.of("Asia/Dhaka"))
    private fun a(s: String) = QuickFacts.answer(s, now)

    @Test fun worldTime() {
        assertEquals("It's 4:00 AM in London.", a("What time is it in London?"))
        assertEquals("It's 11:00 PM in New York, Thursday.", a("what's the time in new york"))
        assertEquals("It's 8:00 AM in Dubai.", a("time in dubai"))
        assertEquals("It's 1:00 PM in Tokyo.", a("what time is it in tokyo now"))
        assertNull(a("what time is it"))
    }

    @Test fun spellingAndCapitals() {
        assertEquals("Necessary is spelled N-E-C-E-S-S-A-R-Y.", a("How do you spell necessary?"))
        assertEquals("Beautiful is spelled B-E-A-U-T-I-F-U-L.", a("how is beautiful spelled"))
        assertNull(a("how is the weather"))
        assertEquals("The capital of France is Paris.", a("what is the capital of france"))
        assertEquals("The capital of Bangladesh is Dhaka.", a("capital of bangladesh"))
        assertEquals("The capital of USA is Washington, D.C.", a("what's the capital of the usa"))
        assertNull(a("what is the capital of narnia"))
    }

    @Test fun moonPhase() {
        // 24 Nov 2026 is a full moon; 9 Nov 2026 a new moon.
        val full = ZonedDateTime.of(2026, 11, 24, 20, 0, 0, 0, ZoneId.of("Asia/Dhaka"))
        assertEquals("Yes, tonight is a full moon, Boss.", QuickFacts.answer("is it full moon tonight", full))
        val newMoon = ZonedDateTime.of(2026, 11, 9, 20, 0, 0, 0, ZoneId.of("Asia/Dhaka"))
        assertEquals("Tonight it's a new moon, Boss.", QuickFacts.answer("what's the moon phase", newMoon))
    }

    @Test fun fixedFacts() {
        assertEquals("The Moon is about 384,400 kilometers from Earth.", QuickFacts.answer("how far is the moon"))
        assertEquals("An adult human has 206 bones.", QuickFacts.answer("how many bones in the human body"))
        assertEquals("Jupiter is the biggest planet.", QuickFacts.answer("what is the biggest planet"))
        assertEquals(null, QuickFacts.answer("light"))
    }

    @Test fun calendarQuestions() {
        val now = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, ZoneId.of("Asia/Dhaka"))
        assertEquals("February 2028 has 29 days.", QuickFacts.answer("how many days in february 2028", now))
        assertEquals("No, 2026 is not a leap year.", QuickFacts.answer("is this a leap year", now))
        assertEquals("It's 2026.", QuickFacts.answer("what year is it", now))
        assertEquals("41 days are left in 2026.", QuickFacts.answer("how many days left in the year", now))
        assertEquals("Friday is 27 November.", QuickFacts.answer("what is the date next friday", now))
        assertEquals("90 minutes is 1.5 hours.", QuickFacts.answer("convert 90 minutes to hours", now))
    }

    @Test fun units() {
        assertEquals("1 kilogram is 1000 grams.", a("how many grams in a kilo"))
        assertEquals("5 kilometers is 3.11 miles.", a("convert 5 km to miles"))
        assertEquals("100 degrees Fahrenheit is 37.78 degrees Celsius.", a("100 fahrenheit in celsius"))
        assertEquals("1 foot is 12 inches.", a("how many inches in a foot"))
        assertEquals("0 degrees Celsius is 273.15 kelvin.", a("convert 0 celsius to kelvin"))
        assertNull(a("convert 5 km to kg"))
    }

    @Test fun dates() {
        assertEquals("25 December is in 35 days, a Friday.", a("how many days until december 25"))
        assertEquals("1 January 2027 is in 42 days, a Friday.", a("how many days till 1st january"))
        assertEquals("21 November is tomorrow, a Saturday.", a("how many days until 21 november"))
        assertEquals("1 January 2000 was a Saturday.", a("what day was 1 january 2000"))
        assertNull(a("what day is 31 february"))
    }
}
