package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.ToolCall

/** Built-in routines and their stored JSON form. */
object Routines {
    fun stepsJson(steps: List<ToolCall>) = Json.write(steps.map { mapOf("tool" to it.tool, "args" to it.args) })

    fun fromJson(json: String): List<ToolCall> =
        (Json.parse(json) as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { m -> ToolCall.fromJson(Json.write(m)) } }

    /** Steps as editable text, one per line: `toggle target=dnd, state=on` (ROADMAP #45, B §K 8.1). */
    fun toText(steps: List<ToolCall>): String = steps.joinToString("\n") { step ->
        if (step.args.isEmpty()) step.tool else step.tool + " " + step.args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    /** Parsed text: the steps, or the first bad line (1-based) with why. */
    data class Parsed(val steps: List<ToolCall>, val error: String? = null)

    private val TOOL = Regex("^[a-z][a-z0-9_]*$")
    private val KEY = Regex("(?:^|,)\\s*([a-z][a-z0-9_]*)\\s*=")

    /** Reverse of [toText]. Blank lines and `#` comments skipped. Values may hold spaces; commas split args. */
    fun parse(text: String, known: (String) -> Boolean = { true }): Parsed {
        val steps = ArrayList<ToolCall>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val tool = line.substringBefore(' ').lowercase()
            if (!TOOL.matches(tool)) return Parsed(steps, "Line ${i + 1}: bad tool name \"$tool\"")
            if (!known(tool)) return Parsed(steps, "Line ${i + 1}: no tool \"$tool\"")
            val rest = line.substringAfter(' ', "").trim()
            val keys = KEY.findAll(rest).toList()
            if (rest.isNotEmpty() && (keys.isEmpty() || keys.first().range.first != 0)) {
                return Parsed(steps, "Line ${i + 1}: use key=value")
            }
            val args = LinkedHashMap<String, String>()
            keys.forEachIndexed { k, m ->
                val end = keys.getOrNull(k + 1)?.range?.first ?: rest.length
                args[m.groupValues[1]] = rest.substring(m.range.last + 1, end).trim()
            }
            steps.add(ToolCall(tool, args))
        }
        return if (steps.isEmpty()) Parsed(steps, "No steps") else Parsed(steps)
    }

    /** Spoken name to stored key: "Movie night" → movie_night. */
    fun key(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    val DEFAULT_ROUTINES = mapOf(
        "good_night" to listOf(
            ToolCall("toggle", mapOf("target" to "dnd", "state" to "on")),
            ToolCall("set_brightness", mapOf("level" to "5")),
            ToolCall("list_reminders"),
            ToolCall("daily_summary"),
        ),
        "good_morning" to listOf(
            ToolCall("toggle", mapOf("target" to "dnd", "state" to "off")),
            ToolCall("set_brightness", mapOf("level" to "auto")),
            ToolCall("device_status", mapOf("what" to "date")),
            ToolCall("calendar_read"),
            ToolCall("weather"),
            ToolCall("list_reminders"),
            ToolCall("notification_summary"),
        ),
    )
}
