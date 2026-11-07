package com.alrasin.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.alrasin.jarvis.agent.ScreenOcr
import com.alrasin.jarvis.agent.Skills
import com.alrasin.jarvis.agent.UiQuery
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult

fun screenTools(context: Context): List<Tool> {
    fun a() = JarvisAccessibilityService.instance
    val noA = ToolResult.fail("Screen control is off. Open Jarvis setup to turn on accessibility.")
    suspend fun keyOr(action: Int, key: Int, done: String): ToolResult =
        if (a()?.global(action) == true || RootInput.key(key).ok) ToolResult.ok(done) else noA
    return listOf(
        SimpleTool("read_screen", "Read the text on the screen") { _, _ ->
            val acc = a() ?: return@SimpleTool noA
            // No text in the tree (Flutter, games, images): read the pixels with OCR (§H.8).
            val text = acc.screenText().ifBlank { ScreenOcr.screenText(acc) }
            if (text.isBlank()) return@SimpleTool ToolResult.ok("I don't see any text on the screen.")
            ToolResult.ok(text.lines().take(12).joinToString(". ").take(600), text)
        },
        SimpleTool("tap", "Tap a button or text on the screen", listOf(arg("text", "visible text of the button"))) { c, _ ->
            val t = c.arg("text") ?: return@SimpleTool ToolResult.fail("Tap what?")
            val acc = a() ?: return@SimpleTool noA
            if (acc.clickText(t)) return@SimpleTool ToolResult.ok("Tapped $t.")
            val item = (ScreenOcr.find(acc, t) as? Skills.Pick.Found)?.item
                ?: return@SimpleTool ToolResult.fail("I can't find $t on the screen.")
            Skills.refuse(acc, item, t)?.let { return@SimpleTool ToolResult.fail(it) }
            val (x, y) = ScreenOcr.centre(item)
            if (UiQuery.tapAt(x, y)) ToolResult.ok("Tapped $t.") else ToolResult.fail("I can't tap $t.")
        },
        SimpleTool("type_text", "Type text into the focused box", listOf(arg("text", "text to type"))) { c, _ ->
            val t = c.arg("text") ?: return@SimpleTool ToolResult.fail("Type what?")
            val acc = a() ?: return@SimpleTool noA
            if (acc.typeText(t)) ToolResult.ok("Typed.") else ToolResult.fail("I can't find a text box.")
        },
        SimpleTool("scroll", "Scroll the screen", listOf(arg("direction", "up or down", choices = listOf("up", "down")))) { c, _ ->
            val down = c.arg("direction") != "up"
            if (a()?.scroll(down) == true) return@SimpleTool ToolResult.ok("Scrolled.")
            val dm = context.resources.displayMetrics
            val x = dm.widthPixels / 2
            val (y1, y2) = if (down) dm.heightPixels * 7 / 10 to dm.heightPixels * 3 / 10 else dm.heightPixels * 3 / 10 to dm.heightPixels * 7 / 10
            if (RootInput.swipe(x, y1, x, y2, 250).ok) ToolResult.ok("Scrolled.") else noA
        },
        SimpleTool("go_back", "Press back") { _, _ -> keyOr(AccessibilityService.GLOBAL_ACTION_BACK, 4, "Back.") },
        SimpleTool("go_home", "Go to the home screen") { _, _ -> keyOr(AccessibilityService.GLOBAL_ACTION_HOME, 3, "Home.") },
        SimpleTool("open_recents", "Show recent apps") { _, _ -> keyOr(AccessibilityService.GLOBAL_ACTION_RECENTS, 187, "Recent apps.") },
    )
}
