package com.alrasin.jarvis.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {
    private fun recipe(say: String, source: String = "card", app: String = "YouTube", name: String = say) = Recipe(
        pkg = "com.google.android.youtube", appName = app, name = name, say = listOf(say),
        params = emptyList(), steps = listOf(RecipeStep("open")), risky = false, source = source,
    )

    @Test fun matchFillsParams() {
        val m = RecipeBook.match("Search lo-fi beats on YouTube.", listOf(recipe("search {query} on youtube")))
        assertNotNull(m)
        assertEquals(mapOf("query" to "lo-fi beats"), m!!.params)
    }

    @Test fun cardRecipeNeedsAppName() {
        val generic = recipe("search for {query}")
        assertNull(RecipeBook.match("search for cats", listOf(generic)))
        assertNotNull(RecipeBook.match("search for cats youtube", listOf(recipe("search for {query} {x}"))))
    }

    @Test fun patternNeedsTwoLiteralWords() {
        assertNull(RecipeBook.match("youtube cats", listOf(recipe("youtube {query}"))))
    }

    @Test fun agentRecipeWinsATie() {
        val card = recipe("play {query} on youtube", name = "card")
        val agent = recipe("play {query} on youtube", source = "agent", name = "agent")
        assertEquals("agent", RecipeBook.match("play jazz on youtube", listOf(card, agent))!!.recipe.name)
    }

    @Test fun longerPatternWins() {
        val short = recipe("play {query} on youtube", name = "short")
        val long = recipe("play {query} on youtube music", name = "long")
        assertEquals("long", RecipeBook.match("play jazz on youtube music", listOf(short, long))!!.recipe.name)
    }

    @Test fun fillReplacesHolesAndKeepsFirstResult() {
        val step = RecipeStep("tap", "{first result}", "{text}", "{x}", listOf(mapOf("type" to "text", "value" to "{text}")))
        val f = RecipeBook.fill(step, mapOf("text" to "hello"))
        assertEquals("{first result}", f.target)
        assertEquals("hello", f.text)
        assertEquals("", f.expect)
        assertEquals("hello", f.selectors.single()["value"])
        assertTrue(RecipeBook.isFirstResult(f.target))
    }

    @Test fun versionRange() {
        assertTrue(RecipeBook.versionOk(5, 0, 0))
        assertTrue(RecipeBook.versionOk(5, 5, 9))
        assertFalse(RecipeBook.versionOk(4, 5, 0))
        assertFalse(RecipeBook.versionOk(10, 0, 9))
    }

    private val box = ScreenElement(index = 1, text = "Title", resourceId = "com.keep:id/title", editable = true)
    private val save = ScreenElement(index = 2, description = "Save")
    private fun done(vararg a: Pair<AgentAction, ScreenElement?>) = a.map { ScreenAgent.Performed(it.first, it.second, "com.keep") }

    @Test fun fromAgentMakesTypedTextAParam() {
        val r = RecipeBook.fromAgent("Add note buy milk in Keep", "com.keep", "Keep", done(
            AgentAction.OpenApp("Keep") to null, AgentAction.Type(1, "buy milk") to box, AgentAction.Tap(2) to save,
            AgentAction.Done("ok") to null,
        ).dropLast(1), risky = false, version = 7)
        assertNotNull(r)
        assertEquals(listOf("add note {text} in keep"), r!!.say)
        assertEquals(listOf("text"), r.params)
        assertEquals(listOf("open", "type", "tap"), r.steps.map { it.action })
        assertEquals("{text}", r.steps[1].text)
        assertEquals("Save", r.steps[2].target)
        assertEquals(7L, r.minVersion)
        val m = RecipeBook.match("add note call mom in keep", listOf(r))
        assertEquals(mapOf("text" to "call mom"), m!!.params)
    }

    @Test fun fromAgentRefusesWhatCannotReplay() {
        val tap = done(AgentAction.OpenApp("Keep") to null, AgentAction.Tap(2) to save)
        assertNull(RecipeBook.fromAgent("note x\nUser clarification: y", "com.keep", "Keep", tap, false, 1))
        assertNull(RecipeBook.fromAgent("open keep", "com.keep", "Keep", done(AgentAction.OpenApp("Keep") to null), false, 1))
        assertNull(RecipeBook.fromAgent("tap it in keep", "com.keep", "Keep", done(AgentAction.Tap(3) to ScreenElement(3)), false, 1))
        assertNull(RecipeBook.fromAgent("ask in keep", "com.keep", "Keep", done(AgentAction.AskUser("?") to null), false, 1))
    }

    private class FakeScreen(var failFirst: Int = 0, var showsAfter: Int = 0) : RecipeScreen {
        val done = mutableListOf<String>()
        var looks = 0
        override suspend fun perform(step: RecipeStep): Boolean {
            done += step.action
            return if (failFirst > 0) { failFirst--; false } else true
        }
        override fun shows(expect: String): Boolean = ++looks > showsAfter
        override suspend fun settle() = Unit
    }

    @Test fun runnerRetriesAFailedStepOnce() = runBlocking {
        val s = FakeScreen(failFirst = 1)
        assertNull(RecipeRunner(s).run(listOf(RecipeStep("tap", "Save"))))
        assertEquals(listOf("tap", "tap"), s.done)
        assertEquals("Step 1 didn't work", RecipeRunner(FakeScreen(failFirst = 2)).run(listOf(RecipeStep("tap", "Save"))))
    }

    @Test fun runnerRedoesOpenButNeverATap() = runBlocking {
        val open = FakeScreen(showsAfter = 2)
        assertNull(RecipeRunner(open).run(listOf(RecipeStep("open", expect = "home"))))
        assertEquals(listOf("open", "open"), open.done)
        val tap = FakeScreen(showsAfter = 5)
        assertEquals("Step 1 didn't work", RecipeRunner(tap).run(listOf(RecipeStep("tap", "Send", expect = "chat"))))
        assertEquals(listOf("tap"), tap.done)
    }

    @Test fun parsesParamsAsJsonOrPairs() {
        assertEquals(mapOf("query" to "jazz"), parseParams("{\"query\":\"jazz\"}"))
        assertEquals(mapOf("a" to "1", "b" to "two"), parseParams("a=1; b=two"))
        assertEquals(emptyMap<String, String>(), parseParams(null))
    }
}
