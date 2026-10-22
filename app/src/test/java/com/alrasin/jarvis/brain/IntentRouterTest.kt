package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {
    @Test fun conversationAndKnowledgeBypassTheToolModel() = assertRoutes(
        IntentRouter.Route.CHAT,
        listOf(
            "hello",
            "hi",
            "hey there",
            "how are you",
            "how are you doing today",
            "thank you",
            "thanks",
            "who are you",
            "what is your name",
            "do you love me",
            "tell me a joke",
            "joke about cats",
            "tell me a story",
            "fun fact about the moon",
            "tell me a moon fact",
            "tell me an interesting fact about the moon",
            "give me a fun fact",
            "show me a joke",
            "read me a bedtime story",
            "give me some advice",
            "what is gravity",
            "explain gravity",
            "why is the sky blue",
            "what is photosynthesis",
            "how do birds fly",
            "what is the capital of France",
            "tell me about gravity",
            "who was Albert Einstein",
            "write a poem about the moon",
            "can you explain black holes",
            "I feel sad",
            "I am bored",
            "call me Boss",
            "please call me Captain",
            "open the door to new ideas",
            "play devil's advocate",
            "what is bluetooth",
            "what is wifi",
            "how do I turn on wifi",
            "what happens if I turn on airplane mode",
            "what happens if I turn off wifi",
            "how can I set an alarm",
            "why is my battery draining",
            "what does set_alarm mean",
            "explain how notifications work",
            "what is a reminder",
            "how does a timer work",
            "do not call Alex",
            "don't open YouTube",
            "do not send a message",
            "never delete my notes",
            "I don't want you to call Alex",
            "can you not open YouTube",
            "say 'turn on wifi'",
            "\"turn on wifi\"",
            "what does 'turn on wifi' mean",
            "explain the phrase call me later",
            "what is weather",
            "why does it rain",
            "tell me a joke about the news",
            "write a story about a phone",
            "what is the difference between SMS and email",
        ),
    )

    @Test fun phoneActionsAndLocalStatusKeepTheirToolRoute() = assertRoutes(
        IntentRouter.Route.TOOL,
        listOf(
            "turn on flashlight",
            "turn off the flashlight",
            "enable bluetooth",
            "turn off wi-fi",
            "turn on mobile data",
            "turn on do not disturb",
            "turn off airplane mode",
            "turn on hotspot",
            "turn on battery saver",
            "open YouTube",
            "open the camera app",
            "launch WhatsApp",
            "close YouTube",
            "open google dot com",
            "set an alarm for 7 am",
            "wake me up at 6 tomorrow",
            "set a timer for five minutes",
            "start a 10 minute timer",
            "cancel my alarms",
            "take a note buy milk",
            "read my notes",
            "remind me to call mom at 5 pm",
            "what are my reminders",
            "delete all reminders",
            "call Alex",
            "phone the dentist",
            "dial 12345",
            "send a message to Alex saying hello",
            "compose an email about lunch",
            "read my messages",
            "play jazz on Spotify",
            "pause music",
            "volume up",
            "set volume to 30 percent",
            "set brightness to 50 percent",
            "make the screen brighter",
            "what time is it",
            "tell me the time",
            "what is the date today",
            "what day is it",
            "how much battery is left",
            "check storage",
            "device status",
            "take a screenshot",
            "lock the phone",
            "unlock the phone",
            "read the screen",
            "tap the send button",
            "type hello world",
            "scroll down",
            "go back",
            "go home",
            "read my notifications",
            "clear notifications",
            "remember that I like tea",
            "forget that I like tea",
            "what do you remember about me",
            "run routine good night",
            "panic",
            "code red",
            "stop everything and lock",
            "cancel",
            "repeat last",
            "good night",
            "send a message to Alex saying tell me a joke",
            "take a note what is gravity",
            "type latest news",
        ),
    )

    @Test fun explicitSearchAndFreshFactsUseTheWebRoute() = assertRoutes(
        IntentRouter.Route.WEB,
        listOf(
            "search who won the match",
            "search the web for gravity",
            "search for nearby cafes",
            "google photosynthesis",
            "look up train times",
            "search wikipedia for black holes",
            "wiki Albert Einstein",
            "wikipedia the moon",
            "what is the weather",
            "weather in Dhaka",
            "weather tomorrow",
            "will it rain tomorrow",
            "do I need an umbrella today",
            "how hot is it outside",
            "latest news",
            "read the headlines",
            "news about technology",
            "what is the latest cricket score",
            "who won the match today",
            "what is the current exchange rate",
        ),
    )

    @Test fun ambiguousInputRetainsLowConfidenceFallback() {
        val sentences = listOf(
            "",
            "   ",
            "hmm",
            "that one",
            "do it",
            "maybe later",
            "blue",
            "something else",
            "the usual",
            "again tomorrow",
            "can you",
            "xyzzy frobnicate",
        )
        val failures = sentences.mapNotNull { sentence ->
            val decision = IntentRouter.classify(sentence)
            if (decision.isConfident) "$sentence -> $decision" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun scorerWorksWithoutAnExistingRuleMatch() {
        val examples = listOf(
            "what is gravity" to IntentRouter.Route.CHAT,
            "tell me a joke" to IntentRouter.Route.CHAT,
            "turn on flashlight" to IntentRouter.Route.TOOL,
            "send a message to Alex" to IntentRouter.Route.TOOL,
            "search who won the match" to IntentRouter.Route.WEB,
            "latest news" to IntentRouter.Route.WEB,
        )
        for ((sentence, route) in examples) {
            val decision = IntentRouter.classify(sentence, ruleCall = null)
            assertEquals(sentence, route, decision.route)
            assertTrue("$sentence -> $decision", decision.isConfident)
        }
    }

    @Test fun chatGuardsOverrideOverlyBroadRuleMatches() {
        val examples = listOf(
            "call me Boss" to ToolCall("call", mapOf("contact" to "me boss")),
            "open the door to new ideas" to ToolCall("open_app", mapOf("app" to "door to new ideas")),
            "write a poem about the moon" to ToolCall("type_text", mapOf("text" to "a poem about the moon")),
            "do not turn on wifi" to ToolCall("toggle", mapOf("target" to "wifi", "state" to "on")),
        )
        for ((sentence, ruleCall) in examples) {
            val decision = IntentRouter.classify(sentence, ruleCall)
            assertEquals(sentence, IntentRouter.Route.CHAT, decision.route)
            assertTrue("$sentence -> $decision", decision.isConfident)
        }
    }

    @Test fun normalizationDoesNotChangeMeaning() {
        val examples = listOf(
            "  HOW ARE YOU?!  " to IntentRouter.Route.CHAT,
            "  Tell   me a joke. " to IntentRouter.Route.CHAT,
            "CALL ME BOSS!" to IntentRouter.Route.CHAT,
            "Don't turn on Wi-Fi." to IntentRouter.Route.CHAT,
            "  What time is it?  " to IntentRouter.Route.TOOL,
            "TURN ON FLASHLIGHT!" to IntentRouter.Route.TOOL,
            "SEARCH WHO WON THE MATCH?" to IntentRouter.Route.WEB,
        )
        for ((sentence, route) in examples) {
            val decision = IntentRouter.classify(sentence)
            assertEquals(sentence, route, decision.route)
            assertTrue("$sentence -> $decision", decision.isConfident)
        }
    }

    @Test fun confidenceHasAnExplicitFallbackBoundary() {
        assertFalse(IntentRouter.Decision(IntentRouter.Route.TOOL, 0.749f, "uncertain").isConfident)
        assertTrue(IntentRouter.Decision(IntentRouter.Route.CHAT, 0.75f, "confident").isConfident)
    }

    private fun assertRoutes(route: IntentRouter.Route, sentences: List<String>) {
        val failures = sentences.mapNotNull { sentence ->
            val decision = IntentRouter.classify(sentence)
            when {
                decision.route != route -> "$sentence -> $decision; expected $route"
                !decision.isConfident -> "$sentence -> $decision; expected confident routing"
                decision.confidence !in 0f..1f -> "$sentence -> invalid confidence ${decision.confidence}"
                decision.reason.isBlank() -> "$sentence -> missing routing reason"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
