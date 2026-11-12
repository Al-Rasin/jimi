package com.alrasin.jarvis.agent.appmap

// Regexes built once, not on every call (ROADMAP §L.4).
private val PRETTY_RE = Regex("([a-z])([A-Z])")

/**
 * "Can't do" list + permission queue (ROADMAP §J.7). Buttons the explorer was unsure about wait here
 * as questions; a "yes" lets the next learn run tap them, a "no" is never asked again. Pure text — JVM tests.
 */
object AppAskQueue {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"

    fun question(appName: String, ask: AppAsk): String {
        val where = ask.screen.takeIf { it.isNotBlank() }?.let { " on ${pretty(it)}" }.orEmpty()
        return "May I tap \"${ask.label}\"$where in $appName to learn it? Say yes learn it, or no skip it."
    }

    /** Spoken answer to "what can't you do in X". Empty parts are left out. */
    fun limits(
        appName: String,
        cannot: List<AppCard.Cannot>,
        readOnly: Boolean,
        skipped: Boolean,
        denied: List<String>,
        riskyCount: Int,
    ): String {
        if (readOnly) return "$appName is a payment app. I only read it, I never tap in it."
        val parts = mutableListOf<String>()
        cannot.forEach { parts += if (it.why.isBlank()) it.what else "${it.what}, because ${it.why}" }
        if (skipped) parts += "explore it by myself, it is private or system"
        if (riskyCount > 0) parts += "tap $riskyCount risky button${if (riskyCount == 1) "" else "s"} like send or delete without asking you"
        if (denied.isNotEmpty()) parts += "tap ${denied.take(3).joinToString(", ") { "\"$it\"" }}, you said no"
        if (parts.isEmpty()) return "I know nothing I can't do in $appName yet."
        return "In $appName I can't ${parts.joinToString("; ")}."
    }

    /** "SettingsActivity" → "Settings". */
    fun pretty(screen: String): String = screen.removeSuffix("Activity").removeSuffix("Fragment")
        .replace(PRETTY_RE, "$1 $2").trim().ifBlank { screen }
}
