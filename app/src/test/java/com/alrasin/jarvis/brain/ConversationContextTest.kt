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

    @Test fun freshContextLivesUntilFiveMinutes() {
        remember("call", "contact" to "Rahim")
        now += 299_999
        assertEquals("Rahim", tool("call him").args["contact"])
        now++
        assertNull(context.snapshot())
        clarifies("call him")
    }

    @Test fun resolutionDoesNotRefreshExpiry() {
        remember("set_volume", "level" to "40")
        now += 250_000
        tool("turn it up more")
        now += 50_000
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

    private fun says(text: String) = assertTrue(text, context.resolve(text) is ConversationContext.Resolution.Say)

    @Test fun turnItOffUsesLastSetting() {
        remember("setting", "name" to "reading mode", "state" to "on")
        for (phrase in listOf("turn it off", "Turn this off.", "switch off it", "turn that back off")) {
            assertEquals(phrase, ToolCall("setting", mapOf("name" to "reading mode", "state" to "off")), tool(phrase))
        }
        remember("toggle", "target" to "wifi", "state" to "off")
        assertEquals(ToolCall("toggle", mapOf("target" to "wifi", "state" to "on")), tool("turn it on again"))
    }

    @Test fun turnItOffWithoutSwitchAsks() {
        clarifies("turn it off")
        remember("weather", "place" to "Dhaka")
        clarifies("turn it on")
    }

    @Test fun doItAgainRepeatsLastSwitch() {
        remember("toggle", "target" to "flashlight", "state" to "toggle")
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "toggle")), tool("do it again"))
        remember("send_sms", "contact" to "Rahim", "text" to "Hi")
        clarifies("again")
    }

    @Test fun didYouReadsTheRealState() {
        remember("setting", "name" to "reading mode", "state" to "off")
        assertEquals(ToolCall("setting", mapOf("name" to "reading mode", "state" to "status")), tool("did you turn it off?"))
        assertEquals("status", tool("is it on now").args["state"])
        assertEquals(ToolCall("toggle", mapOf("target" to "wifi", "state" to "status")), tool("did you turn off wifi"))
        assertEquals(ToolCall("setting", mapOf("name" to "dark mode", "state" to "status")), tool("did you turn the dark mode on"))
    }

    @Test fun didYouWithoutSwitchNeverClaims() {
        says("did you turn it off")
        context.remember("hello", null, "I turned it off.")
        says("did you turn it off")
    }

    @Test fun onlyForTodayFollowsLastAction() {
        remember("set_alarm", "time" to "07:00", "repeat" to "daily")
        assertEquals(ToolCall("set_alarm", mapOf("time" to "07:00")), tool("only for today"))
        remember("set_alarm", "time" to "07:00")
        says("just for today")
        remember("toggle", "target" to "dnd", "state" to "on")
        says("only for today")
        context.clear()
        clarifies("only for today")
    }

    @Test fun whereDidYouSaveTheNoteNamesTheApp() {
        context.remember("note buy milk", ToolCall("create_note", mapOf("text" to "buy milk")), "Saved in Google Keep.")
        assertEquals(ConversationContext.Resolution.Say("In Google Keep, Boss."), context.resolve("Where did you save the note?"))
        assertEquals(ConversationContext.Resolution.Say("In Google Keep, Boss."), context.resolve("where did you save it"))
    }

    @Test fun whereIsItWithoutNoteIsNotHandled() {
        remember("weather")
        assertNull(context.resolve("where is it"))
    }

    @Test fun whatDoYouKnowAfterNoteReadsNotes() {
        context.remember("note buy milk", ToolCall("create_note", mapOf("text" to "buy milk")), "Noted.")
        assertEquals("read_notes", tool("what do you know").tool)
    }
}
