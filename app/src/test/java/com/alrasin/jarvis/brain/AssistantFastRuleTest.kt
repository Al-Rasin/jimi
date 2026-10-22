package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class AssistantFastRuleTest {
    private val now = LocalDateTime.of(2026, 10, 23, 10, 0)

    @Test fun commonAppCommandsUseRulesBeforeModelsWithOneAppLookup() {
        val previous = RuleMatcher.appNameMatches
        try {
            var lookups = 0
            RuleMatcher.appNameMatches = { lookups++; it in setOf("youtube", "you tube") }
            for (text in listOf("open YouTube", "Jimi, please open YouTube.", "could you launch YouTube", "open You Tube")) {
                lookups = 0
                assertNull(RuleMatcher.interrupt(text))
                assertEquals(0, lookups)
                val rule = RuleMatcher.match(text, now)
                assertEquals(text, "open_app", rule?.tool)
                assertEquals(text, rule, Assistant.ruleRouteCall(text, rule, IntentRouter.classify(text, rule)))
                assertEquals(text, 1, lookups)
            }
        } finally {
            RuleMatcher.appNameMatches = previous
        }
    }

    @Test fun commonAlarmsUseRulesBeforeModels() {
        for (text in listOf("set alarm for 3 pm", "set an alarm for three pm", "Jimi, set alarm at 3 p.m.", "wake me up at 3 pm")) {
            val rule = RuleMatcher.match(text, now)
            assertEquals(text, "set_alarm", rule?.tool)
            assertEquals(text, "15:00", rule?.arg("time"))
            assertEquals(text, rule, Assistant.ruleRouteCall(text, rule, IntentRouter.classify(text, rule)))
        }
    }

    @Test fun fastPathKeepsChatAndMultiStepGuards() {
        val call = ToolCall("open_app", mapOf("app" to "youtube"))
        for (text in listOf("don't open YouTube", "open YouTube then tap subscriptions")) {
            assertNull(text, Assistant.ruleRouteCall(text, call, IntentRouter.classify(text, call)))
        }
    }

    @Test fun interruptsStillRecognizeCancelAndPanic() {
        assertEquals("cancel", RuleMatcher.interrupt("Jimi, cancel please")?.tool)
        assertEquals("panic", RuleMatcher.interrupt("stop everything")?.tool)
    }
}
