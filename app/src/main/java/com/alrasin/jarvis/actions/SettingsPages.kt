package com.alrasin.jarvis.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.delay

/**
 * Any setting on any phone (D-056, QUALITY-PLAN G.2). Pages come from Android's own list of public settings screens
 * (`Settings.ACTION_*_SETTINGS` that open on this phone); anything else is typed into the Settings app's search box.
 * For on/off, the switch in the row with that name is flipped through accessibility and read back.
 */
class SettingsPages(private val context: Context) {
    /** [base] from the Android name ("nfc payment"), [aliases] = other spoken words for it. */
    data class Page(val action: String, val base: String, val aliases: String = "")

    /** Public pages that open on this phone. */
    val pages: List<Page> by lazy {
        Settings::class.java.fields.mapNotNull { f ->
            val name = f.name
            if (!name.startsWith("ACTION_") || !name.endsWith("_SETTINGS")) return@mapNotNull null
            val action = runCatching { f.get(null) as? String }.getOrNull() ?: return@mapNotNull null
            if (Intent(action).resolveActivity(context.packageManager) == null) null else page(action, name)
        }
    }

    private val settingsPkg by lazy {
        Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager)?.packageName ?: "com.android.settings"
    }

    suspend fun run(name: String, state: String?): ToolResult {
        direct(name, state)?.let { return it }
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return ToolResult.fail("Unlock the phone first, Boss.")
        val service = JarvisAccessibilityService.instance
        val page = best(name, pages)
        val flip = state == "on" || state == "off"
        val opened = when {
            page != null && (!flip || service == null) -> context.launch(Intent(page.action)).also { JarvisLog.i("setting page ${page.action}") }
            service != null -> search(service, name) || (page != null && context.launch(Intent(page.action)))
            else -> context.launch(Intent(Settings.ACTION_SETTINGS))
        }
        if (!opened) return ToolResult.fail("I couldn't open settings for $name.")
        if (!flip) return ToolResult.ok("Opened $name settings.")
        service ?: return ToolResult.ok("Opened $name settings. Turn it ${state} there.")
        val on = state == "on"
        // The row may not be on screen at once; wait for it, then flip and read back.
        repeat(12) {
            val label = match(name, service.texts(settingsPkg))
            if (label != null && service.switchState(settingsPkg, label) != null) {
                service.setSwitch(settingsPkg, label, on)
                delay(500)
                return if (service.switchState(settingsPkg, label) == on) ToolResult.ok("$label is ${state} now.")
                else ToolResult.ok("I opened $label. Turn it ${state} there.")
            }
            delay(250)
        }
        return ToolResult.ok("I opened $name settings. Turn it ${state} there.")
    }

    /** Settings Android can set straight away (root), the same on every phone; null = not one of them. */
    private suspend fun direct(name: String, state: String?): ToolResult? {
        if (state != "on" && state != "off" || !DARK.containsMatchIn(name)) return null
        val ok = RootShell.run("cmd uimode night ${if (state == "on") "yes" else "no"}").ok
        return if (ok) ToolResult.ok("Dark mode is $state now.") else null
    }

    /** Settings app → search box → type [name] (then simpler words if nothing is found) → tap the closest result. */
    private suspend fun search(service: JarvisAccessibilityService, name: String): Boolean {
        if (!context.launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))) return false
        if (!waitFor { service.clickExact(settingsPkg, labels = SEARCH_LABELS) }) return false
        for (query in queries(name)) {
            if (!type(service, query)) return false
            delay(800)
            var hit: String? = null
            // MIUI fills results slowly; the box itself still shows the query, so it is skipped.
            if (!waitFor(32) { match(name, service.texts(settingsPkg).filter { !it.equals(query, true) }).also { hit = it } != null }) continue
            JarvisLog.i("setting search \"$query\" → \"$hit\"")
            return waitFor { service.clickUniqueText(settingsPkg, hit!!) }
        }
        return false
    }

    /**
     * MIUI search runs only on real key presses and ignores some set-text changes, so the box is cleared with
     * Delete presses and the query typed as keys (root); set-text is only the fallback without root.
     */
    private suspend fun type(service: JarvisAccessibilityService, query: String): Boolean {
        val old = service.editableText(settingsPkg).orEmpty()
        val clear = if (old.isEmpty()) "" else "input keyevent 123 ${"67 ".repeat(old.length)}; "
        val safe = query.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.replace(" ", "%s")
        return RootShell.run("${clear}input text $safe").ok || service.replaceText(settingsPkg, query)
    }

    private suspend fun waitFor(tries: Int = 16, check: () -> Boolean): Boolean {
        repeat(tries) {
            if (check()) return true
            delay(250)
        }
        return false
    }

    companion object {
        private val DARK = Regex("\\b(?:dark (?:mode|theme)|night mode)\\b")
        private val SPELLING = mapOf("wifi" to "wi-fi", "vibration" to "vibrate", "haptics" to "haptic", "hotspot" to "hotspot")

        /** What to type: the whole name, its usual spelling, then single words, longest first (MIUI search is word based). */
        internal fun queries(name: String): List<String> {
            val words = name.lowercase().split(' ').filter { it.length > 2 }
            val spelled = words.joinToString(" ") { SPELLING[it] ?: it }
            return (listOf(name.lowercase(), spelled) + words.map { SPELLING[it] ?: it }.sortedByDescending { it.length }).distinct()
        }

        private val SEARCH_LABELS = listOf("Search settings", "Search", "Search Settings")
        private val SKIP = setOf("search settings", "search", "cancel")

        /**
         * Screen text that shares the most words with [name] ("haptic feedback" → "Vibration & haptic strength"),
         * fewest extra words on a tie; breadcrumbs ("Display/Dark mode") and search-box labels are skipped.
         */
        internal fun match(name: String, texts: List<String>): String? {
            val words = name.lowercase().split(' ').filter { it.length > 2 && it != "settings" && it != "setting" }
                .flatMap { listOfNotNull(it, SPELLING[it]) }.map { it.trimEnd('s') }.distinct()
            fun score(t: String) = t.lowercase().split(Regex("[^a-z0-9-]+")).count { w -> words.any { w.startsWith(it) } }
            return texts.filter { '/' !in it && it.lowercase() !in SKIP && it.length < 60 }
                .map { it to score(it) }.filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })?.first
        }

        /** "ACTION_NFC_PAYMENT_SETTINGS" → base "nfc payment", plus spoken aliases. */
        internal fun page(action: String, field: String): Page {
            val base = field.removePrefix("ACTION_").removeSuffix("_SETTINGS").lowercase().replace('_', ' ')
            return Page(action, base, ALIASES[base].orEmpty())
        }

        private val ALIASES = mapOf(
            "wifi" to "wi-fi wireless internet", "display" to "screen brightness dark mode font size", "sound" to "volume ringtone",
            "locale" to "language", "manage applications" to "apps applications", "internal storage" to "storage",
            "security" to "lock screen password", "date" to "time clock", "application development" to "developer options",
            "battery saver" to "power saving", "accessibility" to "talkback", "bluetooth" to "bluetooth devices pair",
        )

        /** Best public page whose words fit [name] closely, or null (then Settings search is used). */
        internal fun best(name: String, pages: List<Page>): Page? {
            val q = name.lowercase().replace(Regex("\\bsettings?\\b"), "").trim()
            if (q.isEmpty()) return pages.firstOrNull { it.action == Settings.ACTION_SETTINGS }
            pages.firstOrNull { it.base == q }?.let { return it }
            val phrase = Regex("\\b${Regex.escape(q)}\\b")
            pages.filter { phrase.containsMatchIn(it.base + " " + it.aliases) }.minByOrNull { it.base.length }?.let { return it }
            return Fuzzy.best(q, pages, { it.base }, min = 0.8)
        }
    }
}

/** "open dark mode settings", "turn on reading mode" — any setting, not only the fixed toggles. */
fun settingTool(pages: SettingsPages) = SimpleTool(
    "setting", "Open any phone setting by name, or turn a setting on/off that the toggle tool does not cover",
    listOf(arg("name", "setting name, e.g. dark mode, nfc, language"), arg("state", "open, on or off", required = false, choices = listOf("open", "on", "off"))),
) { c, _ ->
    val name = c.arg("name") ?: return@SimpleTool ToolResult.fail("Which setting?")
    pages.run(name, c.arg("state"))
}
