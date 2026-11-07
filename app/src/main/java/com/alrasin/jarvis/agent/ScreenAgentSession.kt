package com.alrasin.jarvis.agent

import android.content.Context
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.ToolSafetyGate
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.ui.AssistantActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenAgentSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: AgentModels,
    private val store: RecipeStore,
    private val online: OnlineHelp,
) {
    private val gate = ToolSafetyGate()
    @Volatile private var agent: ScreenAgent? = null
    private var model: AgentModel? = null
    private var panel: AgentPanel? = null
    @Volatile var running = false
        private set
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
            val first = newAgent(backend)
            acting {
                speak(backend.description)
                val result = first.run(task)
                if (first.stuck && agent === first) retryWithOnlineSteps(task, first, backend, speak) ?: result else result
            }.also { agent?.let { remember(it) } }
        }

    private fun newAgent(backend: AgentModel) = ScreenAgent(backend, AccessibilityAgentScreen(context), onStep = { step ->
        JarvisLog.i("screen agent: $step")
        panel?.show(step)
    }).also { agent = it }

    /** §G.4: the agent got lost, so look up the steps online and try once more with them. Null = no lookup / no steps. */
    private suspend fun retryWithOnlineSteps(task: String, stuck: ScreenAgent, backend: AgentModel, speak: (String) -> Unit): AgentResult? {
        if (!online.available()) return null
        val pkg = stuck.performed.map { it.pkg }.firstOrNull { it.isNotBlank() && it != context.packageName }.orEmpty()
        val appName = runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault("")
        panel?.show("Looking up the steps online…")
        speak("I'm stuck. Let me look up how to do it online.")
        val steps = online.lookup(task, appName, pkg)
        if (steps.isEmpty() || stuck.wasStopped) return null
        panel?.show("Found ${steps.size} steps online. Trying again.")
        return newAgent(backend).run(task, OnlineSteps.hint(steps))
    }

    suspend fun confirm(voiceConfirmed: Boolean, locked: Boolean, voiceOk: Boolean?): AgentResult =
        guarded(locked, voiceOk) {
            panel = AgentPanel(context, ::stop)
            acting { agent?.confirm(voiceConfirmed) ?: AgentResult("There is no screen action to confirm.") }
                .also { agent?.let { remember(it, confirmed = true) } }
        }

    suspend fun answer(text: String, locked: Boolean, voiceOk: Boolean?): AgentResult =
        guarded(locked, voiceOk) {
            panel = AgentPanel(context, ::stop)
            acting { agent?.answer(text) ?: AgentResult("Please ask for the screen task again.") }
                .also { agent?.let { remember(it) } }
        }

    private suspend fun acting(block: suspend () -> AgentResult): AgentResult {
        running = true
        return try {
            withContext(Dispatchers.Main.immediate) { AssistantActivity.hideForScreenAgent() }
            delay(150) // Let the activity window disappear before reading the app below.
            block()
        } finally {
            try { release() } finally { running = false }
        }
    }

    private suspend fun guarded(locked: Boolean, voiceOk: Boolean?, block: suspend () -> AgentResult): AgentResult {
        var result: AgentResult? = null
        val outcome = gate.execute(ToolCall("screen_agent"), Risk.VOICE_ID, locked, true, voiceOk) {
            block().also { result = it }
            ToolResult.ok("Screen agent finished.")
        }
        return result ?: AgentResult((outcome as? Outcome.Denied)?.why ?: "Screen access was refused.")
    }

    /** A finished task becomes a recipe, so the same command next time runs with no AI (ROADMAP #9). */
    private suspend fun remember(agent: ScreenAgent, confirmed: Boolean = false) {
        if (!agent.succeeded) return
        val done = agent.performed
        val pkg = done.map { it.pkg }.firstOrNull { it.isNotBlank() && it != context.packageName } ?: return
        if (PhoneProfile.isPaymentPackage(pkg) || done.any { it.element?.password == true }) return
        val risky = confirmed || done.any { p -> p.element?.let { Skills.isRisky(it.text.ifBlank { it.description }) } == true }
        runCatching {
            val pm = context.packageManager
            val version = pm.getPackageInfo(pkg, 0).longVersionCode
            val name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            RecipeBook.fromAgent(agent.command, pkg, name, done, risky, version)?.let { store.save(it) }
        }.onFailure { JarvisLog.i("recipe not saved: ${it.message}") }
    }

    private fun release() {
        panel?.close()
        panel = null
        (model as? AutoCloseable)?.close()
    }
}
