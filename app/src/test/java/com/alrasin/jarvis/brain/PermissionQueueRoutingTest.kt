package com.alrasin.jarvis.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Permission queue + "what can't you do in X" (ROADMAP §J.7). */
class PermissionQueueRoutingTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @Before fun knownApps() { RuleMatcher.appNameMatches = { it in setOf("whatsapp", "youtube") } }
    @After fun restore() { RuleMatcher.appNameMatches = previousMatcher }

    @Test fun questions() {
        assertEquals("list", RuleMatcher.match("do you have any questions for me")?.arg("action"))
        assertEquals("list", RuleMatcher.match("what do you want to ask me")?.arg("action"))
        assertEquals("yes", RuleMatcher.match("yes learn it")?.arg("action"))
        assertEquals("no", RuleMatcher.match("no skip it")?.arg("action"))
        assertEquals("no", RuleMatcher.match("don't tap it")?.arg("action"))
        assertEquals(IntentRouter.Route.TOOL, IntentRouter.classify("don't learn it").route)
        // Plain yes/no stay with the confirm flow; "skip" stays media.
        assertNull(RuleMatcher.match("yes"))
        assertNotEquals("app_questions", RuleMatcher.match("skip it")?.tool)
    }

    @Test fun limits() {
        val call = RuleMatcher.match("what can't you do in WhatsApp")!!
        assertEquals("app_limits", call.tool)
        assertEquals("whatsapp", call.arg("app"))
        assertEquals(IntentRouter.Route.TOOL, IntentRouter.classify("what can't you do in youtube").route)
        assertNotEquals("app_limits", RuleMatcher.match("what can't you do in my house")?.tool)
    }
}
