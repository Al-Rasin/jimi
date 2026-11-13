package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FactSpotterTest {
    @Test fun spotsLastingFacts() {
        assertEquals("My wife's name is Sara", FactSpotter.spot("my wife's name is Sara."))
        assertEquals("I live in Dhaka", FactSpotter.spot("I live in Dhaka"))
        assertEquals("I work at a bank", FactSpotter.spot("i work at a bank"))
        assertEquals("I'm a doctor", FactSpotter.spot("I'm a doctor"))
        assertEquals("I am allergic to peanuts", FactSpotter.spot("I am allergic to peanuts"))
        assertEquals("I love biryani", FactSpotter.spot("I love biryani!"))
        assertEquals("My mom's phone number is 01711", FactSpotter.spot("my mom's phone number is 01711"))
        assertEquals("I was born in Sylhet", FactSpotter.spot("I was born in Sylhet"))
        assertEquals("I have a dog named Max", FactSpotter.spot("I have a dog named Max"))
    }

    @Test fun skipsQuestionsCommandsAndPassingStates() {
        listOf(
            "what is my wife's name", "is my wife's name Sara", "my name is what?", "my battery is low",
            "my phone is slow", "I am tired", "I have a headache", "I have a meeting today", "I love you",
            "I like it", "call my wife", "please open youtube", "I live", "hello there",
            "I'm a bit busy right now", "my head is hurting",
        ).forEach { assertNull(it, FactSpotter.spot(it)) }
    }

    @Test fun cloudFactsRankedByQuestion() {
        val facts = listOf("My car is a blue Toyota", "My wife's name is Sara", "I live in Dhaka") +
            (1..10).map { "Filler fact number $it" }
        assertEquals("My wife's name is Sara", CloudBrain.relevantFacts("what is my wife's name", facts).first())
        assertEquals(facts.take(8), CloudBrain.relevantFacts("what do you know about me", facts))
    }

    @Test fun contextLineAddsBatteryAndShortScreen() {
        assertEquals("time 10:00", Assistant.contextLine("10:00", null, null, "  "))
        val line = Assistant.contextLine("10:00", "com.whatsapp", 54, "Chats\n  Sara   hi " + "x".repeat(500))
        assertEquals("time 10:00, app com.whatsapp, battery 54%, screen: Chats Sara hi ", line.take(65))
        assertEquals(251, line.length)
    }
}
