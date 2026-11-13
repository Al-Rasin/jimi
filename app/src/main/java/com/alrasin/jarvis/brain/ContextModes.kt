package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ContextMode

/** Decides the context mode from connected audio outputs, the clock and the user's switches. Pure Kotlin: unit tested. */
object ContextModes {
    enum class Kind { WIRED, BLUETOOTH, USB, OTHER }
    data class Output(val kind: Kind, val name: String, val carClass: Boolean = false)
    data class Prefs(val car: Boolean = true, val headphones: Boolean = true, val bedtime: Boolean = false, val hours: String = DEFAULT_HOURS)

    const val DEFAULT_HOURS = "23-6"
    const val QUIET_GAIN = 0.4f
    const val SHORT_SENTENCES = 2

    private val CAR_WORDS = Regex(
        "\\b(car|auto|carplay|sync|uconnect|mylink|mbux|idrive|carkit|car ?kit|head ?unit|toyota|honda|suzuki|nissan|" +
            "hyundai|kia|ford|bmw|mercedes|audi|vw|volkswagen|mazda|lexus|mitsubishi|proton|tata|mahindra|pioneer|kenwood|alpine)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isCar(o: Output): Boolean = o.kind == Kind.BLUETOOTH && (o.carClass || CAR_WORDS.containsMatchIn(o.name))

    fun decide(outputs: List<Output>, minuteOfDay: Int, p: Prefs): ContextMode {
        val car = p.car && outputs.any(::isCar)
        val phones = p.headphones && !car && outputs.any { !isCar(it) && it.kind != Kind.OTHER }
        val bed = p.bedtime && hours(p.hours)?.let { (s, e) -> inWindow(minuteOfDay, s * 60, e * 60) } == true
        return ContextMode(car, phones, bed)
    }

    /** "23-6" → (23, 6). */
    fun hours(s: String): Pair<Int, Int>? {
        val (a, b) = s.split('-').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return if (a in 0..23 && b in 0..23 && a != b) a to b else null
    }

    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start until end else minute >= start || minute < end

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    /** First [maxSentences] sentences, for short spoken answers. */
    fun shorten(text: String, maxSentences: Int = 2): String {
        val parts = text.trim().split(SENTENCE_END)
        return if (parts.size <= maxSentences) text.trim() else parts.take(maxSentences).joinToString(" ")
    }

    /** "Rahim says: on my way" — the newest line only, sender said once, kept short. */
    fun messageLine(app: String, title: String, text: String): String? {
        val from = title.trim()
        val last = text.lines().lastOrNull { it.isNotBlank() }?.trim()?.let { line ->
            // Chat apps repeat the sender inside the text ("Rahim: on my way"); drop it so the name is said once.
            if (from.isNotEmpty() && line.startsWith("$from:", ignoreCase = true)) line.substring(from.length + 1).trim() else line
        } ?: return null
        if (last.isEmpty()) return null
        val body = if (last.length > 160) last.take(157).trimEnd() + "…" else last
        return if (from.isEmpty()) "$app: $body" else "$from says: $body"
    }
}
