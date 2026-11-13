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

    @Test fun memoryQuestionOnlyForPersonalAsks() {
        listOf("what is my name", "where do i live", "do you remember my car", "what did we talk about", "who am i").forEach {
            assertTrue(it, Persona.isMemoryQuestion(it))
        }
        listOf("can i boil water at 50 degrees", "who painted the mona lisa", "my phone is slow why", "tell me a story").forEach {
            assertFalse(it, Persona.isMemoryQuestion(it))
        }
    }

    @Test fun historyOnlyForFollowUps() {
        assertTrue(Persona.needsHistory("why is that"))
        assertTrue(Persona.needsHistory("tell me more"))
        assertTrue(Persona.needsHistory("how old is he"))
        assertFalse(Persona.needsHistory("what is the capital of france"))
        assertFalse(Persona.needsHistory("how far is the moon"))
    }

    @Test fun promptSaysNotSiriAndNotSure() {
        assertTrue(Persona.SYSTEM_PROMPT.contains("not Siri"))
        assertTrue(Persona.SYSTEM_PROMPT.contains("I'm not sure"))
    }

    @Test fun styleNotesJoinChatPrompt() {
        assertEquals("", Persona.styleNotes("  "))
        val p = Persona.chatPrompt("tell me a story", " funny,\n short answers ")
        assertTrue(p.startsWith(Persona.SYSTEM_PROMPT))
        assertTrue(p.endsWith("reply length: funny, short answers"))
        assertEquals(Persona.SYSTEM_PROMPT, Persona.chatPrompt("tell me a story"))
    }
}
