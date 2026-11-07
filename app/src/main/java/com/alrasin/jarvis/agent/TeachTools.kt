package com.alrasin.jarvis.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.actions.arg

/** teach_start / teach_done / teach_cancel (ROADMAP §H.6). Recording itself lives in [TeachSession]. */
fun teachTools(context: Context, session: TeachSession, store: RecipeStore): List<Tool> = listOf(
    SimpleTool(
        "teach_start",
        "Start watching the user do a task by hand, to save it as a recipe.",
        listOf(arg("task", "what the task is called, e.g. order coffee")),
    ) { call, ctx ->
        val task = call.arg("task") ?: return@SimpleTool ToolResult.fail("What should I call the task?")
        when {
            ctx.locked -> ToolResult.fail("Unlock the phone first, then teach me.")
            JarvisAccessibilityService.instance == null -> ToolResult.fail("Turn on Jarvis accessibility first.")
            else -> {
                val skip = skipPackages(context)
                session.start(task) { it in skip }
                ToolResult.ok("Okay, show me how to $task. Open the app and do it, then say \"Jimi, done\".")
            }
        }
    },
    SimpleTool("teach_done", "Stop watching and save what the user showed.", emptyList()) { _, _ ->
        val (name, rec) = session.stop() ?: return@SimpleTool ToolResult.fail("I'm not learning anything right now.")
        val pkg = rec.pkg
        val built = TeachBook.build(name, rec.events(), pkg, label(context, pkg), version(context, pkg))
        when (built) {
            is TeachBook.Built.Fail -> ToolResult.fail(built.why)
            is TeachBook.Built.Ok -> {
                store.save(built.recipe)
                session.refresh()
                JarvisLog.i("teach: saved \"$name\" ${built.recipe.steps.size} steps, secretSkipped=${rec.skippedSecret}")
                val note = if (rec.skippedSecret) " I skipped the password or code field." else ""
                ToolResult.ok(TeachBook.summary(built.recipe) + note)
            }
        }
    },
    SimpleTool("teach_cancel", "Stop watching and save nothing.", emptyList()) { _, _ ->
        if (session.stop() == null) ToolResult.fail("I'm not learning anything right now.")
        else ToolResult.ok("Okay, I didn't save it.")
    },
)

/** Launchers and system UI are never part of a task; Jimi and the keyboard are skipped by the watcher. */
private fun skipPackages(context: Context): Set<String> {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val launchers = context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
    return launchers.toSet() + setOf("com.android.systemui", "android", context.packageName)
}

private fun label(context: Context, pkg: String?): String = pkg?.let {
    val pm = context.packageManager
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
}.orEmpty()

private fun version(context: Context, pkg: String?): Long =
    pkg?.let { runCatching { context.packageManager.getPackageInfo(it, 0).longVersionCode }.getOrNull() } ?: 0L
