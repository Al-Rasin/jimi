package com.alrasin.jarvis.brain

import com.alrasin.jarvis.brain.MemoryRetriever.Kind
import com.alrasin.jarvis.brain.MemoryRetriever.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrieverTest {
    private val wife = Memory(1, "My wife's birthday is May 12", 10)
    private val wifi = Memory(2, "The wifi password is example-only!", 20)
    private val parking = Memory(3, "I parked on level B2 near the lift", 30, Kind.NOTE)
    private val memories = listOf(
        wife, wifi, parking,
        Memory(4, "My father's birthday is June 20", 100),
        Memory(5, "My email password is another-example", 200),
        Memory(6, "Buy milk tomorrow", 300, Kind.NOTE),
    )

    @Test fun spokenQuestionsRetrieveRelevantOlderMemories() {
        assertEquals(wife, MemoryRetriever.retrieve("what's my wife's birthday", memories).first())
        assertEquals(parking, MemoryRetriever.retrieve("where did I park", memories).first())
        assertEquals(wifi, MemoryRetriever.retrieve("what is the wifi password", memories).first())
    }

    @Test fun synonymsAndSpeechPunctuationMatch() {
        assertEquals(wife, MemoryRetriever.retrieve("What's my wife’s birthdate?", memories).first())
        assertEquals(wifi, MemoryRetriever.retrieve("Tell me my wi-fi passphrase", memories).first())
        assertEquals(wifi, MemoryRetriever.retrieve("wireless passcode", memories).first())
        assertEquals(parking, MemoryRetriever.retrieve("parking", memories).single())
    }

    @Test fun irrelevantAndEmptyQuestionsDoNotReturnRecentFacts() {
        for (question in listOf("", "what is my", "what is my passport number", "play music")) {
            assertTrue(MemoryRetriever.retrieve(question, memories).isEmpty())
        }
        assertTrue(MemoryRetriever.retrieve("wifi", emptyList()).isEmpty())
    }

    @Test fun recencyBreaksEqualRelevanceTies() {
        val older = Memory(10, "Wifi password is sample-one", 1)
        val newer = Memory(11, "Wifi password is sample-two", 2)
        assertEquals(listOf(newer, older), MemoryRetriever.retrieve("wifi password", listOf(older, newer)))
    }

    @Test fun limitsPreserveCompleteTextsAndIncludeNewlines() {
        val facts = (1L..8L).map { Memory(it, "wifi $it", it) }
        assertEquals(4, MemoryRetriever.retrieve("wifi", facts).size)
        assertEquals(2, MemoryRetriever.retrieve("wifi", facts, topK = 2).size)
        val result = MemoryRetriever.retrieve("wifi", facts, charBudget = 13)
        assertEquals(2, result.size)
        assertEquals(13, result.joinToString("\n") { it.text }.length)
        assertTrue(MemoryRetriever.retrieve("wifi", facts, charBudget = 5).isEmpty())
        assertTrue(MemoryRetriever.retrieve("wifi", facts, topK = 0).isEmpty())
        assertTrue(MemoryRetriever.retrieve("wifi", facts, charBudget = -1).isEmpty())
    }

    @Test fun budgetCanSkipOversizedResult() {
        val small = Memory(20, "wifi", 1)
        assertEquals(listOf(small), MemoryRetriever.retrieve("wifi password", listOf(wifi, small), charBudget = 4))
    }

    @Test fun partialForgetReturnsCandidateForConfirmation() {
        val candidate = MemoryRetriever.selectForgetting("forget my wifi password", memories)!!
        assertEquals(wifi, candidate.memory)
        assertTrue(candidate.requiresConfirmation)
        assertTrue(MemoryRetriever.selectForgetting("wireless passcode", memories)!!.requiresConfirmation)
    }

    @Test fun uniqueFullExactTextNeedsNoQuestion() {
        val candidate = MemoryRetriever.selectForgetting("forget  THE WIFI PASSWORD IS EXAMPLE-ONLY! ", memories)!!
        assertEquals(wifi, candidate.memory)
        assertFalse(candidate.requiresConfirmation)
    }

    @Test fun passwordPunctuationIsNotAnExactMatch() {
        val candidate = MemoryRetriever.selectForgetting(wifi.text.replace("!", "?"), memories)!!
        assertTrue(candidate.requiresConfirmation)
    }

    @Test fun duplicatedExactTextRequiresConfirmation() {
        val duplicate = wifi.copy(id = 12, timeMs = 40)
        val candidate = MemoryRetriever.selectForgetting(wifi.text, memories + duplicate)!!
        assertEquals(duplicate, candidate.memory)
        assertTrue(candidate.requiresConfirmation)
    }

    @Test fun unrelatedPasswordCannotBeForgottenAndNotesAreExcluded() {
        assertNull(MemoryRetriever.selectForgetting("my bank password", memories))
        assertNull(MemoryRetriever.selectForgetting(parking.text, memories))
        assertNull(MemoryRetriever.selectForgetting("", memories))
        assertNull(MemoryRetriever.selectForgetting("all", memories))
        assertNull(MemoryRetriever.selectForgetting("everything", memories))
    }

    @Test fun forgetThatRequiresExplicitExistingFactContext() {
        assertNull(MemoryRetriever.selectForgetting("forget that", memories))
        assertNull(MemoryRetriever.selectForgetting("forget that", memories, selectedFactId = 999))
        assertNull(MemoryRetriever.selectForgetting("forget that", memories, selectedFactId = parking.id))
        val candidate = MemoryRetriever.selectForgetting("forget that", memories, selectedFactId = wifi.id)!!
        assertEquals(wifi, candidate.memory)
        assertTrue(candidate.requiresConfirmation)
    }

    @Test fun embedderFindsFactWithNoSharedWords() {
        val office = Memory(7, "I sit at Rahman Tower in Gulshan", 5)
        val all = memories + office
        assertTrue(MemoryRetriever.retrieve("where do I work", all).none { it == office })
        val fake: (String, List<String>) -> FloatArray = { _, texts -> FloatArray(texts.size) { if ("Tower" in texts[it]) 0.7f else 0.05f } }
        assertEquals(office, MemoryRetriever.retrieve("where do I work", all, similar = fake).first())
    }

    @Test fun noEmbedderSameAsBefore() {
        val none: (String, List<String>) -> FloatArray? = { _, _ -> null }
        assertEquals(MemoryRetriever.retrieve("where did I park", memories), MemoryRetriever.retrieve("where did I park", memories, similar = none))
    }
}
