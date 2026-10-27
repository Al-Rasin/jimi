package com.alrasin.jarvis.actions

import com.alrasin.jarvis.agent.Skills
import com.alrasin.jarvis.brain.Persona
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
        val wasLocked = ctx.locked
        return gate.execute(call, tool.risk, ctx.locked, confirmed, voiceOk) {
            coroutineContext[SmokeSession]?.intercept(call)
                ?: unlockFirst(call, ctx, voiceOk)
                ?: run(tool, call, ctx).also { if (wasLocked && needsScreen(call)) relock(ctx) }
        }
    }

    /** ROADMAP "Unlock for every screen task": unlock with the saved PIN only after the owner's voice matched,
     * never for payment/banking apps, then lock again in [execute] once the task is done. */
    private suspend fun unlockFirst(call: ToolCall, ctx: ToolContext, voiceOk: Boolean?): ToolResult? {
        val isPaymentApp = call.tool in setOf("open_app", "app_go", "search_in_app", "run_recipe") &&
            call.arg("app")?.let { registry.apps.find(it)?.pkg }?.let(PhoneProfile::isPaymentPackage) == true
        return when (unlockNeed(call, ctx.locked, voiceOk, isPaymentApp)) {
            Unlock.NONE -> null
            Unlock.PAYMENT_APP -> ToolResult.fail(
                "${Persona.ADDRESS}, I won't unlock the phone for a banking or payment app. Please unlock it yourself.",
            )
            Unlock.VOICE_ID_OFF -> ToolResult.fail(
                "${Persona.ADDRESS}, the phone is locked. Turn on voice ID so I can unlock it with your PIN, or unlock it yourself.",
            )
            Unlock.VOICE_MISMATCH -> ToolResult.fail("${Persona.ADDRESS}, please unlock the phone yourself first.")
            Unlock.UNLOCK -> {
                val unlock = registry.get("unlock_phone") ?: return ToolResult.fail("I can't unlock the phone.")
                val r = run(unlock, ToolCall("unlock_phone"), ctx)
                JarvisLog.i("auto unlock for ${call.tool}: ${if (r.ok) "ok" else "failed"}")
                if (r.ok) null else r
            }
        }
    }

    private suspend fun relock(ctx: ToolContext) {
        val lock = registry.get("lock_phone") ?: return
        val r = run(lock, ToolCall("lock_phone"), ctx)
        JarvisLog.i("re-lock after screen task: ${if (r.ok) "ok" else "failed"}")
    }

    private suspend fun run(tool: Tool, call: ToolCall, ctx: ToolContext): ToolResult =
        runCatching { tool.run(call, ctx) }
            .onFailure { JarvisLog.e("tool ${call.tool} failed", it) }
            .getOrElse { ToolResult.fail("Sorry, ${call.tool.replace('_', ' ')} didn't work this time.") }   // cause is in the log
}

internal enum class Unlock { NONE, UNLOCK, VOICE_ID_OFF, VOICE_MISMATCH, PAYMENT_APP }

/** Tools that drive another app's screen and so cannot work behind the lock screen. */
internal fun needsScreen(call: ToolCall): Boolean = when (call.tool) {
    "send_whatsapp", "open_app", "app_go", "read_screen", "tap", "type_text", "scroll",
    "search_in_app", "open_result", "tap_named", "go_tab", "read_list", "toggle_named", "run_recipe" -> true
    "send_sms" -> call.arg("app").equals("messenger", ignoreCase = true)
    "call" -> call.arg("app")?.lowercase() in setOf("whatsapp", "messenger")
    else -> false
}

internal fun unlockNeed(call: ToolCall, locked: Boolean, voiceOk: Boolean?, isPaymentApp: Boolean = false): Unlock = when {
    !locked || !needsScreen(call) -> Unlock.NONE
    isPaymentApp -> Unlock.PAYMENT_APP
    voiceOk == true -> Unlock.UNLOCK
    voiceOk == null -> Unlock.VOICE_ID_OFF
    else -> Unlock.VOICE_MISMATCH
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
            call.tool == "run_recipe" && call.arg("risky") == "true" -> Risk.CONFIRM
            call.tool in setOf("tap_named", "toggle_named", "open_result") &&
                Skills.isRisky(call.arg("name") ?: call.arg("query").orEmpty()) -> Risk.CONFIRM
            (locked || lockdown) && call.tool in setOf(
                "read_messages", "read_chat", "read_notes", "list_reminders", "recall_facts",
                "read_screen", "read_list", "read_notifications", "notification_summary",
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

    private fun question(call: ToolCall): String {
        fun arg(name: String) = call.arg(name)?.let(::spoken)
        val contact = arg("contact_name") ?: arg("contact") ?: "that contact"
        val app = when (call.arg("app")?.lowercase()) {
            "whatsapp" -> " on WhatsApp"
            "messenger" -> " on Messenger"
            else -> ""
        }
        val action = when (call.tool) {
            "send_sms", "send_whatsapp" -> {
                val message = arg("text")?.let { "\"$it\"" } ?: "this message"
                val channel = if (call.tool == "send_whatsapp") " on WhatsApp" else app
                "Send $message to $contact$channel"
            }
            "call" -> {
                val verb = if (app.isNotEmpty() && call.arg("video").equals("true", ignoreCase = true)) "Video call" else "Call"
                "$verb $contact$app"
            }
            "call_back" -> "Call back the last caller"
            "redial" -> "Call the last number again"
            "unlock_phone" -> "Unlock your phone"
            "clear_notifications" -> "Clear all your notifications"
            "cancel_alarm" -> arg("time")?.let(::spokenClock)?.let { "Turn off your $it alarm" } ?: "Cancel all your alarms and timers"
            "delete_reminder" -> when (val text = arg("text")) {
                "all" -> "Delete all your reminders"
                null -> "Delete that reminder"
                else -> "Delete your reminder \"$text\""
            }
            "forget_fact" -> arg("text")?.let { "Forget \"$it\"" } ?: "Forget that fact"
            "read_messages" -> "Read your latest messages"
            "read_chat" -> "Read $contact's last messages$app"
            "read_notes" -> "Read your latest notes"
            "list_reminders" -> "Read your pending reminders"
            "recall_facts" -> "Read what I remember about you"
            "read_screen" -> "Read your screen"
            "read_list" -> "Read the list on your screen"
            "run_recipe" -> "Run \"${arg("name") ?: "that"}\" in ${arg("app") ?: "the app"}"
            "tap_named", "toggle_named", "open_result" -> "Tap \"${arg("name") ?: arg("query") ?: "that"}\""
            "read_notifications" -> "Read your notifications"
            "notification_summary" -> "Summarize your recent notifications"
            "undo_notification_clear" -> "Restore your last cleared notifications as copies"
            "explain_notification_clear" -> "Explain why I cleared those notifications"
            "search_notification_history" -> arg("query")?.let { "Search notification history for \"$it\"" }
                ?: "Search your notification history"
            else -> spoken(call.tool).takeIf { it.isNotBlank() }?.let { "Go ahead with $it" }
                ?: "Should I go ahead"
        }
        return "$action, ${Persona.ADDRESS}?"
    }

    private fun spoken(text: String): String = text.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
}

/** "07:00" → "7:00 AM"; null when not HH:mm. */
private fun spokenClock(hhmm: String): String? =
    runCatching { java.time.LocalTime.parse(hhmm.padStart(5, '0')).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)) }.getOrNull()
