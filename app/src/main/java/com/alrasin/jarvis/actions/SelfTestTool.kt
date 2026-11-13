package com.alrasin.jarvis.actions

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.pollUntil

/**
 * "Jimi, test yourself" (ROADMAP #67): routing check + safe live checks. Each live check runs the real tool,
 * reads the result back a different way (settings / sysfs / AudioManager), then puts the old value back.
 * Never touches alarms, Wi-Fi, data, calls, messages or money apps.
 */
fun selfTestTool(context: Context, runTool: suspend (ToolCall) -> ToolResult) = SimpleTool(
    "self_test", "Test Jimi itself: routing and safe phone actions, then undo them",
) { _, _ ->
    val failed = SelfTest.routingFailures { RuleMatcher.match(it) }.toMutableList()
    var total = SelfTest.ROUTING.size
    val live = SelfTestLive(context, runTool)
    for ((name, check) in live.checks()) {
        total++
        val ok = runCatching { check() }.getOrElse { JarvisLog.e("self test $name", it); false }
        if (!ok) failed += name
    }
    val passed = total - failed.size
    JarvisLog.i("self test: $passed/$total failed=${failed.joinToString()}")
    ToolResult.ok(SelfTest.summary(passed, total, failed), data = "${passed * 100 / total}%")
}

private class SelfTestLive(private val context: Context, private val runTool: suspend (ToolCall) -> ToolResult) {
    private val torch = Torch(context)
    private val audio = context.getSystemService(AudioManager::class.java)

    fun checks(): List<Pair<String, suspend () -> Boolean>> = listOf(
        "flashlight" to { flashlight() },
        "auto rotate" to { flipSetting("auto_rotate", "system", "accelerometer_rotation") },
        "do not disturb" to { dnd() },
        "brightness" to { brightness() },
        "volume" to { volume() },
        "battery read" to { runTool(ToolCall("device_status", mapOf("what" to "battery"))).ok },
    )

    private fun toggle(target: String, on: Boolean) =
        ToolCall("toggle", mapOf("target" to target, "state" to if (on) "on" else "off"))

    private suspend fun waitFor(want: Boolean, read: suspend () -> Boolean?): Boolean =
        pollUntil(20, 100) { read() == want }

    private suspend fun flashlight(): Boolean {
        val before = torch.readState() ?: return false
        runTool(toggle("flashlight", !before))
        val changed = waitFor(!before) { torch.readState() }
        runTool(toggle("flashlight", before))
        return changed && waitFor(before) { torch.readState() }
    }

    private suspend fun flipSetting(target: String, ns: String, key: String): Boolean {
        val read: suspend () -> Boolean? = { RootSettings.get(ns, key).toIntOrNull()?.let { it != 0 } }
        val before = read() ?: return false
        runTool(toggle(target, !before))
        val changed = waitFor(!before, read)
        runTool(toggle(target, before))
        return changed && waitFor(before, read)
    }

    private suspend fun dnd(): Boolean {
        val read: suspend () -> Boolean? = { RootSettings.get("global", "zen_mode").toIntOrNull()?.let { it != 0 } }
        val before = read() ?: return false
        if (before) return runTool(ToolCall("toggle", mapOf("target" to "dnd", "state" to "status"))).ok
        return flipSetting("dnd", "global", "zen_mode")
    }

    private suspend fun brightness(): Boolean {
        val oldLevel = RootSettings.get("system", "screen_brightness")
        val oldMode = RootSettings.get("system", "screen_brightness_mode")
        val max = maxBrightness()
        val current = oldLevel.toIntOrNull() ?: return false
        val pct = if (current * 100 / max in 25..35) 60 else 30
        val result = runTool(ToolCall("set_brightness", mapOf("level" to "$pct")))
        val actual = RootSettings.get("system", "screen_brightness").toIntOrNull()
        RootSettings.put("system", "screen_brightness_mode", oldMode)
        RootSettings.put("system", "screen_brightness", oldLevel)
        return result.ok && actual == (pct * max / 100).coerceIn(1, max) &&
            RootSettings.get("system", "screen_brightness") == oldLevel
    }

    private suspend fun volume(): Boolean {
        val s = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
        val before = audio.getStreamVolume(s)
        val pct = if (before * 100 / max in 30..50) 70 else 40
        val result = runTool(ToolCall("set_volume", mapOf("level" to "$pct")))
        val actual = audio.getStreamVolume(s)
        audio.setStreamVolume(s, before, 0)
        return result.ok && actual == (pct * max + 50) / 100 && audio.getStreamVolume(s) == before
    }

    private fun maxBrightness(): Int = runCatching {
        Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            .takeIf { it != 0 }?.let { Resources.getSystem().getInteger(it) }
    }.getOrNull() ?: 255
}
