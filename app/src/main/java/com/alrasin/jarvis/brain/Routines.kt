package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.ToolCall

/** Built-in routines and their stored JSON form. */
object Routines {
    fun stepsJson(steps: List<ToolCall>) = Json.write(steps.map { mapOf("tool" to it.tool, "args" to it.args) })

    val DEFAULT_ROUTINES = mapOf(
        "good_night" to listOf(
            ToolCall("toggle", mapOf("target" to "dnd", "state" to "on")),
            ToolCall("set_brightness", mapOf("level" to "5")),
            ToolCall("list_reminders"),
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
