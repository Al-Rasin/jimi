package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerFollowupTest {
    private var now = 100L
    private val context = ConversationContext(clockMillis = { now })
    private val original = ToolCall("send_sms", mapOf(
        "contact" to "+15550101000", "contact_name" to "Rahim Khan",
        "text" to "Meet at 5, NOT 6!", "app" to "messenger",
    ))

    private fun remember(call: ToolCall = original) = context.remember("message request", call, "Send requested.")
    private fun resolve(text: String) = (context.resolve(text) as ConversationContext.Resolution.Tool).call

    @Test fun newRecipientKeepsMessengerAndDropsOldName() {
        remember()
        assertEquals(ToolCall("send_sms", mapOf("contact" to "mom", "text" to "Meet at 5, NOT 6!", "app" to "messenger")),
            resolve("send it to mom too"))
    }

    @Test fun sameRecipientKeepsMessengerAndPinnedIdentity() {
        remember()
        assertEquals(original, resolve("send it to him too"))
    }

    @Test fun consecutiveFollowupsKeepMessenger() {
        remember()
        val next = resolve("send it to mom too")
        remember(next.copy(args = next.args + mapOf("contact" to "+15550101001", "contact_name" to "Mom")))
        assertEquals(ToolCall("send_sms", mapOf("contact" to "dad", "text" to "Meet at 5, NOT 6!", "app" to "messenger")),
            resolve("send that to dad as well"))
    }

    @Test fun laterSmsDoesNotInheritMessenger() {
        remember()
        remember(ToolCall("send_sms", mapOf("contact" to "Rahim", "text" to "An SMS")))
        assertEquals(ToolCall("send_sms", mapOf("contact" to "mom", "text" to "An SMS")),
            resolve("send it to mom too"))
    }

    @Test fun expiredMessengerFollowupAsksInsteadOfChoosingAnotherApp() {
        remember()
        now += 5 * 60_000
        assertTrue(context.resolve("send it to mom too") is ConversationContext.Resolution.Clarify)
    }
}
