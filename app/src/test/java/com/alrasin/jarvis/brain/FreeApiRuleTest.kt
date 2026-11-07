package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class FreeApiRuleTest {
    private val now = LocalDateTime.of(2026, 11, 7, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)
    private fun c(tool: String, vararg args: Pair<String, String>) = ToolCall(tool, args.toMap())

    @Test fun currency() {
        assertEquals(c("currency", "from" to "usd", "to" to "taka"), m("USD to taka"))
        assertEquals(c("currency", "from" to "dollars", "to" to "taka", "amount" to "100"), m("convert 100 dollars to taka"))
        assertEquals(c("currency", "from" to "euro", "to" to "taka", "amount" to "50"), m("how much is 50 euro in taka?"))
        assertEquals(c("currency", "from" to "dollar"), m("what's the dollar rate today"))
        assertEquals(c("currency", "from" to "riyal"), m("riyal rate"))
        assertEquals(c("currency", "from" to "dollars", "to" to "taka", "amount" to "20"), m("how many taka is 20 dollars"))
        assertEquals(c("currency", "from" to "pound", "to" to "taka", "amount" to "100"), m("convert one hundred pound to taka"))
    }

    @Test fun prayer() {
        assertEquals(c("prayer_times"), m("prayer times today"))
        assertEquals(c("prayer_times"), m("what are the prayer times"))
        assertEquals(c("prayer_times", "day" to "tomorrow"), m("namaz time tomorrow"))
        assertEquals(c("prayer_times", "prayer" to "maghrib"), m("when is maghrib"))
        assertEquals(c("prayer_times", "prayer" to "fajr", "day" to "tomorrow"), m("what time is fajr tomorrow?"))
        assertEquals(c("prayer_times", "prayer" to "dhuhr"), m("zohr time"))
        assertEquals(c("prayer_times", "prayer" to "next"), m("next prayer"))
        assertEquals(c("prayer_times", "prayer" to "next"), m("when is the next namaz"))
        assertEquals(c("prayer_times", "prayer" to "sunset"), m("when is sunset"))
    }

    @Test fun noFalseHits() {
        assertNotEquals("currency", m("who won the match")?.tool)
        assertNotEquals("prayer_times", m("sunset")?.tool)
        assertNotEquals("currency", m("play dollar song")?.tool)
        assertEquals("news", m("news")?.tool)
        assertEquals("weather", m("what's the weather")?.tool)
    }
}
