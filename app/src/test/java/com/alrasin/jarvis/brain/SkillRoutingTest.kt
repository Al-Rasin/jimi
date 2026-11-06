package com.alrasin.jarvis.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** General app skills (ROADMAP §H.4) route without the LLM. */
class SkillRoutingTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @Before fun knownApps() {
        RuleMatcher.appNameMatches = { it in setOf("daraz", "youtube", "settings", "google", "messenger") }
    }

    @After fun restore() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    @Test fun searchInApp() {
        val call = RuleMatcher.match("search red shoes in Daraz")!!
        assertEquals("search_in_app", call.tool)
        assertEquals("red shoes", call.arg("query"))
        assertEquals("daraz", call.arg("app"))
        assertEquals("search_in_app", RuleMatcher.match("find lofi music on youtube app")?.tool)
    }

    @Test fun findInApp() {
        for (text in listOf("open messenger and find rahim", "open messenger and search for rahim", "open messenger then open chat with rahim")) {
            val call = RuleMatcher.match(text)!!
            assertEquals(text, "find_in_app", call.tool)
            assertEquals("rahim", call.arg("query"))
            assertEquals("messenger", call.arg("app"))
        }
        assertEquals("find_in_app", RuleMatcher.match("find rahim on messenger")?.tool)
        assertNotEquals("find_in_app", RuleMatcher.match("open my bag and find keys")?.tool)
        assertTrue(com.alrasin.jarvis.agent.AgentRouting.acceptsSingleTool("open messenger and find rahim", "find_in_app"))
    }

    @Test fun webAndUnknownAppStayWeb() {
        assertEquals("web_search", RuleMatcher.match("search cricket score on google")?.tool)
        assertEquals("web_search", RuleMatcher.match("search best phones")?.tool)
        assertNotEquals("search_in_app", RuleMatcher.match("search keys in my bag")?.tool)
    }

    @Test fun openResult() {
        assertEquals("open_result", RuleMatcher.match("open the first result")?.tool)
        val call = RuleMatcher.match("open result lofi girl radio")!!
        assertEquals("open_result", call.tool)
        assertEquals("lofi girl radio", call.arg("query"))
    }

    @Test fun tab() {
        val call = RuleMatcher.match("go to the calls tab")!!
        assertEquals("go_tab", call.tool)
        assertEquals("calls", call.arg("name"))
        assertEquals("go_tab", RuleMatcher.match("open library tab")?.tool)
    }

    @Test fun readList() {
        assertEquals("read_list", RuleMatcher.match("read the list")?.tool)
        assertEquals("read_list", RuleMatcher.match("read the results")?.tool)
    }

    @Test fun toggleNamed() {
        val call = RuleMatcher.match("turn on the dark theme switch")!!
        assertEquals("toggle_named", call.tool)
        assertEquals("dark theme", call.arg("name"))
        assertEquals("on", call.arg("state"))
        assertEquals("off", RuleMatcher.match("turn the auto backup option off")?.arg("state"))
        assertEquals("toggle", RuleMatcher.match("turn on wifi")?.tool)
    }

    @Test fun plainTapUnchanged() {
        assertEquals("tap", RuleMatcher.match("tap on send button")?.tool)
    }
}
