package com.alrasin.jarvis.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiffTest {
    private fun snap(pkg: String, vararg labels: String) =
        ScreenSnapshot(pkg, labels.mapIndexed { i, l -> ScreenElement(i + 1, l, clickable = true) })

    @Test fun saysWhatAppearedAndWent() {
        val d = ScreenDiff.describe(snap("app", "Chats", "Search"), snap("app", "Chats", "Rahim", "Karim"))
        assertTrue(d, d.contains("New: \"Rahim\", \"Karim\""))
        assertTrue(d, d.contains("Gone: \"Search\""))
    }

    @Test fun saysNothingChangedAndNewApp() {
        assertEquals("Nothing changed on the screen.", ScreenDiff.describe(snap("app", "A"), snap("app", "A")))
        assertEquals("Now in com.other.", ScreenDiff.describe(snap("app", "A"), snap("com.other", "A")))
    }

    @Test fun neverShowsPasswordText() {
        val before = ScreenSnapshot("app", emptyList())
        val after = ScreenSnapshot("app", listOf(ScreenElement(1, "hunter2", password = true)))
        assertFalse(ScreenDiff.describe(before, after).contains("hunter2"))
    }

    @Test fun checksTapAndTyping() {
        val s = ScreenSnapshot("app", listOf(ScreenElement(1, "", editable = true)))
        assertTrue(ScreenDiff.noEffect(AgentAction.Tap(1), s, s))
        assertFalse(ScreenDiff.noEffect(AgentAction.Wait, s, s))
        val typed = ScreenSnapshot("app", listOf(ScreenElement(1, "Hello there", editable = true)))
        assertFalse(ScreenDiff.typedMissing(AgentAction.Type(1, "hello there"), s, typed))
        assertTrue(ScreenDiff.typedMissing(AgentAction.Type(1, "bye"), s, typed))
    }

    @Test fun agentSeesResultAndStopsWhenTapsDoNothing() = runBlocking {
        val seen = mutableListOf<List<String>>()
        val model = object : AgentModel {
            override val description = "Fake"
            override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>): AgentAction {
                seen += history
                return AgentAction.Tap(1)
            }
        }
        val screen = object : AgentScreen {
            override suspend fun snapshot() = snap("app", "Chats")
            override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot) = true
        }
        val result = ScreenAgent(model, screen).run("find rahim")
        assertTrue(result.summary, result.summary.contains("not changing the screen"))
        assertTrue(seen[1].any { it.startsWith("Step 1 result: Nothing changed") && it.contains("did not work") })
        assertEquals(3, seen.size)
    }
}
