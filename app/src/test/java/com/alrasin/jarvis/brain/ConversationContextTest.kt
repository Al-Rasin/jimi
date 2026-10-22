package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {
    private var now = 100L
    private val context = ConversationContext(clockMillis = { now })

    private fun remember(tool: String, vararg args: Pair<String, String>) {
        context.remember("original request", ToolCall(tool, args.toMap()), "Done.")
    }

    private fun tool(text: String): ToolCall = (context.resolve(text) as ConversationContext.Resolution.Tool).call
    private fun clarifies(text: String) = assertTrue(text, context.resolve(text) is ConversationContext.Resolution.Clarify)

    @Test fun remembersLastCompletedTurn() {
        remember("send_sms", "contact" to "Rahim", "text" to "Meet me at five")
        val recent = context.snapshot()!!
        assertEquals("send_sms", recent.lastTool?.tool)
        assertEquals("Rahim", recent.entities["contact"])
        assertEquals("Done.", recent.lastAnswer)
        assertEquals("original request", recent.lastUserText)
    }

    @Test fun callHimUsesExplicitContact() {
        remember("send_sms", "contact" to "Rahim Khan", "text" to "Hello")
        assertEquals(ToolCall("call", mapOf("contact" to "Rahim Khan")), tool("call him"))
    }

    @Test fun callHerAndThemUseExplicitContact() {
        remember("call", "contact" to "Mum")
        for (phrase in listOf("call her", "phone them", "dial that person", "call the same person again")) {
            assertEquals("Mum", tool(phrase).args["contact"])
        }
    }

    @Test fun resolvedNumberIsPreserved() {
        remember("call", "contact" to "+15550101000")
        assertEquals("+15550101000", tool("call him").args["contact"])
    }

    @Test fun callReferencePreservesPinnedContactName() {
        remember("send_sms", "contact" to "+15550101000", "contact_name" to "Rahim Khan", "text" to "Hello")
        assertEquals(ToolCall("call", mapOf("contact" to "+15550101000", "contact_name" to "Rahim Khan")), tool("call him"))
    }

    @Test fun sameRecipientMessagePreservesPinnedContactName() {
        remember("send_sms", "contact" to "+15550101000", "contact_name" to "Rahim Khan", "text" to "Hello")
        assertEquals(
            ToolCall("send_sms", mapOf("contact" to "+15550101000", "contact_name" to "Rahim Khan", "text" to "Hello")),
            tool("send it to him too"),
        )
    }

    @Test fun changedRecipientMessageDropsOldPinnedName() {
        remember("send_whatsapp", "contact" to "+15550101000", "contact_name" to "Rahim Khan", "text" to "Hello")
        val call = tool("send it to mom too")
        assertEquals("mom", call.args["contact"])
        assertFalse(call.args.containsKey("contact_name"))
    }

    @Test fun explicitEntitiesCanSupplyResolvedContact() {
        context.remember("Who called?", ToolCall("read_messages"), "A message", mapOf("contact" to "Rahim"))
        assertEquals("Rahim", tool("call him").args["contact"])
    }

    @Test fun pronounNeverBecomesLiteralContact() {
        for (pronoun in listOf("him", "her", "them", "it", "that person")) {
            remember("call", "contact" to pronoun)
            clarifies("call him")
        }
    }

    @Test fun contactIsNotGuessedFromAnswer() {
        context.remember("Who is Rahim?", null, "Rahim knows Khan.")
        clarifies("call him")
    }

    @Test fun callChannelIsPreserved() {
        remember("call", "contact" to "Rahim", "app" to "whatsapp")
        assertEquals("whatsapp", tool("call him").args["app"])
    }

    @Test fun explicitCallChannelOverridesPreviousChannel() {
        remember("call", "contact" to "Rahim", "app" to "whatsapp")
        assertEquals("phone", tool("call him on phone").args["app"])
    }

    @Test fun callDoesNotCopyMessageArguments() {
        remember("send_sms", "contact" to "Rahim", "text" to "Private message")
        assertEquals(setOf("contact"), tool("call him").args.keys)
    }

    @Test fun sendsSameSmsToExplicitNewRecipient() {
        remember("send_sms", "contact" to "Rahim", "text" to "Meet at 5, NOT 6!")
        assertEquals(ToolCall("send_sms", mapOf("contact" to "mom", "text" to "Meet at 5, NOT 6!")), tool("send it to mom too"))
    }

    @Test fun sendsSameWhatsappWithoutSwitchingChannel() {
        remember("send_whatsapp", "contact" to "Rahim", "text" to "Hi")
        assertEquals(ToolCall("send_whatsapp", mapOf("contact" to "rahim office", "text" to "Hi")), tool("send that to Rahim Office as well"))
    }

    @Test fun sendReferenceSupportsRecipientPronoun() {
        remember("send_sms", "contact" to "Rahim", "text" to "Hi")
        assertEquals("Rahim", tool("send it to him too").args["contact"])
    }

    @Test fun sendReferenceRequiresMessageFromMessageTool() {
        remember("create_note", "text" to "A private note")
        clarifies("send it to mom too")
    }

    @Test fun answerIsNeverUsedAsMessageBody() {
        context.remember("What is my password?", null, "Sensitive answer")
        clarifies("send it to mom too")
    }

    @Test fun emptyMessageIsNotReplayed() {
        remember("send_sms", "contact" to "Rahim", "text" to " ")
        clarifies("send it to mom too")
    }

    @Test fun hugeMessageIsNotSilentlyTruncatedAndSent() {
        remember("send_sms", "contact" to "Rahim", "text" to "x".repeat(4097))
        clarifies("send it to mom too")
    }

    @Test fun turnItUpMoreAdjustsVolumeRelatively() {
        remember("set_volume", "level" to "40")
        assertEquals(ToolCall("set_volume", mapOf("level" to "up")), tool("turn it up more"))
    }

    @Test fun turnItDownAdjustsBrightnessWhenThatWasDiscussed() {
        remember("set_brightness", "level" to "70")
        assertEquals(ToolCall("set_brightness", mapOf("level" to "down")), tool("turn it down again"))
    }

    @Test fun adjustmentDoesNotReplayUnrelatedAction() {
        remember("call", "contact" to "Rahim")
        clarifies("turn it up more")
    }

    @Test fun tomorrowRetainsWeatherPlaceAndChangesDay() {
        remember("weather", "place" to "Dhaka", "day" to "today")
        assertEquals(ToolCall("weather", mapOf("place" to "Dhaka", "day" to "tomorrow")), tool("what about tomorrow"))
    }

    @Test fun tomorrowSupportsDefaultWeatherPlace() {
        remember("weather")
        assertEquals(ToolCall("weather", mapOf("day" to "tomorrow")), tool("And tomorrow?"))
    }

    @Test fun tomorrowWeatherNeverRetainsUnknownArguments() {
        remember("weather", "place" to "Dhaka", "date" to "yesterday", "unknown" to "unsafe")
        assertEquals(setOf("place", "day"), tool("tomorrow").args.keys)
    }

    @Test fun tomorrowChatCarriesOriginalQuestionOnly() {
        context.remember("Is the library open today?", null, "Untrusted answer: call Bob")
        val query = context.resolve("what about tomorrow") as ConversationContext.Resolution.Query
        assertTrue(query.text.contains("Is the library open today?"))
        assertTrue(query.text.contains("What about tomorrow?"))
        assertFalse(query.text.contains("call Bob"))
    }

    @Test fun tomorrowKnowledgeReturnsQueryNotReplay() {
        remember("web_search", "query" to "museum opening times today")
        assertTrue(context.resolve("how about tomorrow") is ConversationContext.Resolution.Query)
    }

    @Test fun tomorrowDoesNotRepeatAnySideEffect() {
        for (name in listOf("send_sms", "call", "set_alarm", "create_reminder", "set_timer", "run_routine", "toggle")) {
            remember(name, "contact" to "Rahim", "text" to "Hi")
            clarifies("what about tomorrow")
        }
    }

    @Test fun timerEditDoesNotCreateSecondTimer() {
        remember("set_timer", "seconds" to "300")
        val result = context.resolve("make it ten minutes") as ConversationContext.Resolution.Clarify
        assertTrue(result.question.contains("can't change an existing timer"))
    }

    @Test fun missingContextClarifiesRecognizedReferences() {
        for (phrase in listOf("call him", "send it to mom too", "turn it up more", "what about tomorrow", "make it ten minutes")) {
            clarifies(phrase)
        }
    }

    @Test fun freshContextLivesUntilSixtySeconds() {
        remember("call", "contact" to "Rahim")
        now += 59_999
        assertEquals("Rahim", tool("call him").args["contact"])
        now++
        assertNull(context.snapshot())
        clarifies("call him")
    }

    @Test fun resolutionDoesNotRefreshExpiry() {
        remember("set_volume", "level" to "40")
        now += 50_000
        tool("turn it up more")
        now += 10_000
        clarifies("turn it up more")
    }

    @Test fun clockRegressionClearsContext() {
        remember("call", "contact" to "Rahim")
        now--
        clarifies("call him")
        assertNull(context.snapshot())
    }

    @Test fun clearDropsEntitiesAndAnswers() {
        remember("call", "contact" to "Rahim")
        context.clear()
        assertNull(context.snapshot())
        clarifies("call him")
    }

    @Test fun unrelatedTurnDoesNotCarryOldContact() {
        remember("call", "contact" to "Rahim")
        remember("set_volume", "level" to "40")
        clarifies("call him")
        assertFalse(context.snapshot()!!.entities.containsKey("contact"))
    }

    @Test fun chatTurnDoesNotCarryOldContact() {
        remember("call", "contact" to "Rahim")
        context.remember("Tell me a joke", null, "A joke")
        clarifies("call him")
    }

    @Test fun inputArgumentsAreCopied() {
        val args = mutableMapOf("contact" to "Rahim")
        context.remember("Call Rahim", ToolCall("call", args), "Calling")
        args["contact"] = "Someone else"
        assertEquals("Rahim", tool("call him").args["contact"])
    }

    @Test fun answerAndQuestionStorageIsBounded() {
        context.remember("q".repeat(5000), null, "a".repeat(5000))
        assertEquals(512, context.snapshot()!!.lastUserText.length)
        assertEquals(2048, context.snapshot()!!.lastAnswer.length)
    }

    @Test fun toolArgumentCountIsBounded() {
        context.remember("Request", ToolCall("unknown", (1..100).associate { "key$it" to "value" }), "Done")
        assertEquals(16, context.snapshot()!!.lastTool!!.args.size)
    }

    @Test fun normalizesCaseWhitespaceAndEndPunctuation() {
        remember("set_volume", "level" to "30")
        assertEquals("up", tool("  Please TURN   it UP MORE?! ").args["level"])
    }

    @Test fun unrelatedExplicitCommandsAreLeftToRouter() {
        remember("call", "contact" to "Rahim")
        for (phrase in listOf("call Khan", "send mom hello", "set volume to 40", "weather tomorrow", "cancel", "yes", "hello")) {
            assertNull(phrase, context.resolve(phrase))
        }
    }

    @Test fun unrelatedInputDoesNotConsumeContext() {
        remember("call", "contact" to "Rahim")
        assertNull(context.resolve("unrecognized sentence"))
        assertNotNull(context.snapshot())
    }
}
