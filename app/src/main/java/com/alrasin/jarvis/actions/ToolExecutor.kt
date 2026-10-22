package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.SmokeSession
import kotlin.coroutines.coroutineContext
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SecurityGate (PLAN 4.2 / 12.2): CONFIRM tools need a "yes" first; VOICE_ID tools need a matching voice when locked
 * (and also a "yes"). Returns [Outcome.NeedConfirm] / [Outcome.Denied] instead of running.
 */
sealed interface Outcome {
    data class Done(val result: ToolResult) : Outcome
    data class NeedConfirm(val question: String) : Outcome
    data class Denied(val why: String) : Outcome
}

@Singleton
class ToolExecutor @Inject constructor(private val registry: ToolRegistry) {

    private val gate = ToolSafetyGate()

    fun enterLockdown() = gate.enterLockdown()
    fun observeLockState(locked: Boolean) = gate.observeLockState(locked)

    suspend fun execute(call: ToolCall, ctx: ToolContext, confirmed: Boolean, voiceOk: Boolean?): Outcome {
        val tool = registry.get(call.tool) ?: return Outcome.Done(ToolResult.fail("I don't know how to ${call.tool.replace('_', ' ')} yet."))
        return gate.execute(call, tool.risk, ctx.locked, confirmed, voiceOk) {
            coroutineContext[SmokeSession]?.intercept(call) ?: run(tool, call, ctx)
        }
    }

    private suspend fun run(tool: Tool, call: ToolCall, ctx: ToolContext): ToolResult =
        runCatching { tool.run(call, ctx) }
            .onFailure { JarvisLog.e("tool ${call.tool} failed", it) }
            .getOrElse { ToolResult.fail("Sorry, that failed: ${it.message ?: it.javaClass.simpleName}") }
}

internal class ToolSafetyGate {
    @Volatile private var lockdown = false
    private var sawLocked = false

    @Synchronized fun enterLockdown() {
        lockdown = true
        sawLocked = false
    }

    @Synchronized fun observeLockState(locked: Boolean) {
        if (!lockdown) return
        if (locked) sawLocked = true
        else if (sawLocked) lockdown = false
    }

    suspend fun execute(
        call: ToolCall,
        declaredRisk: Risk,
        locked: Boolean,
        confirmed: Boolean,
        voiceOk: Boolean?,
        run: suspend () -> ToolResult,
    ): Outcome {
        val risk = when {
            declaredRisk != Risk.SAFE -> declaredRisk
            call.tool in setOf("delete_reminder", "forget_fact", "cancel_alarm") -> Risk.CONFIRM
            (locked || lockdown) && call.tool in setOf(
                "read_messages", "read_notes", "list_reminders", "recall_facts",
                "read_screen", "read_notifications", "notification_summary",
            ) -> Risk.VOICE_ID
            else -> Risk.SAFE
        }
        if (risk != Risk.SAFE) {
            if (lockdown) return Outcome.Denied("Panic mode is on. Unlock the phone by hand before I can do that.")
            // null means the optional check is off; the service reports missing/failed verification as false.
            if (locked && voiceOk == false) return Outcome.Denied(
                "I couldn't verify your voice. Voice ID may be unavailable or not enrolled. Unlock the phone by hand to do that.",
            )
            if (!confirmed) return Outcome.NeedConfirm(question(call))
        }
        return Outcome.Done(run())
    }

    private fun question(call: ToolCall): String = when (call.tool) {
        "send_sms", "send_whatsapp" -> "Send \"${call.arg("text")}\" to ${call.arg("contact")}?"
        "call" -> "Call ${call.arg("contact")}?"
        "unlock_phone" -> "Unlock the phone?"
        "clear_notifications" -> "Clear all notifications?"
        "delete_reminder" -> "Delete ${if (call.arg("text") == "all") "all reminders" else "the reminder ${call.arg("text")}"}?"
        else -> "Do ${call.tool.replace('_', ' ')}?"
    }
}
