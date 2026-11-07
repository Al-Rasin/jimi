package com.alrasin.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineStepsTest {
    @Test fun queryDropsOpenAppAndAddsAppName() {
        assertEquals("how to turn off read receipts in WhatsApp Android",
            OnlineSteps.query("Jimi, open whatsapp and turn off read receipts.", "WhatsApp"))
        assertEquals("how to clear cache in chrome Android", OnlineSteps.query("clear cache in chrome", "Chrome"))
        assertEquals("how to enable dark mode Android", OnlineSteps.query("please enable dark mode", ""))
    }

    @Test fun stepsFromNumberedOrBulletList() {
        val answer = """
            Here you go:
            1. Tap the **three dots** menu
            2) Tap Settings
            - Tap Privacy
            Step 4: Turn off Read receipts
        """.trimIndent()
        assertEquals(listOf("Tap the three dots menu", "Tap Settings", "Tap Privacy", "Turn off Read receipts"), OnlineSteps.steps(answer))
    }

    @Test fun noneOrNoListGivesNothing() {
        assertTrue(OnlineSteps.steps("NONE").isEmpty())
        assertTrue(OnlineSteps.steps("I am not sure how to do that.").isEmpty())
        assertEquals(OnlineSteps.MAX_STEPS, OnlineSteps.steps((1..20).joinToString("\n") { "$it. Tap item $it" }).size)
    }

    @Test fun hintAndPrompt() {
        assertEquals("", OnlineSteps.hint(emptyList()))
        val h = OnlineSteps.hint(listOf("Tap Settings", "Tap Privacy"))
        assertTrue(h.endsWith("1. Tap Settings\n2. Tap Privacy"))
        val p = OnlineSteps.prompt("x", "Chrome", listOf("a".repeat(10_000)))
        assertTrue(p.startsWith("App: Chrome\nTask: x"))
        assertTrue(p.length < 6_200)
    }
}
