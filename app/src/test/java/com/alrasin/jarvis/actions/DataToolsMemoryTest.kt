package com.alrasin.jarvis.actions

import android.content.ContextWrapper
import com.alrasin.jarvis.core.Json
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

    @Test fun partialMatchReturnsCandidateWithoutDeleting() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        val result = forget(memory, "my wifi password")
        assertFalse(result.ok)
        assertEquals("Forget '${wifi.text}'?", result.say)
        val candidate = Json.parse(result.data) as Map<*, *>
        assertEquals("forget_fact_confirmation", candidate["type"])
        assertEquals(wifi.id, (candidate["id"] as Number).toLong())
        assertEquals(wifi.text, candidate["text"])
        assertTrue(memory.deleted.isEmpty())
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

    @Test fun thatUsesProvidedStableIdAndStillConfirms() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        val result = forget(memory, "that", wifi.id)
        assertFalse(result.ok)
        assertEquals("Forget '${wifi.text}'?", result.say)
        assertTrue(memory.deleted.isEmpty())
    }

    @Test fun staleReferentDoesNotDeleteAnotherFact() = runBlocking {
        val memory = FakeMemoryDao(listOf(wifi, birthday))
        assertFalse(forget(memory, "that", 999).ok)
        assertTrue(memory.deleted.isEmpty())
    }

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
        override suspend fun insert(f: MemoryFact): Long {
            facts.add(f)
            return f.id
        }
        override suspend fun delete(id: Long) {
            deleted.add(id)
            facts.removeAll { it.id == id }
        }
        override suspend fun all(): List<MemoryFact> = facts.toList()
        override fun flow(): Flow<List<MemoryFact>> = flowOf(facts.toList())
    }
}
