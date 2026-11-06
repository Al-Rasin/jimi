package com.alrasin.jarvis.actions

import android.content.ContextWrapper
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.MemoryFact
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.ReminderDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class DataToolsMemoryTest {
    private val wifi = MemoryFact(id = 7, timeMs = 10, text = "My wifi password is sample-pass")
    private val birthday = MemoryFact(id = 8, timeMs = 20, text = "My wife's birthday is May 12")

    @Test fun partialMatchDeletesBestCandidateAfterGateConfirm() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        val result = forget(memory, "my wifi password")
        assertTrue(result.ok)
        assertEquals("Forgotten: ${wifi.text}.", result.say)
        assertEquals(listOf(wifi.id), memory.deleted)
    }

    @Test fun exactMatchDeletesOnlyMatchingId() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        assertTrue(forget(memory, wifi.text).ok)
        assertEquals(listOf(wifi.id), memory.deleted)
        assertEquals(listOf(birthday), memory.all())
    }

    @Test fun bulkRequestsNeverDelete() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        for (query in listOf("all", "everything", "forget everything")) {
            assertFalse(forget(memory, query).ok)
        }
        assertTrue(memory.deleted.isEmpty())
    }

    @Test fun thatWithoutReferentDoesNotGuessNewestFact() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        assertFalse(forget(memory, "that").ok)
        assertTrue(memory.deleted.isEmpty())
    }

    @Test fun thatUsesProvidedStableId() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        assertTrue(forget(memory, "that", wifi.id).ok)
        assertEquals(listOf(wifi.id), memory.deleted)
    }

    @Test fun forgetThatRemovesJustRememberedFact() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi))
        val tools = tools(memory)
        assertTrue(run(tools, "remember_fact", "my car is blue").ok)
        val result = run(tools, "forget_fact", "that")
        assertTrue(result.ok)
        assertEquals(listOf("My wifi password is sample-pass"), memory.all().map { it.text })
    }

    @Test fun rememberCleansTimestampsAndPunctuation() = runBlocking {
        val memory = FakeMemoryDao(emptyList())
        assertTrue(run(tools(memory), "remember_fact", "that my car is blue 2026-11-01T10:22:33.").ok)
        assertEquals(listOf("My car is blue"), memory.all().map { it.text })
    }

    @Test fun rememberJunkFails() = runBlocking {
        val memory = FakeMemoryDao(emptyList())
        assertFalse(run(tools(memory), "remember_fact", "..").ok)
        assertTrue(memory.all().isEmpty())
    }

    @Test fun rememberSameFactTwiceKeepsOne() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi))
        assertEquals("I already know that.", run(tools(memory), "remember_fact", "my wifi password is sample-pass.").say)
        assertEquals(1, memory.all().size)
    }

    @Test fun rememberSameSubjectReplacesOldFact() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        assertTrue(run(tools(memory), "remember_fact", "My wifi password is new-pass").ok)
        assertEquals(listOf(wifi.id), memory.deleted)
        assertTrue(memory.all().any { it.text == "My wifi password is new-pass" })
    }

    @Test fun recallDropsJunkAndDuplicates() = runBlocking {
        val memory = FakeMemoryDao(listOf(
            MemoryFact(id = 1, timeMs = 1, text = "My car is blue"),
            MemoryFact(id = 2, timeMs = 2, text = ".."),
            MemoryFact(id = 3, timeMs = 3, text = "my car is blue."),
            MemoryFact(id = 4, timeMs = 4, text = "1730457600000 I like tea"),
        ))
        val result = run(tools(memory), "recall_facts", null)
        assertEquals("I remember: I like tea. My car is blue.", result.say)
        assertEquals(setOf("I like tea", "My car is blue"), memory.all().map { it.text }.toSet())
    }

    @Test fun staleReferentDoesNotDeleteAnotherFact() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        assertFalse(forget(memory, "that", 999).ok)
        assertTrue(memory.deleted.isEmpty())
    }

    private fun tools(memory: MemoryDao): List<com.alrasin.jarvis.core.Tool> {
        val reminders = fake<ReminderDao>()
        return dataTools(fake<NoteDao>(), reminders, ReminderScheduler(ContextWrapper(null), reminders), memory)
    }

    private suspend fun run(tools: List<com.alrasin.jarvis.core.Tool>, name: String, text: String?) =
        tools.single { it.name == name }.run(
            ToolCall(name, text?.let { mapOf("text" to it) }.orEmpty()),
            ToolContext(ContextWrapper(null), text.orEmpty(), false),
        )

    private suspend fun forget(memory: MemoryDao, query: String, selectedId: Long? = null): com.alrasin.jarvis.core.ToolResult {
        val context = ContextWrapper(null)
        val reminders = fake<ReminderDao>()
        val tool = dataTools(fake<NoteDao>(), reminders, ReminderScheduler(context, reminders), memory) { selectedId }
            .single { it.name == "forget_fact" }
        return tool.run(ToolCall("forget_fact", mapOf("text" to query)), ToolContext(context, query, false))
    }

    private inline fun <reified T> fake(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private class FakeMemoryDao(facts: List<MemoryFact>) : MemoryDao {
        private val facts = facts.toMutableList()
        val deleted = mutableListOf<Long>()
        private var nextId = 100L
        override suspend fun insert(f: MemoryFact): Long {
            val saved = if (f.id == 0L) f.copy(id = nextId++) else f
            facts.add(saved)
            return saved.id
        }
        override suspend fun delete(id: Long) {
            deleted.add(id)
            facts.removeAll { it.id == id }
        }
        override suspend fun all(): List<MemoryFact> = facts.toList()
        override fun flow(): Flow<List<MemoryFact>> = flowOf(facts.toList())
    }
}
