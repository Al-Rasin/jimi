package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.JarvisLog
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

    suspend fun execute(call: ToolCall, ctx: ToolContext, confirmed: Boolean, voiceOk: Boolean?): Outcome {
        val tool = registry.get(call.tool) ?: return Outcome.Done(ToolResult.fail("I don't know how to ${call.tool.replace('_', ' ')} yet."))
        val risk = riskOf(tool, ctx)
        if (risk == Risk.VOICE_ID && ctx.locked && voiceOk == false) {
            return Outcome.Denied("Sorry, I only do that for my owner while the phone is locked.")
        }
        if (risk != Risk.SAFE && !confirmed) return Outcome.NeedConfirm(question(call))
        return Outcome.Done(run(tool, call, ctx))
    }

    suspend fun run(tool: Tool, call: ToolCall, ctx: ToolContext): ToolResult =
        runCatching { tool.run(call, ctx) }
            .onFailure { JarvisLog.e("tool ${call.tool} failed", it) }
            .getOrElse { ToolResult.fail("Sorry, that failed: ${it.message ?: it.javaClass.simpleName}") }

    /** Anything that sends, calls or unlocks while locked needs the owner's voice (D-011). */
    private fun riskOf(tool: Tool, ctx: ToolContext): Risk =
        if (ctx.locked && tool.risk == Risk.CONFIRM) Risk.VOICE_ID else tool.risk

    private fun question(call: ToolCall): String = when (call.tool) {
        "send_sms", "send_whatsapp" -> "Send \"${call.arg("text")}\" to ${call.arg("contact")}?"
        "call" -> "Call ${call.arg("contact")}?"
        "unlock_phone" -> "Unlock the phone?"
        "clear_notifications" -> "Clear all notifications?"
        "delete_reminder" -> "Delete ${if (call.arg("text") == "all") "all reminders" else "the reminder ${call.arg("text")}"}?"
        else -> "Do ${call.tool.replace('_', ' ')}?"
    }
}
