package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MisheardAppRoutingTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @After fun restoreMatcher() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    @Test fun unknownLaunchFallsBackToConversation() {
        RuleMatcher.appNameMatches = { it == "youtube" }
        assertNull(RuleMatcher.match("open asium"))
        val route = IntentRouter.classify("open asium")
        assertEquals(IntentRouter.Route.CHAT, route.route)
        assertTrue(route.isConfident)
        assertEquals("open_app", RuleMatcher.match("open youtube")?.tool)
    }

    @Test fun embeddedCapabilityQuestionCannotLaunchAnApp() {
        for (text in listOf("asium me what can you do", "show me what can you do", "tell me what can you do")) {
            val route = IntentRouter.classify(text, ToolCall("open_app", mapOf("app" to "asium")))
            assertEquals(text, IntentRouter.Route.CHAT, route.route)
            assertTrue(text, route.isConfident)
        }
        assertNull(RuleMatcher.match("show me what can you do"))
    }

    @Test fun politeLaunchIsStillAnAction() {
        RuleMatcher.appNameMatches = { it == "youtube" }
        val route = IntentRouter.classify("can you open youtube")
        assertEquals(IntentRouter.Route.TOOL, route.route)
        assertTrue(route.isConfident)
    }
}
