package com.alrasin.jarvis.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRoutingTest {
    @Test fun recognizesScreenTasks() {
        listOf("turn on dark mode in settings", "book the first Pathao ride",
            "reply ok to the last WhatsApp from Rahim", "please change my profile in Instagram",
            "can you open YouTube and search for jazz").forEach { assertTrue(it, AgentRouting.needsScreen(it)) }
    }

    @Test fun keepsExplanationsAndOrdinaryConversationOut() {
        listOf("how do I turn on dark mode in settings", "what is Pathao", "explain how to book an Uber ride",
            "tell me about WhatsApp", "hello", "set an alarm for 7 am", "can you explain WhatsApp settings")
            .forEach { assertFalse(it, AgentRouting.needsScreen(it)) }
    }

    @Test fun unsupportedTasksDoNotBecomePartialTools() {
        listOf("turn on dark mode in settings", "book the first Pathao ride",
            "reply ok to the last WhatsApp from Rahim", "open YouTube and search for jazz",
            "change my profile in Instagram").forEach { request ->
            assertTrue(request, AgentRouting.needsMultiStep(request))
            listOf("open_app", "toggle", "read_screen", "tap", "type_text", "scroll", "send_whatsapp")
                .forEach { tool -> assertFalse("$request: $tool", AgentRouting.acceptsSingleTool(request, tool)) }
        }
    }

    @Test fun simpleCommandsAndQuestionsStillUseExistingRouting() {
        listOf("open WhatsApp", "scroll the screen down", "tap the settings button", "turn on wifi",
            "how do I turn on dark mode in settings", "what is the first Pathao ride")
            .forEach { assertFalse(it, AgentRouting.needsMultiStep(it)) }
        assertTrue(AgentRouting.acceptsSingleTool("open WhatsApp", "open_app"))
    }
}
