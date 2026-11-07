package com.alrasin.jarvis.core

import java.util.Locale

/** Shared "lowercase + strip + spaces" helpers (ROADMAP §L.4). Regexes built once. */
object TextNorm {
    private val SPACES = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val NOT_ALNUM = Regex("[^a-z0-9]")

    /** "  Open   YouTube " → "open youtube". Keeps punctuation. */
    fun squash(s: String): String = s.lowercase(Locale.ROOT).trim().replace(SPACES, " ")

    /** "Mom's  phone!" → "mom s phone". Letters (any script, with marks) and digits only. */
    fun words(s: String): String = s.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim()

    /** "You Tube 2" → "youtube2". ASCII letters and digits only, no spaces. */
    fun alnum(s: String): String = s.lowercase(Locale.ROOT).replace(NOT_ALNUM, "")
}
