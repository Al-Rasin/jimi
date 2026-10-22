package com.alrasin.jarvis.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAgentStopStateTest {
    @Test fun stoppingDuringStepDoesNotLeaveAConfirmation() = runBlocking {
        val snapshot = ScreenSnapshot("app", listOf(ScreenElement(1, "Send", clickable = true)))
        val screen = object : AgentScreen {
            override suspend fun snapshot() = snapshot
            override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean =
                error("Stopped task must not perform an action")
        }
        val model = object : AgentModel {
            override val description = "Fake model"
            override suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>) =
                AgentAction.Tap(1)
        }
        lateinit var agent: ScreenAgent
        agent = ScreenAgent(model, screen, onStep = { if (it.startsWith("Step ")) agent.stop() })

        assertTrue(agent.run("Send a message").summary.contains("stopped"))
        assertFalse(agent.hasPendingConfirmation)
        assertFalse(agent.hasPendingQuestion)
    }
}
