package com.alrasin.jarvis.actions

import android.content.ContextWrapper
import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.ReminderDao
import com.alrasin.jarvis.llm.FunctionGemma
import com.alrasin.jarvis.notify.notificationTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Builds the real tool list without a phone and writes the FunctionGemma prompt prefix + grammar to
 * app/build/llm/ (used by training/ scripts and to test the model on the Mac with llama.cpp).
 */
class ToolPromptDump {
    private val previousMatcher = RuleMatcher.appNameMatches

    @After fun restoreAppNameMatcher() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    private inline fun <reified T> fake(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private fun tools(): List<Tool> {
        val ctx = ContextWrapper(null)
        return buildList {
            addAll(appTools(ctx, AppList(ctx)))
            addAll(timeTools(ctx))
            addAll(commsTools(ctx, Contacts(ctx)))
            addAll(mediaTools(ctx))
            addAll(systemTools(ctx, Torch(ctx)))
            add(unlockTool(ctx) { null })
            addAll(screenTools(ctx))
            addAll(dataTools(fake<NoteDao>(), fake<ReminderDao>(), ReminderScheduler(ctx, fake()), fake<MemoryDao>()))
            addAll(notificationTools(fake<NotificationDao>()))
            addAll(webTools(Web()) { "Dhaka" })
        }
    }

    @Test fun dump() {
        val tools = tools()
        assertEquals("tool names must be unique", tools.size, tools.map { it.name }.toSet().size)
        val dir = File("build/llm").apply { mkdirs() }
        File(dir, "tools.txt").writeText(tools.joinToString("\n") { t -> "${t.name}(${t.args.joinToString { it.name }}) ${t.risk}: ${t.description}" })
        File(dir, "prompt_prefix.txt").writeText(FunctionGemma.prompt(tools, "CONTEXT", "USER").substringBefore("<start_of_turn>user"))
        File(dir, "grammar.gbnf").writeText(FunctionGemma.grammar(tools))
        assertTrue(tools.size > 40)
    }
}
