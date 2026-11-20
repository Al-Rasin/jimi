package com.alrasin.jimi.brain

import org.junit.Assert.assertEquals
import org.junit.Test

/** "stop it" was read as close_app with app "it" (usage log 2026-11-21). */
class StopItRuleTest {
    @Test fun stopItCancelsLikeStop() {
        for (t in listOf("stop", "cancel", "shut up", "stop it", "Stop it.", "stop that", "stop this", "stop it now", "stop it please")) {
            assertEquals(t, "cancel", RuleMatcher.match(t)?.tool)
            assertEquals(t, "cancel", RuleMatcher.interrupt(t)?.tool)
        }
    }

    @Test fun stopAnAppStillClosesIt() {
        val c = RuleMatcher.match("stop youtube")
        assertEquals("close_app", c?.tool)
        assertEquals("youtube", c?.args?.get("app"))
    }
}
