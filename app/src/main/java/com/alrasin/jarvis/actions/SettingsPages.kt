package com.alrasin.jarvis.actions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.isLocked
import com.alrasin.jarvis.core.launch
import com.alrasin.jarvis.core.pollUntil
import com.alrasin.jarvis.core.resolves
import kotlinx.coroutines.delay

// Regexes built once, not on every call (ROADMAP §L.4).
private val DARK_WANTED_RE = Regex("\\blight (?:mode|theme)\\b")
private val KNOWN_RE = Regex("\\bhaptic|touch (?:feedback|vibration)|vibrate on touch")
private val KNOWN_RE_2 = Regex("\\bvibrat")
private val KNOWN_RE_3 = Regex("\\b(?:reading|paper) mode\\b|\\beye comfort\\b")
private val KEY_RE = Regex("[^a-z0-9]+")
private val MATCH_RE = Regex("[^a-z0-9-]+")
private val BEST_RE = Regex("\\bsettings?\\b")

/**
 * Any setting on any phone (D-056, ROADMAP §G.2). Pages come from Android's own list of public settings screens
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
            if (!context.resolves(Intent(action))) null else page(action, name)
        }
    }

    private val settingsPkg by lazy {
        Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager)?.packageName ?: "com.android.settings"
    }

    suspend fun run(name: String, state: String?): ToolResult {
        if (DESKTOP.containsMatchIn(name)) return ToolResult.ok(DESKTOP_REPLY)
        if (state == "status") return status(name)
        direct(name, state)?.let { return it }
        // Keys are set with root, so they work on the lock screen too; only the Settings app needs unlocking.
        val service = JarvisAccessibilityService.instance
        val locked = context.isLocked()
        val k = known(name)
        if (state == "on" || state == "off") {
            if (k != null && (!k.appFirst || locked || service == null)) knownSet(k, state == "on")?.takeIf { it.ok || locked }?.let { return it }
            if (k == null) stored(name, state == "on")?.let { return it }
        }
        if (locked) return ToolResult.fail("Unlock the phone first, ${Persona.ADDRESS}.")
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
        val lastTry = suspend { k?.let { knownSet(it, on) } }
        // The row may not be on screen at once; wait for it, then flip and read back.
        repeat(12) {
            val label = match(name, service.texts(settingsPkg).filter { service.switchState(settingsPkg, it) != null })
            if (label != null) {
                service.setSwitch(settingsPkg, label, on)
                delay(500)
                return if (service.switchState(settingsPkg, label) == on) ToolResult.ok("$label is ${state} now.")
                else lastTry() ?: ToolResult.ok("I opened $label. Turn it ${state} there.")
            }
            delay(250)
        }
        return lastTry() ?: ToolResult.ok("I opened $name settings. Turn it ${state} there.")
    }

    /** Reads the real on/off value; changes nothing, so a "did you…?" answer is never a guess. */
    private suspend fun status(name: String): ToolResult {
        val label = name.replaceFirstChar { it.uppercase() }
        if (DARK.containsMatchIn(name)) {
            darkOn()?.let { return ToolResult.ok("Dark mode is ${if (it) "on" else "off"}.") }
        }
        known(name)?.let { k ->
            when (RootSettings.get(k.keys.first().first, k.keys.first().second)) {
                "1" -> return ToolResult.ok("${k.label} is on.")
                "0" -> return ToolResult.ok("${k.label} is off.")
            }
        }
        val (ns, key, _) = key(name, entries(), read = true) ?: return ToolResult.fail("I can't read $name from here.")
        return when (RootSettings.get(ns, key)) {
            "1" -> ToolResult.ok("$label is on.")
            "0" -> ToolResult.ok("$label is off.")
            else -> ToolResult.fail("I can't read $name from here.")
        }
    }

    private suspend fun entries() = NAMESPACES.flatMap { ns ->
        RootSettings.list(ns).mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> Triple(ns, k.trim(), v.trim()) }
        }
    }

    /** Settings Android can set straight away (root), the same on every phone; null = not one of them. */
    private suspend fun direct(name: String, state: String?): ToolResult? {
        if (state != "on" && state != "off" || !DARK.containsMatchIn(name)) return null
        val on = darkWanted(name, state == "on")
        RootShell.run("cmd uimode night ${if (on) "yes" else "no"}")
        // MIUI sometimes ignores the uimode command; the secure key (2 = dark, 1 = light) is the same switch.
        if (darkOn() != on) RootShell.run("settings put secure ui_night_mode ${if (on) 2 else 1}", "cmd uimode night ${if (on) "yes" else "no"}")
        val now = darkOn()
        JarvisLog.i("dark mode → $on, reads $now")
        return when (now) {
            on -> ToolResult.ok("Dark mode is ${if (on) "on" else "off"} now.")
            null -> null
            else -> ToolResult.fail("Dark mode is still ${if (now) "on" else "off"}.")
        }
    }

    private suspend fun darkOn(): Boolean? {
        val out = RootShell.run("cmd uimode night", log = false).out.lowercase()
        return when {
            "yes" in out -> true
            "no" in out -> false
            else -> when (RootSettings.get("secure", "ui_night_mode")) { "2" -> true; "1" -> false; else -> null }
        }
    }

    /** Writes every key of [k] with root and reads them back; null when root is missing (then the Settings app is used). */
    private suspend fun knownSet(k: Known, on: Boolean): ToolResult? {
        if (!RootShell.available) return null
        val v = if (on) "1" else "0"
        k.keys.forEach { (ns, key) -> RootSettings.put(ns, key, v) }
        val read = k.keys.map { (ns, key) -> RootSettings.get(ns, key) }
        JarvisLog.i("setting ${k.label}: ${k.keys.map { it.second }} → $v, reads $read")
        return if (read.all { it == v }) ToolResult.ok("${k.label} is ${if (on) "on" else "off"} now.")
        else ToolResult.fail("${k.label} didn't change. It reads ${if (read.first() == "1") "on" else "off"}.")
    }

    /**
     * On/off switches Android keeps as 0/1 keys (`settings list system|secure|global`): "haptic feedback" →
     * system haptic_feedback_enabled. Set with root and read back; null = no such key (then the Settings app is used).
     */
    private suspend fun stored(name: String, on: Boolean): ToolResult? {
        val (ns, key, _) = key(name, entries()) ?: return null
        val v = if (on) "1" else "0"
        val done = RootSettings.put(ns, key, v) && RootSettings.get(ns, key) == v
        JarvisLog.i("setting key $ns/$key → $v ${if (done) "ok" else "failed"}")
        return if (done) ToolResult.ok("${name.replaceFirstChar { it.uppercase() }} is ${if (on) "on" else "off"} now.") else null
    }

    /** Settings app → search box → type [name] (then simpler words if nothing is found) → tap the closest result. */
    private suspend fun search(service: JarvisAccessibilityService, name: String): Boolean {
        if (!context.launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))) return false
        if (!pollUntil(16, 250) { service.clickExact(settingsPkg, labels = SEARCH_LABELS) }) return false
        var typed = ""
        // A cold search index takes ~20 s to load on a slow phone and says "no results" until then, so keep cycling.
        val end = System.currentTimeMillis() + SEARCH_MS
        while (System.currentTimeMillis() < end) for (query in queries(name)) {
            if (!type(service, typed, query)) return false
            typed = query
            var hit: String? = null
            // Slow polling: reading the screen too often keeps a slow Settings app from showing its results.
            if (!pollUntil(8, 1000) { match(name, service.texts(settingsPkg).filter { !it.equals(query, true) }).also { hit = it } != null }) {
                JarvisLog.i("setting search \"$query\": no match in ${service.texts(settingsPkg).take(6)}")
                if (System.currentTimeMillis() >= end) break else continue
            }
            JarvisLog.i("setting search \"$query\" → \"$hit\"")
            return pollUntil(16, 250) { service.clickUniqueText(settingsPkg, hit!!) }
        }
        return false
    }

    /**
     * MIUI search runs only on real key presses and ignores some set-text changes, so the box is cleared with
     * Delete presses (one per letter typed before) and the query typed as keys (root); set-text is only the fallback without root.
     */
    private suspend fun type(service: JarvisAccessibilityService, old: String, query: String): Boolean {
        val clear = if (old.isEmpty()) "" else "input keyevent 123 ${"67 ".repeat(old.length)}; "
        val safe = query.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.replace(" ", "%s")
        return RootShell.run("${clear}input text $safe").ok || service.replaceText(settingsPkg, query)
    }

    companion object {
        private val DARK = Regex("\\b(?:dark|light (?:mode|theme)|night mode)\\b")
        private val DESKTOP = Regex("\\bdesktop\\b")
        internal const val DESKTOP_REPLY = "Desktop mode shows apps in windows on a big screen, like a computer. This phone doesn't have it."

        /** "turn on light mode" means dark mode off. */
        internal fun darkWanted(name: String, on: Boolean): Boolean =
            if (DARK_WANTED_RE.containsMatchIn(name.lowercase()) && "dark" !in name.lowercase()) !on else on

        /** Settings whose keys are known, so no guessing from `settings list`; every key is written (MIUI keeps extra copies). */
        internal data class Known(val label: String, val keys: List<Pair<String, String>>, val appFirst: Boolean = false)

        internal fun known(name: String): Known? {
            val n = name.lowercase()
            return when {
                KNOWN_RE.containsMatchIn(n) ->
                    Known("Haptic feedback", listOf("system" to "haptic_feedback_enabled"))
                KNOWN_RE_2.containsMatchIn(n) ->
                    Known("Vibration", listOf("system" to "vibrate_when_ringing", "system" to "vibrate_in_normal", "system" to "vibrate_in_silent"))
                KNOWN_RE_3.containsMatchIn(n) ->
                    // The Settings switch was proven on the phone (#58); the key alone may not repaint the screen, so it is the fallback.
                    Known("Reading mode", listOf("system" to "screen_paper_mode_enabled"), appFirst = true)
                else -> null
            }
        }
        /**
         * Key words that differ from the spoken name, used only to READ a value: MIUI keeps reading mode in
         * screen_paper_mode_enabled, but writing that key alone may skip the screen change, so turning it on still uses Settings.
         */
        private val READ_KEYS = mapOf("reading" to "paper")
        private val SPELLING = mapOf("wifi" to "wi-fi", "vibration" to "vibrate", "haptics" to "haptic", "hotspot" to "hotspot")

        /** What to type: the whole name, its usual spelling, then single words, longest first (MIUI search is word based). */
        internal fun queries(name: String): List<String> {
            val words = name.lowercase().split(' ').filter { it.length > 2 }
            val spelled = words.joinToString(" ") { SPELLING[it] ?: it }
            return (listOf(name.lowercase(), spelled) + words.map { SPELLING[it] ?: it }.sortedByDescending { it.length }).distinct()
        }

        private const val SEARCH_MS = 30_000L
        private val NAMESPACES = listOf("system", "secure", "global")
        private val FILLER = setOf("enabled", "enable", "mode", "on", "is", "setting", "settings")
        /** Keys never flipped by voice: debugging, security, accessibility and install checks. */
        private val DENY = Regex("adb|develop|accessibility|verifier|install|password|lock|usb|secure|admin|boot|airplane")

        /** Best 0/1 key holding every spoken word (fewest extra words); null when none fits. */
        internal fun key(name: String, entries: List<Triple<String, String, String>>, read: Boolean = false): Triple<String, String, String>? {
            val words = name.lowercase().split(KEY_RE).filter { it.length > 2 && it !in FILLER }
                .map { SPELLING[it] ?: it }.map { if (read) READ_KEYS[it] ?: it else it }.map { it.replace("-", "").trimEnd('s') }
            if (words.isEmpty()) return null
            return entries.filter { (_, k, v) -> (v == "0" || v == "1") && !DENY.containsMatchIn(k) }
                .mapNotNull { e ->
                    val parts = e.second.lowercase().split('_', '.').filter { it.isNotEmpty() }
                    if (!words.all { w -> parts.any { it.startsWith(w) } }) null
                    else e to parts.count { p -> p !in FILLER && words.none { p.startsWith(it) } }
                }.minByOrNull { it.second }?.first
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
            fun score(t: String) = t.lowercase().split(MATCH_RE).count { w -> words.any { w.startsWith(it) } }
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
            val q = name.lowercase().replace(BEST_RE, "").trim()
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
    listOf(arg("name", "setting name, e.g. dark mode, nfc, language"), arg("state", "open, on, off, or status to only read it", required = false, choices = listOf("open", "on", "off", "status"))),
) { c, _ ->
    val name = c.require("name", "Which setting?")
    pages.run(name, c.arg("state"))
}
