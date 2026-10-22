package com.alrasin.jarvis.actions

import android.content.ContextWrapper
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.llm.FunctionGemma
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionContractTest {
    private val context = ContextWrapper(null)
    private val tools = commsTools(context, Contacts(context)) + mediaTools(context)

    @Test fun appCommunicationsKeepExistingConfirmationRisk() {
        for (name in listOf("call", "send_sms", "send_whatsapp")) {
            assertEquals(name, Risk.CONFIRM, tools.single { it.name == name }.risk)
        }
        assertEquals(Risk.SAFE, tools.single { it.name == "play_media" }.risk)
    }

    @Test fun declarationsExposeAppChoicesAndVideoToFunctionGemma() {
        val declaration = FunctionGemma.declarations(tools)
        for (word in listOf("whatsapp", "messenger", "video", "youtube_music", "spotify")) {
            assertTrue(word, declaration.contains(word, ignoreCase = true))
        }
        assertEquals(tools.size, tools.map { it.name }.distinct().size)
    }

    @Test fun lockedAppActionsCannotBypassConfirmationOrVoiceFailure() = runBlocking {
        for (call in listOf(
            ToolCall("call", mapOf("contact" to "mom", "app" to "whatsapp", "video" to "true")),
            ToolCall("call", mapOf("contact" to "mom", "app" to "messenger")),
            ToolCall("send_sms", mapOf("contact" to "mom", "app" to "messenger", "text" to "hello")),
            ToolCall("send_whatsapp", mapOf("contact" to "mom", "text" to "hello")),
        )) {
            var ran = false
            val gate = ToolSafetyGate()
            val risk = tools.single { it.name == call.tool }.risk
            assertTrue(gate.execute(call, risk, true, false, true) {
                ran = true
                com.alrasin.jarvis.core.ToolResult.ok("unexpected")
            } is Outcome.NeedConfirm)
            assertTrue(gate.execute(call, risk, true, true, false) {
                ran = true
                com.alrasin.jarvis.core.ToolResult.ok("unexpected")
            } is Outcome.Denied)
            assertFalse(ran)
        }
    }
}
