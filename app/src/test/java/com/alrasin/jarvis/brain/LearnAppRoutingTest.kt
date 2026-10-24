package com.alrasin.jarvis.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** "learn this app" / "learn Mi Remote" go to the app explorer (ROADMAP §J.5). */
class LearnAppRoutingTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @Before fun knownApps() {
        RuleMatcher.appNameMatches = { it in setOf("mi remote", "whatsapp") }
    }

    @After fun restore() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    @Test fun learnThisApp() {
        val call = RuleMatcher.match("learn this app")!!
        assertEquals("learn_app", call.tool)
        assertNull(call.arg("app"))
        assertEquals("learn_app", RuleMatcher.match("Jarvis, explore the current app")?.tool)
    }

    @Test fun learnNamedApp() {
        val call = RuleMatcher.match("learn Mi Remote")!!
        assertEquals("learn_app", call.tool)
        assertEquals("mi remote", call.arg("app"))
        assertEquals("whatsapp", RuleMatcher.match("explore the whatsapp app")?.arg("app"))
    }

    @Test fun unknownThingIsNotLearn() {
        assertNotEquals("learn_app", RuleMatcher.match("learn spanish")?.tool)
    }
}
