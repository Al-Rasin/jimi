package com.alrasin.jarvis.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult

/** Launchable apps, cached a few minutes (PLAN 4.3 Apps group). */
class AppList(private val context: Context) {
    data class App(val label: String, val pkg: String)

    private var cache: List<App> = emptyList()
    private var cacheMs = 0L

    fun all(): List<App> {
        if (System.currentTimeMillis() - cacheMs > 5 * 60_000 || cache.isEmpty()) {
            val pm = context.packageManager
            cache = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
            cacheMs = System.currentTimeMillis()
        }
        return cache
    }

    fun find(name: String): App? {
        val q = ALIASES[name.lowercase().trim()] ?: name
        return Fuzzy.best(q, all(), { it.label }) ?: all().firstOrNull { it.pkg.contains(Fuzzy.clean(q)) }
    }

    companion object {
        private val ALIASES = mapOf(
            "browser" to "Chrome", "internet" to "Chrome", "google chrome" to "Chrome",
            "photos" to "Gallery", "pictures" to "Gallery", "dialer" to "Phone", "phone app" to "Phone",
            "messages" to "Messaging", "sms" to "Messaging", "text messages" to "Messaging",
            "play store" to "Play Store", "app store" to "Play Store", "mail" to "Gmail", "email" to "Gmail",
            "maps" to "Maps", "google maps" to "Maps", "fb" to "Facebook", "insta" to "Instagram",
            "clock" to "Clock", "alarm" to "Clock", "files" to "File Manager", "file manager" to "File Manager",
            "music" to "Music", "you tube" to "YouTube", "whats app" to "WhatsApp", "calculator" to "Calculator",
        )
    }
}

fun appTools(context: Context, apps: AppList): List<Tool> = listOf(
    SimpleTool("open_app", "Open an app by name", listOf(arg("app", "app name, e.g. YouTube"))) { c, _ ->
        val name = c.arg("app") ?: return@SimpleTool ToolResult.fail("Which app?")
        if (name in listOf("settings", "phone settings")) {
            return@SimpleTool if (context.launch(Intent(android.provider.Settings.ACTION_SETTINGS)))
                ToolResult.ok("Boss, I opened settings.")
            else ToolResult.fail("Boss, I couldn't open settings.")
        }
        val app = apps.find(name) ?: return@SimpleTool ToolResult.fail("I can't find an app called $name.")
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return@SimpleTool ToolResult.fail("${app.label} can't be opened.")
        if (context.launch(intent)) ToolResult.ok("Boss, I opened ${app.label}.") else ToolResult.fail("Couldn't open ${app.label}.")
    },
    SimpleTool("close_app", "Close (force stop) an app", listOf(arg("app", "app name"))) { c, _ ->
        val name = c.arg("app") ?: return@SimpleTool ToolResult.fail("Which app?")
        val app = apps.find(name) ?: return@SimpleTool ToolResult.fail("I can't find an app called $name.")
        if (app.pkg == context.packageName) return@SimpleTool ToolResult.fail("I can't close myself.")
        val r = RootShell.run("am force-stop ${app.pkg}")
        if (r.ok) ToolResult.ok("Boss, I requested ${app.label} to close.") else ToolResult.fail("Couldn't close ${app.label}.")
    },
    SimpleTool("open_url", "Open a website", listOf(arg("url", "web address, e.g. google.com"))) { c, _ ->
        var url = c.arg("url") ?: return@SimpleTool ToolResult.fail("Which website?")
        if (!url.startsWith("http")) url = "https://$url"
        if (context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ToolResult.ok("Boss, I opened the link for ${Uri.parse(url).host}.")
        else ToolResult.fail("No browser found.")
    },
)

fun Context.isInstalled(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
