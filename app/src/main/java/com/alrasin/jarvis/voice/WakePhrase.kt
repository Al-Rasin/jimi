package com.alrasin.jarvis.voice

/**
 * The command audio starts a bit before the wake (so the first command word is not lost), so STT text
 * often begins with the wake phrase ("hey jimmy turn on …"). This removes it.
 */
object WakePhrase {
    private val WORDS = setOf("hey", "hay", "hi", "a", "jimmy", "jimi", "jimmie", "jamie", "jim", "gym", "me", "mi")
    private const val MAX_WORDS = 4

    fun strip(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var n = 0
        while (n < words.size && n < MAX_WORDS && isWakeWord(words[n])) n++
        // only strip when it really looks like the wake phrase, not a lone "a" / "me"
        val prefix = words.take(n)
        if (prefix.none { it == "hey" || it == "hay" || looksLikeJimi(it) }) return words.joinToString(" ")
        return words.drop(n).joinToString(" ")
    }

    private fun isWakeWord(w: String) = w in WORDS || looksLikeJimi(w)

    private fun looksLikeJimi(w: String) = w.startsWith("jim") || w.startsWith("gym") || w.startsWith("jem")
}
