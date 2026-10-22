package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaEdgeCasesTest {
    @Test fun cleanNormalizesCasePunctuationAndWhitespace() {
        assertEquals("hey jimi please", Persona.clean("  HeY,\tJiMi!\nPlease... "))
        assertEquals("don't", Persona.clean("DON'T!"))
        assertEquals("", Persona.clean(" \t\n?! "))
    }

    @Test fun spokenConfirmationsAcceptCaseAndSentencePunctuation() {
        for (text in listOf("YES!", "Go Ahead, Please.", "Do it!", "Of course.", "Hmm yes", "Send it.")) {
            assertTrue(text, Persona.isYes(text))
            assertFalse(text, Persona.isNo(text))
        }
    }

    @Test fun spokenCancellationsAcceptCaseAndSentencePunctuation() {
        for (text in listOf("NO!", "Don't.", "CANCEL IT", "Stop, please.", "Nevermind", "Not now.", "Leave it!")) {
            assertTrue(text, Persona.isNo(text))
            assertFalse(text, Persona.isYes(text))
        }
    }

    @Test fun emptyAndAmbiguousAnswersDoNotConfirmOrCancel() {
        for (text in listOf("", "  ", "?!", "maybe", "yes no", "not yes", "yesterday", "nobody",
            "yes but wait", "no call tomorrow", "cancel my alarm")) {
            assertFalse(text, Persona.isYes(text))
            assertFalse(text, Persona.isNo(text))
        }
    }

    @Test fun greetingsAndThanksHandleAssistantNameAndPunctuation() {
        assertEquals("Hello Boss. What can I do?", Persona.smallTalk("HELLO, JIMI!", 10))
        assertEquals("Hello Boss. What can I do?", Persona.smallTalk("Hi Jarvis.", 10))
        assertEquals("Walaikum assalam, Boss.", Persona.smallTalk("Assalamu Alaikum, Jimi!", 10))
        assertEquals("Any time, Boss.", Persona.smallTalk("THANK YOU SO MUCH, JIMI!", 10))
    }

    @Test fun contractionsProduceNaturalFixedReplies() {
        assertEquals("All good, Boss. Ready when you are.", Persona.smallTalk("How's it going?", 10))
        assertEquals("I'm Jimi, your phone assistant.", Persona.smallTalk("What's your name?", 10))
        assertEquals("I'm here, Boss.", Persona.smallTalk("Can you hear me, Jimi?", 10))
        assertEquals("Bye, Boss.", Persona.smallTalk("See ya!", 10))
    }

    @Test fun greetingUsesHourAtDayPartBoundaries() {
        val cases = mapOf(3 to "evening", 4 to "morning", 11 to "morning", 12 to "afternoon",
            16 to "afternoon", 17 to "evening", 23 to "evening")
        cases.forEach { (hour, part) ->
            assertEquals("hour=$hour", "Good $part, Boss.", Persona.smallTalk("good evening", hour))
        }
    }

    @Test fun smallTalkDoesNotConsumeCommandsOrEmptySpeech() {
        for (text in listOf("", "   ", "?!", "hello open camera", "thanks and set a timer",
            "help me set volume to fifty percent", "good morning", "good morning Jimi")) {
            assertNull(text, Persona.smallTalk(text, 8))
        }
    }

    @Test fun randomJokeAlwaysProducesASpeakableReply() {
        val reply = Persona.smallTalk("Tell me a joke!", 10)
        assertTrue(reply in setOf(
            "I told my phone a joke. It didn't laugh, but the battery dropped a percent.",
            "Why did the developer go broke? He used up all his cache.",
            "I would tell you a UDP joke, but you might not get it."
        ))
    }
}
