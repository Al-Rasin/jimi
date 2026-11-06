package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreNewsRuleTest {
    @Test fun playStoreSearch() {
        mapOf(
            "search whatsapp in play store" to "whatsapp",
            "find telegram app on google play" to "telegram",
            "open play store and search for pubg game" to "pubg",
            "search the play store for calculator" to "calculator",
        ).forEach { (text, q) ->
            val call = RuleMatcher.match(text)
            assertEquals(text, "store_search", call?.tool)
            assertEquals(text, q, call?.arg("query"))
        }
    }

    @Test fun newsPhrases() {
        listOf("whats the news", "any news today", "read me the headlines", "latest news").forEach {
            val call = RuleMatcher.match(it)
            assertEquals(it, "news", call?.tool)
            assertNull(it, call?.arg("topic"))
        }
        assertEquals("bangladesh", RuleMatcher.match("latest news in bangladesh")?.arg("topic"))
        assertEquals("cricket", RuleMatcher.match("news about cricket")?.arg("topic"))
    }
}
