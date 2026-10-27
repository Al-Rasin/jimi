package com.alrasin.jarvis.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/** "open X in App" and "what can you do in App" go to the map planner (ROADMAP §J.4). */
class AppMapRoutingTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @Before fun knownApps() {
        RuleMatcher.appNameMatches = { it in setOf("whatsapp", "youtube", "settings") }
    }

    @After fun restore() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    @Test fun goInsideApp() {
        val call = RuleMatcher.match("open privacy settings in WhatsApp")!!
        assertEquals("app_go", call.tool)
        assertEquals("whatsapp", call.arg("app"))
        assertEquals("privacy settings", call.arg("target"))
        assertEquals("app_go", RuleMatcher.match("Jarvis, go to the blocked contacts on whatsapp app")?.tool)
        assertEquals("blocked contacts", RuleMatcher.match("take me to blocked contacts in whatsapp")?.arg("target"))
        assertEquals(IntentRouter.Route.TOOL, IntentRouter.classify("open privacy settings in whatsapp").route)
    }

    @Test fun unknownAppIsNotAMapTask() {
        assertNotEquals("app_go", RuleMatcher.match("open the door in my house")?.tool)
        assertEquals("open_app", RuleMatcher.match("open whatsapp")?.tool)
    }

    @Test fun abilities() {
        val call = RuleMatcher.match("what can you do in WhatsApp")!!
        assertEquals("app_abilities", call.tool)
        assertEquals("whatsapp", call.arg("app"))
        assertEquals(IntentRouter.Route.TOOL, IntentRouter.classify("what can you do in youtube").route)
        // #56: plain "what can you do" gives the real list (phone_profile ask=abilities), not fixed chat text.
        assertEquals("phone_profile", RuleMatcher.match("what can you do")?.tool)
    }
}
