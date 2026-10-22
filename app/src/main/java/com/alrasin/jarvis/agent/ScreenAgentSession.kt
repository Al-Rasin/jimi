package com.alrasin.jarvis.agent

import android.content.Context
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.ToolSafetyGate
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenAgentSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: AgentModels,
) {
    private val gate = ToolSafetyGate()
    @Volatile private var agent: ScreenAgent? = null
    private var model: AgentModel? = null
    private var panel: AgentPanel? = null
    val hasPendingConfirmation get() = agent?.hasPendingConfirmation == true
    val hasPendingQuestion get() = agent?.hasPendingQuestion == true

    fun observeLockState(locked: Boolean) = gate.observeLockState(locked)
    fun enterLockdown() { stop(); gate.enterLockdown() }
    fun stop() { agent?.stop() }

    suspend fun run(task: String, locked: Boolean, voiceOk: Boolean?, speak: (String) -> Unit): AgentResult =
        guarded(locked, voiceOk) {
            stop()
            val backend = models.create()
            model = backend
            panel = AgentPanel(context, ::stop)
            val next = ScreenAgent(backend, AccessibilityAgentScreen(context)) { step ->
                JarvisLog.i("screen agent: $step")
                panel?.show(step)
            }
            agent = next
            speak(backend.description)
            try { next.run(task) } finally { release() }
        }

    suspend fun confirm(voiceConfirmed: Boolean, locked: Boolean, voiceOk: Boolean?): AgentResult =
        guarded(locked, voiceOk) {
            panel = AgentPanel(context, ::stop)
            try { agent?.confirm(voiceConfirmed) ?: AgentResult("There is no screen action to confirm.") }
            finally { release() }
        }

    suspend fun answer(text: String, locked: Boolean, voiceOk: Boolean?): AgentResult =
        guarded(locked, voiceOk) {
            panel = AgentPanel(context, ::stop)
            try { agent?.answer(text) ?: AgentResult("Please ask for the screen task again.") }
            finally { release() }
        }

    private suspend fun guarded(locked: Boolean, voiceOk: Boolean?, block: suspend () -> AgentResult): AgentResult {
        var result: AgentResult? = null
        val outcome = gate.execute(ToolCall("screen_agent"), Risk.VOICE_ID, locked, true, voiceOk) {
            block().also { result = it }
            ToolResult.ok("Screen agent finished.")
        }
        return result ?: AgentResult((outcome as? Outcome.Denied)?.why ?: "Screen access was refused.")
    }

    private fun release() {
        panel?.close()
        panel = null
        (model as? AutoCloseable)?.close()
    }
}
