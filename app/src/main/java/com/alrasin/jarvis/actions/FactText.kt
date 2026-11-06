package com.alrasin.jarvis.actions

/** Cleans remembered facts so the fact list has no timestamps, filler or junk like ".." (§M.10). */
internal object FactText {
    private val isoTime = Regex("\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b")
    private val bracketTime = Regex("[\\[(]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*[])]")
    private val epoch = Regex("\\b\\d{10}(?:\\d{3})?\\b")
    private val lead = Regex("^(?i)(?:(?:please )?remember(?: that)?|that)\\s+")
    private val subject = Regex("^(.+?) (?:is|are|was|were|means) ")

    /** Null when nothing worth keeping is left. */
    fun clean(raw: String): String? {
        val t = raw.replace(isoTime, " ").replace(bracketTime, " ").replace(epoch, " ")
            .replace(Regex("\\s+"), " ").trim()
            .replace(lead, "")
            .trim(' ', '.', ',', ';', ':', '-', '"', '\'')
        if (t.count { it.isLetter() } < 2) return null
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /** Lower case words only, for duplicate checks. */
    fun key(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** "My car is blue" → "my car"; null when the fact has no clear subject. */
    fun subject(text: String): String? = subject.find(key(text))?.groupValues?.get(1)
}
