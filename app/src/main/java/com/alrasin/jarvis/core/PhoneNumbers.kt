package com.alrasin.jarvis.core

/** One place for phone number parsing and matching (ROADMAP §L.4). */
object PhoneNumbers {
    private val DIALABLE = Regex("^\\+?[0-9(][0-9\\s().-]*$")
    private val SEPARATORS = Regex("[\\s().-]")
    private val E164_DIGITS = Regex("[1-9][0-9]{6,14}")

    /** Digits a match uses: last 9 skips country code / leading 0 differences. */
    const val TAIL_DIGITS = 9

    fun digits(text: String): String = text.filter(Char::isDigit)

    /** "01712 345678" → "01712345678", "+880 17…" → "+88017…"; null if it is not a number (letters, < 5 digits). */
    fun parse(text: String): String? {
        val trimmed = text.trim()
        if (!DIALABLE.matches(trimmed)) return null
        val digits = digits(trimmed)
        if (digits.length < 5) return null
        return if (trimmed.startsWith('+')) "+$digits" else digits
    }

    /** "+880 1712-345678" / "00880…" → "8801712345678"; null without an explicit country code. */
    fun international(value: String): String? {
        val compact = value.trim().replace(SEPARATORS, "")
        val digits = when {
            compact.startsWith("+") -> compact.drop(1)
            compact.startsWith("00") -> compact.drop(2)
            else -> return null
        }
        return digits.takeIf { it.matches(E164_DIGITS) }
    }

    /** Last [TAIL_DIGITS] digits, used to match the same number written with or without country code. */
    fun tail(number: String): String = digits(number).takeLast(TAIL_DIGITS)

    /** True when [number] ends with [tail] (from [tail]). */
    fun endsWith(number: String, tail: String): Boolean = digits(number).endsWith(tail)
}
