package com.alrasin.jarvis.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenAgentTest {
    private val safe = ScreenSnapshot("settings", listOf(ScreenElement(1, "Dark mode", clickable = true)))
    private class Screen(var value: ScreenSnapshot) : AgentScreen {
        val actions = mutableListOf<AgentAction>()
        override suspend fun snapshot() = value
        override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean { actions += action; return true }
    }
    private fun model(vararg actions: AgentAction) = object : AgentModel {
        override val description = "Fake model"
        var i = 0
        override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>) = actions[(i++).coerceAtMost(actions.lastIndex)]
    }

    @Test fun parsesOneStrictAction() {
        assertEquals(AgentAction.Type(2, "hello\nworld"), AgentAction.parse("""{"action":"type","element":2,"text":"hello\nworld"}"""))
        listOf("""{"action":"tap","element":0}""", """{"action":"tap","element":1.2}""",
            """{"action":"tap","element":1,"extra":"send"}""", """{"action":"back"}{"action":"home"}""",
            """{"action":"scroll","direction":"sideways"}""").forEach { assertNull(it, AgentAction.parse(it)) }
    }

    @Test fun parsesFencedCloudReply() {
        assertEquals(AgentAction.OpenApp("com.facebook.orca"), AgentAction.parse("```json\n{\"action\":\"open_app\",\"package\":\"com.facebook.orca\"}\n```"))
        assertNull(AgentAction.parse("I can't do that."))
    }

    @Test fun compactsAndRedacts() {
        val compact = ScreenSnapshot.compact("app", (1..100).map { ScreenElement(it, "x".repeat(1000), password = true) })
        assertEquals(60, compact.elements.size)
        assertEquals(60, compact.elements.last().index)
        assertFalse(compact.compact().contains("xxxx"))
        assertTrue(compact.compact().contains("[private]"))
    }

    @Test fun protectsRiskyAndUnlabelledControlsAndCredentials() {
        for (label in listOf("Send", "Buy now", "Delete", "Pay", "Confirm purchase", "")) {
            assertNotNull(AgentSafety.confirmation(AgentAction.Tap(1), ScreenSnapshot("app", listOf(ScreenElement(1, label, clickable = true)))))
        }
        assertNull(AgentSafety.confirmation(AgentAction.Tap(1), safe))
        assertNotNull(AgentSafety.confirmation(AgentAction.Tap(1), ScreenSnapshot("app",
            listOf(ScreenElement(1, resourceId = "app:id/image_button_1", clickable = true)))))
        assertNotNull(AgentSafety.confirmation(AgentAction.Tap(1), ScreenSnapshot("app",
            listOf(ScreenElement(1, "Other", clickable = true, safetyUncertain = true)))))
        val otp = ScreenSnapshot("app", listOf(ScreenElement(1, "OTP", editable = true)))
        assertNotNull(AgentSafety.confirmation(AgentAction.Type(1, "123456"), otp))
    }

    @Test fun searchBoxesFieldsAndTabsNeedNoConfirm() {
        fun tap(e: ScreenElement) = AgentSafety.confirmation(AgentAction.Tap(1), ScreenSnapshot("app", listOf(e)))
        assertNull(tap(ScreenElement(1, "Search", clickable = true)))
        assertNull(tap(ScreenElement(1, resourceId = "app:id/search_button", clickable = true)))
        assertNull(tap(ScreenElement(1, "Chats tab", clickable = true)))
        assertNull(tap(ScreenElement(1, "", editable = true, clickable = true)))
        assertNotNull(tap(ScreenElement(1, "Send", editable = true, clickable = true)))
        assertNotNull(tap(ScreenElement(1, "Search", clickable = true, safetyUncertain = true)))
    }

    @Test fun namedAppOpensBeforeTheModel() = runBlocking {
        val screen = Screen(safe)
        val result = ScreenAgent(model(AgentAction.Done("Found it")), screen).run("open messenger and find the search box")
        assertEquals("Found it", result.summary)
        assertEquals(listOf<AgentAction>(AgentAction.OpenApp("messenger")), screen.actions)
    }

    @Test fun loopExecutesThenFinishes() = runBlocking {
        val screen = Screen(safe)
        val result = ScreenAgent(model(AgentAction.Tap(1), AgentAction.Done("Done")), screen).run("dark mode")
        assertEquals("Done", result.summary)
        assertEquals(listOf(AgentAction.Tap(1)), screen.actions)
    }

    @Test fun confirmationCannotActOnChangedScreen() = runBlocking {
        val screen = Screen(ScreenSnapshot("app", listOf(ScreenElement(1, "Send", clickable = true))))
        val agent = ScreenAgent(model(AgentAction.Tap(1)), screen)
        assertTrue(agent.run("reply").needsConfirmation)
        assertTrue(screen.actions.isEmpty())
        screen.value = safe
        assertTrue(agent.confirm(true).summary.contains("changed"))
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun confirmationKeepsDeadline() = runBlocking {
        var clock = 0L
        val screen = Screen(ScreenSnapshot("app", listOf(ScreenElement(1, "Send", clickable = true))))
        val agent = ScreenAgent(model(AgentAction.Tap(1)), screen, now = { clock })
        assertTrue(agent.run("reply").needsConfirmation)
        clock = 60_001
        assertFalse(agent.confirm(true).needsConfirmation)
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun stopBeforeFirstSnapshotPreventsAction() = runBlocking {
        val screen = Screen(safe)
        val agent = ScreenAgent(model(AgentAction.Tap(1)), screen)
        agent.stop()
        assertEquals("Screen task stopped.", agent.run("task").summary)
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun stopDuringModelPreventsAction() = runBlocking {
        val screen = Screen(safe)
        lateinit var agent: ScreenAgent
        val model = object : AgentModel {
            override val description = "Fake"
            override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>): AgentAction {
                agent.stop()
                return AgentAction.Tap(1)
            }
        }
        agent = ScreenAgent(model, screen)
        assertEquals("Screen task stopped.", agent.run("task").summary)
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun limitsSteps() = runBlocking {
        val screen = Screen(safe)
        assertTrue(ScreenAgent(model(AgentAction.Tap(1)), screen, maxSteps = 2).run("task").summary.contains("2 steps"))
        assertEquals(2, screen.actions.size)
    }

    @Test fun confirmationExecutesOnlyOnceAndKeepsBudget() = runBlocking {
        val screen = Screen(ScreenSnapshot("app", listOf(ScreenElement(1, "Send", clickable = true))))
        val agent = ScreenAgent(model(AgentAction.Tap(1)), screen, maxSteps = 1)
        assertTrue(agent.run("reply").needsConfirmation)
        assertTrue(agent.confirm(true).summary.contains("1 steps"))
        agent.confirm(true)
        assertEquals(1, screen.actions.size)
    }

    @Test fun answerResumesOriginalTaskWithinOriginalBudget() = runBlocking {
        var seen = ""
        val model = object : AgentModel {
            override val description = "Fake"
            var calls = 0
            override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>): AgentAction {
                seen = task
                return if (calls++ == 0) AgentAction.AskUser("Which app?") else AgentAction.Done("Finished")
            }
        }
        val agent = ScreenAgent(model, Screen(safe))
        assertTrue(agent.run("open settings").question)
        assertTrue(agent.hasPendingQuestion)
        assertEquals("Finished", agent.answer("Android settings").summary)
        assertTrue(seen.contains("open settings"))
        assertTrue(seen.contains("Android settings"))
        assertFalse(agent.hasPendingQuestion)
    }

    @Test fun unavailableIndexNeverExecutes() = runBlocking {
        val screen = Screen(safe)
        assertTrue(ScreenAgent(model(AgentAction.Tap(2)), screen).run("task").summary.contains("unavailable"))
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun failureStopsLoopAfterOneRetry() = runBlocking {
        var calls = 0
        val screen = object : AgentScreen {
            override suspend fun snapshot() = safe
            override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean { calls++; return false }
        }
        assertTrue(ScreenAgent(model(AgentAction.Tap(1)), screen).run("task").summary.contains("failed"))
        assertEquals(2, calls)
    }

    private fun slowModel() = object : AgentModel {
        override val description = "Fake"
        override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>): AgentAction {
            delay(10_000)
            return AgentAction.Tap(1)
        }
    }

    @Test fun timeoutCancelsModelWithoutAction() = runBlocking {
        val screen = Screen(safe)
        assertTrue(ScreenAgent(slowModel(), screen, timeoutMs = 20).run("task").summary.contains("60 seconds"))
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun stopInterruptsModelImmediately() = runBlocking {
        val screen = Screen(safe)
        val agent = ScreenAgent(slowModel(), screen)
        val result = async { agent.run("task") }
        delay(20)
        agent.stop()
        assertEquals("Screen task stopped.", result.await().summary)
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun cancellationIsNotSwallowed() {
        val model = object : AgentModel {
            override val description = "Fake"
            override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>): AgentAction = throw CancellationException("stop")
        }
        try {
            runBlocking { ScreenAgent(model, Screen(safe)).run("task") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
