package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.agent.appmap.ScreenStructure.RawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Can't do" list + permission queue (ROADMAP §J.7). */
class AppAskQueueTest {
    private fun button(desc: String) =
        RawNode(desc = desc, className = "android.widget.Button", clickable = true)

    @Test fun unsureButtonGoesToQueue() {
        val plan = ExplorePolicy.plan(listOf(button("my"), button("add")), "com.app.MainActivity")
        assertEquals(listOf("my"), plan.targets.map { it.element.label })
        assertTrue(plan.ask.any { it.label == "add" })
    }

    @Test fun approvedButtonIsTapped() {
        val plan = ExplorePolicy.plan(listOf(button("my"), button("add")), "com.app.MainActivity", approved = setOf("add"))
        assertTrue(plan.targets.any { it.element.label == "add" })
        assertFalse(plan.ask.any { it.label == "add" })
    }

    @Test fun riskyButtonIsNeverAsked() {
        val plan = ExplorePolicy.plan(listOf(button("send"), button("delete")), "com.app.MainActivity")
        assertTrue(plan.ask.isEmpty())
    }

    @Test fun questionText() {
        val ask = AppAsk(pkg = "com.whatsapp", label = "add", role = "button", screen = "HomeActivity", status = AppAskQueue.PENDING, createdAt = 0)
        assertEquals("May I tap \"add\" on Home in WhatsApp to learn it? Say yes learn it, or no skip it.",
            AppAskQueue.question("WhatsApp", ask))
        assertEquals("Chat Settings", AppAskQueue.pretty("ChatSettingsFragment"))
    }

    @Test fun limitsText() {
        assertEquals("bKash is a payment app. I only read it, I never tap in it.",
            AppAskQueue.limits("bKash", emptyList(), readOnly = true, skipped = false, denied = emptyList(), riskyCount = 0))
        assertEquals("I know nothing I can't do in Notes yet.",
            AppAskQueue.limits("Notes", emptyList(), readOnly = false, skipped = false, denied = emptyList(), riskyCount = 0))
        assertEquals("In WhatsApp I can't tap 2 risky buttons like send or delete without asking you; tap \"add\", you said no.",
            AppAskQueue.limits("WhatsApp", emptyList(), readOnly = false, skipped = false, denied = listOf("add"), riskyCount = 2))
    }
}
