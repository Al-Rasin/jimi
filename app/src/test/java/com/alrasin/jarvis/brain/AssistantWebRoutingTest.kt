package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantWebRoutingTest {
    @Test fun appSearchRulesAreNotReplacedByGenericWebSearch() {
        for (text in listOf("search YouTube for jazz", "search for jazz on Spotify", "search YouTube Music for jazz")) {
            val rule = RuleMatcher.match(text)
            assertEquals("play_media", rule?.tool)
            val intent = IntentRouter.classify(text, rule)
            assertEquals(IntentRouter.Route.WEB, intent.route)
            assertNull(Assistant.webRouteCall(text, rule, intent))
        }
    }

    @Test fun explicitWebSearchRetainsItsParsedQuery() {
        val text = "search the web for Kotlin"
        val rule = RuleMatcher.match(text)
        assertEquals("web_search", rule?.tool)
        assertEquals(rule, Assistant.webRouteCall(text, rule, IntentRouter.classify(text, rule)))
    }

    @Test fun currentInformationWithoutARuleStillUsesWebSearch() {
        val text = "who won the election"
        val call = Assistant.webRouteCall(text, null, IntentRouter.classify(text, null))
        assertEquals("web_search", call?.tool)
        assertEquals(text, call?.arg("query"))
    }
}
