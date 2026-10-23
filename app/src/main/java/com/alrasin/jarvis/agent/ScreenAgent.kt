package com.alrasin.jarvis.agent

import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

interface AgentModel {
    val description: String
    suspend fun next(task: String, snapshot: ScreenSnapshot, history: List<String>): AgentAction
}

interface AgentScreen {
    suspend fun snapshot(): ScreenSnapshot
    suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean
}

data class AgentResult(val summary: String, val needsConfirmation: Boolean = false, val question: Boolean = false)

class ScreenAgent(
    private val model: AgentModel,
    private val screen: AgentScreen,
    private val onStep: (String) -> Unit = {},
    private val maxSteps: Int = 12,
    private val timeoutMs: Long = 60_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var stopped = false
    @Volatile private var activeJob: Job? = null
    private class Stopped : CancellationException("Screen task stopped")
    private var task = ""
    private var started = 0L
    private var steps = 0
    private val history = mutableListOf<String>()
    private data class Pending(val action: AgentAction, val snapshot: ScreenSnapshot)
    private var pending: Pending? = null
    val hasPendingConfirmation: Boolean get() = pending != null
    var hasPendingQuestion: Boolean = false
        private set

    fun stop() { stopped = true; pending = null; hasPendingQuestion = false; activeJob?.cancel(Stopped()) }

    suspend fun run(task: String): AgentResult {
        if (stopped) return limitResult()
        this.task = task
        pending = null
        hasPendingQuestion = false
        steps = 0
        started = now()
        history.clear()
        onStep(model.description)
        return bounded { openNamedApp(); loop() }
    }

    /** "open messenger and find …": Jimi opens the app itself; cloud models do not know package names. */
    private suspend fun openNamedApp() {
        val app = START.find(task.trim())?.groupValues?.get(1) ?: return
        val action = AgentAction.OpenApp(app)
        if (!screen.perform(action, ScreenSnapshot("", emptyList()))) return
        steps++
        "Step $steps: ${action.label()}".also { onStep(it); history += it }
        settle()
    }

    /** Waits (max ~4 s) until two looks at the screen match, so a loading app does not change under a tap. */
    private suspend fun settle() {
        var last = screen.snapshot()
        repeat(8) {
            delay(500)
            val now = screen.snapshot()
            if (now == last && now.elements.isNotEmpty()) return
            last = now
        }
    }

    companion object {
        private val START = Regex("^(?:please )?(?:open|launch|start|go to) (?:the |my )?(.+?)(?: app)?(?:,)? (?:and(?: then)?|then) .+$", RegexOption.IGNORE_CASE)
    }

    suspend fun answer(text: String): AgentResult {
        if (!hasPendingQuestion) return AgentResult("There is no screen question to answer.")
        hasPendingQuestion = false
        task += "\nUser clarification: ${text.take(2000)}"
        return bounded { loop() }
    }

    suspend fun confirm(voiceConfirmed: Boolean): AgentResult {
        val saved = pending ?: return AgentResult("There is no screen action to confirm.")
        pending = null
        if (!voiceConfirmed) { stop(); return AgentResult("Screen task cancelled.") }
        return bounded {
            val fresh = screen.snapshot()
            if (fresh != saved.snapshot) return@bounded AgentResult("The screen changed. Please ask again.")
            if (!allowed()) return@bounded limitResult()
            if (!execute(saved.action, fresh)) return@bounded AgentResult("The screen action failed. Please ask again.")
            loop()
        }
    }

    private suspend fun bounded(block: suspend () -> AgentResult): AgentResult {
        if (!allowed()) return limitResult()
        return try {
            withTimeoutOrNull((timeoutMs - (now() - started)).coerceAtLeast(1)) {
                activeJob = currentCoroutineContext()[Job]
                if (stopped) throw Stopped()
                block()
            }
                ?: AgentResult("Stopped after 60 seconds. The task may be incomplete.").also { pending = null }
        } catch (_: Stopped) {
            AgentResult("Screen task stopped.")
        } catch (e: CancellationException) {
            stopped = true
            pending = null
            throw e
        } catch (e: Exception) {
            JarvisLog.e("screen agent failed: ${e.message}")
            pending = null
            AgentResult("I could not continue the screen task. Please try again.")
        } finally {
            activeJob = null
            if (!allowed()) { pending = null; hasPendingQuestion = false }
        }
    }

    private fun allowed() = !stopped && now() - started < timeoutMs
    private fun limitResult() = AgentResult(if (stopped) "Screen task stopped." else "Stopped after 60 seconds. The task may be incomplete.")

    private suspend fun loop(): AgentResult {
        var failed = 0
        while (steps < maxSteps.coerceAtMost(12)) {
            if (!allowed()) return limitResult()
            val snapshot = screen.snapshot()
            if (!allowed()) return limitResult()
            val action = model.next(task, snapshot, history.toList())
            if (!allowed()) return limitResult()
            steps++
            val line = "Step $steps: ${action.label()}"
            onStep(line)
            history += line
            when (action) {
                is AgentAction.Done -> return AgentResult(action.summary)
                is AgentAction.AskUser -> {
                    hasPendingQuestion = true
                    return AgentResult(action.question, question = true)
                }
                else -> Unit
            }
            if (!validTarget(action, snapshot)) return AgentResult("The model selected an unavailable screen element. Please ask again.")
            val question = AgentSafety.confirmation(action, snapshot)
            if (question != null) {
                pending = Pending(action, snapshot)
                return AgentResult(question, needsConfirmation = true)
            }
            if (!execute(action, snapshot)) {
                // usually the screen moved between looking and tapping; look again once before giving up
                if (stopped || failed++ >= 1) return AgentResult(if (stopped) "Screen task stopped." else "The screen action failed. Please ask again.")
                history += "Step $steps failed: the screen changed"
                settle()
            }
        }
        return AgentResult("Stopped after $steps steps. The task may be incomplete.")
    }

    private fun validTarget(action: AgentAction, snapshot: ScreenSnapshot): Boolean = when (action) {
        is AgentAction.Tap -> snapshot.elements.any { it.index == action.element && it.clickable }
        is AgentAction.Type -> snapshot.elements.any { it.index == action.element && it.editable }
        else -> true
    }

    private suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot): Boolean {
        if (!allowed()) return false
        if (action == AgentAction.Wait) { delay(500); return allowed() }
        return screen.perform(action, snapshot).also { if (it && allowed()) delay(250) }
    }
}
