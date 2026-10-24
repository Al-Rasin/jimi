package com.alrasin.jarvis.core

import android.app.KeyguardManager
import android.content.Context

/** ARCHITECTURE §4. VOICE_ID = needs speaker verification when the phone is locked. */
enum class Risk { SAFE, CONFIRM, VOICE_ID }

/** Same format for rules, LLM, cloud and training data: {"tool": "set_alarm", "args": {"time": "07:00"}}. */
data class ToolCall(val tool: String, val args: Map<String, String> = emptyMap()) {
    fun arg(name: String): String? = args[name]?.takeIf { it.isNotBlank() }
    fun toJson(): String = Json.write(mapOf("tool" to tool, "args" to args))

    companion object {
        /** Parses {"tool": …, "args": {…}}; non-string arg values become strings. Null if not a tool call. */
        fun fromJson(text: String): ToolCall? {
            val obj = runCatching { Json.parse(text.trim()) }.getOrNull() as? Map<*, *> ?: return null
            val tool = obj["tool"] as? String ?: return null
            val args = (obj["args"] as? Map<*, *>).orEmpty()
                .mapNotNull { (k, v) -> (k as? String)?.let { it to (if (v is String) v else Json.write(v)) } }
                .toMap()
            return ToolCall(tool, args)
        }
    }
}

/** [say]: short sentence for voice + text. [data]: longer text to show (list of notes, page text…). */
data class ToolResult(val ok: Boolean, val say: String, val data: String = "") {
    companion object {
        fun ok(say: String, data: String = "") = ToolResult(true, say, data)
        fun fail(say: String) = ToolResult(false, say)
    }
}

/** One argument. All values are strings (simple for rules, grammar and training data). */
data class ArgSpec(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val choices: List<String>? = null,
)

class ToolContext(
    val context: Context,
    val heard: String,
    val locked: Boolean,
)

/** Is the screen locked right now (ROADMAP §L.3). Replaces the repeated KeyguardManager lookup. */
fun Context.isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

interface Tool {
    /** snake_case, stable — used in training data. Never rename. */
    val name: String
    val description: String
    val args: List<ArgSpec>
    val risk: Risk
    suspend fun run(call: ToolCall, ctx: ToolContext): ToolResult
}
