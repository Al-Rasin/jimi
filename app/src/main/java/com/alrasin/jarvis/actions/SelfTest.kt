package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.ToolCall

/**
 * "Jimi, test yourself" (ROADMAP #67), pure part: common sentences must reach the same tool every time.
 * Each case is sentence → tool, plus args that must match exactly. No phone needed (unit tested).
 */
object SelfTest {
    data class Case(val text: String, val tool: String, val args: Map<String, String> = emptyMap())

    private fun c(text: String, tool: String, vararg args: Pair<String, String>) = Case(text, tool, args.toMap())

    val ROUTING = listOf(
        c("turn on the flashlight", "toggle", "target" to "flashlight", "state" to "on"),
        c("switch off the torch", "toggle", "target" to "flashlight", "state" to "off"),
        c("turn on wifi", "toggle", "target" to "wifi", "state" to "on"),
        c("turn off bluetooth", "toggle", "target" to "bluetooth", "state" to "off"),
        c("enable do not disturb", "toggle", "target" to "dnd", "state" to "on"),
        c("turn off mobile data", "toggle", "target" to "data", "state" to "off"),
        c("turn on airplane mode", "toggle", "target" to "airplane", "state" to "on"),
        c("turn on auto rotate", "toggle", "target" to "auto_rotate", "state" to "on"),
        c("turn on battery saver", "toggle", "target" to "battery_saver", "state" to "on"),
        c("set brightness to 50", "set_brightness", "level" to "50"),
        c("auto brightness", "set_brightness", "level" to "auto"),
        c("set volume to 30", "set_volume", "level" to "30"),
        c("volume up", "set_volume", "level" to "up"),
        c("set an alarm for 6 30 am", "set_alarm"),
        c("wake me up at 7", "set_alarm"),
        c("set a timer for 5 minutes", "set_timer"),
        c("what time is it", "device_status", "what" to "time"),
        c("what is the date today", "device_status", "what" to "date"),
        c("how much battery is left", "device_status", "what" to "battery"),
        c("take a screenshot", "take_screenshot"),
        c("lock the phone", "lock_phone"),
        c("pause the music", "media_control", "action" to "pause"),
        c("next song", "media_control", "action" to "next"),
        c("open youtube", "open_app"),
        c("what is the weather", "weather"),
        c("good night", "run_routine", "name" to "good_night"),
        c("good morning", "run_routine", "name" to "good_morning"),
        c("cancel", "cancel"),
        c("stop", "cancel"),
        c("test yourself", "self_test"),
    )

    /** Sentences that went to the wrong tool or with wrong args, as short lines. Empty = all passed. */
    fun routingFailures(match: (String) -> ToolCall?): List<String> = ROUTING.mapNotNull { case ->
        val got = runCatching { match(case.text) }.getOrNull()
        val ok = got?.tool == case.tool && case.args.all { (k, v) -> got.arg(k) == v }
        if (ok) null else "\"${case.text}\" went to ${got?.tool ?: "nothing"}"
    }

    /** "Self test: 33 of 35 passed." plus up to 3 failure names, so the voice reply stays short. */
    fun summary(passed: Int, total: Int, failed: List<String>): String = buildString {
        append("Self test: $passed of $total passed.")
        if (failed.isNotEmpty()) append(" Failed: ").append(failed.take(3).joinToString("; ")).append(if (failed.size > 3) "; and ${failed.size - 3} more." else ".")
    }
}
