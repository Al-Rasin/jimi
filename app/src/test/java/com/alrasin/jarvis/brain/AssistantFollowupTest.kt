package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.ToolSafetyGate
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFollowupTest {
    private val contacts = listOf(
        Contacts.Contact("Rahim Khan", "+15550000001"),
        Contacts.Contact("Rahim Office", "+15550000002"),
        Contacts.Contact("Rahim Uddin", "+15550000003"),
    )

    private fun select(tool: String, response: String): PendingContactChoice.Reply.Selection {
        val choice = ContactResolver.resolve("Rahim", contacts) as ContactResolver.Resolution.Ambiguous
        val pending = PendingContactChoice { 0L }
        pending.begin(ToolCall(tool, mapOf("contact" to "Rahim", "text" to "See you soon")), choice)
        return pending.answer(response) as PendingContactChoice.Reply.Selection
    }

    @Test fun selectingContactStillRequiresSeparateConfirmationForEveryCommsTool() = runBlocking {
        for (tool in listOf("call", "send_sms", "send_whatsapp")) {
            val selected = select(tool, "the first one")
            val gate = ToolSafetyGate()
            var executions = 0
            val execute: suspend () -> ToolResult = { executions++; ToolResult.ok("Done.") }
            val question = gate.execute(selected.call, Risk.CONFIRM, false, false, null, execute)
            assertTrue(question is Outcome.NeedConfirm)
            assertEquals(0, executions)
            assertTrue(gate.execute(selected.call, Risk.CONFIRM, false, true, null, execute) is Outcome.Done)
            assertEquals(1, executions)
        }
    }

    @Test fun selectedContactDoesNotBypassVoiceVerification() = runBlocking {
        val selected = select("call", "Khan")
        var executions = 0
        val result = ToolSafetyGate().execute(selected.call, Risk.CONFIRM, true, true, false) {
            executions++
            ToolResult.ok("Done.")
        }
        assertTrue(result is Outcome.Denied)
        assertEquals(0, executions)
    }

    @Test fun selectedContactDoesNotBypassPanicMode() = runBlocking {
        val selected = select("send_sms", "office")
        val gate = ToolSafetyGate()
        gate.enterLockdown()
        var executions = 0
        val result = gate.execute(selected.call, Risk.CONFIRM, false, true, true) {
            executions++
            ToolResult.ok("Done.")
        }
        assertTrue(result is Outcome.Denied)
        assertEquals(0, executions)
    }

    @Test fun selectedNumberRemainsPinnedWhenPhonebookOrderChanges() {
        val selected = select("send_sms", "the second one")
        val reordered = listOf(Contacts.Contact("Rahim Office", "+15550000009")) + contacts.reversed()
        val resolved = ContactResolver.resolve(selected.call.arg("contact")!!, reordered) as ContactResolver.Resolution.Found
        assertEquals("+15550000002", resolved.contact.number)
        assertEquals("See you soon", selected.call.arg("text"))
    }
}
