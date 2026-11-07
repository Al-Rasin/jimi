package com.alrasin.jarvis.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "and / then" task chains (ROADMAP §H.1). */
class TaskChainTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @Before fun knownApps() {
        RuleMatcher.appNameMatches = { it in setOf("youtube", "messenger", "daraz", "settings", "camera", "tom and jerry") }
    }

    @After fun restore() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    private fun plan(text: String) = TaskChain.plan(text) { RuleMatcher.match(it) }

    private fun route(text: String): TaskChain.Plan? {
        val rule = RuleMatcher.match(text)
        return Assistant.chainPlan(text, rule, IntentRouter.classify(text, rule))
    }

    @Test fun splitsOnAndThenAfterThat() {
        assertEquals(listOf("turn on wifi", "open youtube"), TaskChain.split("turn on wifi and then open youtube").map { it.second })
        assertEquals(listOf("a", "b", "c"), TaskChain.split("first a, then b after that c.").map { it.second })
        assertEquals(listOf("one task"), TaskChain.split("one task").map { it.second })
    }

    @Test fun allKnownStepsRunInOrder() {
        val p = route("turn on wifi and then open youtube")!!
        assertTrue(p.complete)
        assertEquals(listOf("toggle", "open_app"), p.steps.map { it.call!!.tool })
        assertEquals("youtube", p.steps[1].call!!.arg("app"))
    }

    @Test fun threeSteps() {
        val p = plan("turn on bluetooth, set volume to 5 and then open camera")!!
        assertTrue(p.complete)
        assertEquals(listOf("toggle", "set_volume", "open_app"), p.steps.map { it.call!!.tool })
    }

    @Test fun appCarriesOver() {
        val p = plan("open daraz and then search red shoes")!!
        assertEquals(listOf("open_app", "search_in_app"), p.steps.map { it.call!!.tool })
        assertEquals("daraz", p.steps[1].call!!.arg("app"))
        assertEquals("red shoes", p.steps[1].call!!.arg("query"))
    }

    @Test fun namesWithAndStayWhole() {
        assertNull(plan("open tom and jerry"))
        val p = plan("turn off wifi and then open tom and jerry")!!
        assertEquals(2, p.steps.size)
        assertEquals("tom and jerry", p.steps[1].call!!.arg("app"))
    }

    @Test fun wholeSentenceRulesWin() {
        // One tool already owns these "and"s.
        assertNull(route("open messenger and find rahim"))
        assertNull(route("remind me to buy milk and eggs at 6 pm"))
        assertNull(route("text rahim I am coming and bring the keys"))
    }

    @Test fun partialSaysWhatItCanAndCant() {
        val p = plan("turn on wifi and then order a pizza")!!
        assertFalse(p.complete)
        assertEquals(listOf("turn on wifi"), p.known.map { it.text })
        assertEquals(listOf("order a pizza"), p.unknown.map { it.text })
        val say = TaskChain.partialReply(p)
        assertTrue(say, say.startsWith("I can turn on wifi, but not order a pizza."))
    }

    @Test fun tooManyStepsIsNotAChain() {
        assertNull(plan("open youtube and open camera and open settings and open daraz and open messenger and open youtube and open camera"))
    }

    @Test fun chatQuestionIsNotAChain() {
        assertNull(route("what is the difference between a cat and a dog"))
    }
}
