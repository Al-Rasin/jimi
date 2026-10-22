package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {
    @Test fun yesNo() {
        assertTrue(Persona.isYes("Yes please"))
        assertTrue(Persona.isYes("okay."))
        assertTrue(Persona.isNo("no"))
        assertTrue(Persona.isNo("never mind"))
        assertFalse(Persona.isYes("yes call mom later"))
    }

    @Test fun greetings() {
        assertEquals("Hello Boss. What can I do?", Persona.smallTalk("Hey Jimi", 10))
        assertEquals("Good evening, Boss.", Persona.smallTalk("good evening", 20))
        assertNotNull(Persona.smallTalk("how are you", 10))
    }

    @Test fun goodMorningIsARoutineNotSmallTalk() = assertNull(Persona.smallTalk("good morning", 8))

    @Test fun commandsAreNotSmallTalk() = assertNull(Persona.smallTalk("open youtube", 10))
}
