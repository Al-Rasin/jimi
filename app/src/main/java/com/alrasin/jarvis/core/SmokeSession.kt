package com.alrasin.jarvis.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only the debug source set creates this context; it never enables a dangerous tool. */
class SmokeSession(val readOnly: Boolean, val forceDryRun: Boolean = false) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SmokeSession> {
        fun allows(call: ToolCall, readOnly: Boolean) = Policy.allows(call, readOnly)
    }
    var route = "fallback"
    var call: ToolCall? = null
    var requestedCall: ToolCall? = null
    var ok = true
    var dryRun = forceDryRun

    fun intercept(call: ToolCall): ToolResult? {
        this.call = call
        if (forceDryRun) {
            dryRun = true
            ok = true
            return ToolResult(true, "Dry run: ${call.tool} was not executed.")
        }
        if (allows(call, readOnly)) return null
        dryRun = true
        ok = !readOnly
        return ToolResult(ok, if (readOnly) "Blocked by smoke read-only policy." else "Dry run: ${call.tool} was not executed.")
    }

    fun record(id: String, heard: String, reply: String, ms: Long, expectAnswer: Boolean? = null,
        deviceTime: String? = null): String {
        val data = linkedMapOf<String, Any?>(
            "id" to id, "heard" to heard, "route" to route, "tool" to call?.tool,
            "args" to call?.args.orEmpty(), "ok" to ok, "reply" to reply.take(600),
            "ms" to ms, "dry_run" to dryRun, "read_only" to readOnly,
            "truncated" to (reply.length > 600),
        )
        if (expectAnswer != null) data["expectAnswer"] = expectAnswer
        if (forceDryRun) {
            data["executed"] = false
            data["requested_args"] = requestedCall?.args.orEmpty()
        }
        if (deviceTime != null) data["device_time"] = deviceTime
        if (Json.write(data).toByteArray(Charsets.UTF_8).size > 3500) {
            data["truncated"] = true
            data["args"] = emptyMap<String, String>()
            if (forceDryRun) data["requested_args"] = emptyMap<String, String>()
            data["reply"] = reply.take(150)
            data["heard"] = heard.take(150)
            data["tool"] = call?.tool?.take(80)
        }
        return Json.write(data)
    }

    private object Policy {
        fun allows(call: ToolCall, readOnly: Boolean): Boolean {
            if (call.tool in setOf("device_status", "read_screen", "read_notes", "list_reminders",
                    "recall_facts", "read_notifications", "notification_summary", "read_messages", "read_list")) return true
            if (readOnly) return false
            return when (call.tool) {
                "toggle" -> call.arg("target") == "flashlight" && call.arg("state") in setOf("on", "off")
                "set_volume" -> call.arg("level")?.toIntOrNull()?.let { it in 0..100 } == true
                "open_app" -> call.arg("app")?.lowercase() == "settings"
                else -> false
            }
        }
    }
}
