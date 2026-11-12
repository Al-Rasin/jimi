package com.alrasin.jarvis.voice

import com.alrasin.jarvis.core.TextNorm
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

// Regexes built once, not on every call (ROADMAP §L.4).
private val NORMALIZE_RE = Regex("!?\\[([^]\\n]*)]\\([^)]*\\)")
private val NORMALIZE_RE_2 = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+")
private val NORMALIZE_RE_3 = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
private val NORMALIZE_RE_4 = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|org|net|edu|gov|io|dev|bd)(?:/[^\\s<>]*)?")
private val NORMALIZE_RE_5 = Regex("(?m)^\\s*(?:#{1,6}\\s*|[-*+>]\\s+|[0-9]+[.)]\\s+)")
private val NORMALIZE_RE_6 = Regex("[`*_~|]")
private val NORMALIZE_RE_7 = Regex("[\\u0980-\\u09F1\\u09F4-\\u09FF]+")
private val NORMALIZE_RE_8 = Regex("\\b([0-9]{4})-([0-9]{2})-([0-9]{2})\\b")
private val NORMALIZE_RE_9 = Regex("\\b([0-9]{1,2})[/-]([0-9]{1,2})[/-]([0-9]{4})\\b")
private val NORMALIZE_RE_10 = Regex("(?i)\\b([0-9]{1,2}):([0-9]{2})(?:\\s*([ap])\\.?m\\b)?(?![0-9])")
private val NORMALIZE_RE_11 = Regex("(?i)\\b([0-9]{1,2})\\s*([ap])\\.?m\\b")
private val NORMALIZE_RE_12 = Regex("(?<![\\w.])\\+?[0-9](?:[0-9 ()-]*[0-9])?(?!\\w|\\.[0-9])")
private val NORMALIZE_RE_13 = Regex("\\D+")
private val NORMALIZE_RE_14 = Regex("\\s{2,}")
private val NORMALIZE_RE_15 = Regex("(?i)\\b([0-9]+)(?:st|nd|rd|th)\\b")
private val NORMALIZE_RE_16 = Regex("(?i)\\be\\.g\\.(?=\\s|[,;:!?]|$)")
private val NORMALIZE_RE_17 = Regex("(?i)\\bi\\.e\\.(?=\\s|[,;:!?]|$)")
private val NORMALIZE_RE_18 = Regex("(?i)\\bkm\\b")
private val NORMALIZE_RE_19 = Regex("\\b[A-Z]{2,}\\b")
private val NORMALIZE_RE_20 = Regex("(?i)\\bboss\\b[!,]*")
private val NORMALIZE_RE_21 = Regex(",\\s*(?=boss\\b)")
private val NORMALIZE_RE_22 = Regex("[\\[\\]{}()<>]")
private val NORMALIZE_RE_23 = Regex("[—–]")
private val NORMALIZE_RE_24 = Regex("[^\\p{L}\\p{N}\\s.,!?:;'\\-]")
private val NORMALIZE_RE_26 = Regex("\\s+([.,!?:;])")
private val NORMALIZE_RE_27 = Regex("[.!?]{2,}")
private val NORMALIZE_RE_28 = Regex("([.,!?;:])(?:\\s*[,;:])+")

object SpeechText {
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December")
    private val ordinalEnds = mapOf("one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth")
    private val acronyms = setOf("AI", "API", "CPU", "GPU", "GPS", "USB", "SMS", "OTP", "PIN", "URL", "HTML",
        "PDF", "RAM", "SIM", "SD", "ID", "TV", "PC", "UK", "US", "USA", "BBC", "HTTP", "HTTPS")
    private val numberPattern = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"

    fun normalize(text: String): String {
        var value = text.map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("")
        value = value.replace(NORMALIZE_RE) { it.groupValues[1] }
        value = value.replace(NORMALIZE_RE_2) { link(it.value) }
        value = value.replace(NORMALIZE_RE_3, "link")
        value = value.replace(NORMALIZE_RE_4) { link(it.value) }
        value = value.replace(NORMALIZE_RE_5, "")
        value = value.replace(NORMALIZE_RE_6, " ")
        value = value.replace(NORMALIZE_RE_7, " ")
        value = value.replace(NORMALIZE_RE_8) {
            date(it, it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }
        // Numeric local dates are day/month/year; ISO dates are handled above.
        value = value.replace(NORMALIZE_RE_9) {
            date(it, it.groupValues[3], it.groupValues[2], it.groupValues[1])
        }
        value = value.replace(Regex("(?i)\\b(${months.joinToString("|")})\\s+([0-9]{1,2})(?:st|nd|rd|th)?(?=\\b)")) {
            "${it.groupValues[1]} ${ordinal(it.groupValues[2].toInt().toString())}"
        }
        value = value.replace(Regex("(?i)\\b([0-9]{1,2})(?:st|nd|rd|th)?\\s+(${months.joinToString("|")})\\b")) {
            "${ordinal(it.groupValues[1].toInt().toString())} ${it.groupValues[2]}"
        }
        value = value.replace(NORMALIZE_RE_10) {
            val hour = it.groupValues[1].toInt()
            val minute = it.groupValues[2].toInt()
            val marker = it.groupValues[3].lowercase(Locale.ROOT)
            if (hour > 23 || minute > 59 || (marker.isNotEmpty() && hour !in 1..12)) it.value
            else cardinal(hour.toLong()) + when {
                minute == 0 -> if (marker.isEmpty()) " o'clock" else ""
                minute < 10 -> " oh ${cardinal(minute.toLong())}"
                else -> " ${cardinal(minute.toLong())}"
            } + if (marker.isNotEmpty()) " ${marker.uppercase(Locale.ROOT)} M" else ""
        }
        value = value.replace(NORMALIZE_RE_11) {
            "${cardinal(it.groupValues[1].toLong())} ${it.groupValues[2].uppercase(Locale.ROOT)} M"
        }
        value = value.replace(Regex("(?i)(?<![\\w])(?:Tk\\.?|BDT|৳|\\$)\\s*($numberPattern)")) {
            money(it.groupValues[1], it.value.trimStart().startsWith('$'))
        }
        value = value.replace(Regex("(?i)($numberPattern)\\s*(?:Tk\\.?|BDT|৳)(?![A-Za-z])")) {
            money(it.groupValues[1], false)
        }
        value = value.replace(NORMALIZE_RE_12) {
            val digits = it.value.filter(Char::isDigit)
            val groups = it.value.split(NORMALIZE_RE_13).filter(String::isNotEmpty)
            val phoneGroups = groups.size == 1 || (groups.size in 2..4 && groups.last().length >= 3 &&
                groups.withIndex().all { (index, group) -> group.length >= 2 || (index == 0 && it.value.startsWith('+')) })
            if (digits.length in 10..15 && phoneGroups && !it.value.contains(NORMALIZE_RE_14)) {
                (if (it.value.startsWith('+')) "plus " else "") + digits(digits)
            } else it.value
        }
        value = value.replace(Regex("($numberPattern)\\s*%")) { "${number(it.groupValues[1])} percent" }
        value = value.replace(Regex("(?i)($numberPattern)\\s*(km/h|km|cm|mm|kg|mg|ml|mph|kph|°c|°f|gb|mb|kb|hz|mins?|hrs?|ms|m|g|l|s)\\b")) {
            val amount = it.groupValues[1]
            val unit = it.groupValues[2].lowercase(Locale.ROOT)
            val singular = amount.replace(",", "").toDoubleOrNull() == 1.0
            val unitWord = when (unit) {
                "km/h", "kph" -> "kilometers per hour"
                "mph" -> "miles per hour"
                "°c" -> "degrees Celsius"
                "°f" -> "degrees Fahrenheit"
                "hz" -> "hertz"
                else -> {
                    val name = when (unit) {
                        "km" -> "kilometer"; "cm" -> "centimeter"; "mm" -> "millimeter"; "m" -> "meter"
                        "kg" -> "kilogram"; "mg" -> "milligram"; "g" -> "gram"; "ml" -> "milliliter"; "l" -> "liter"
                        "gb" -> "gigabyte"; "mb" -> "megabyte"; "kb" -> "kilobyte"; "ms" -> "millisecond"
                        "min", "mins" -> "minute"; "hr", "hrs" -> "hour"; else -> "second"
                    }
                    name + if (singular) "" else "s"
                }
            }
            "${number(amount)} $unitWord"
        }
        value = value.replace(NORMALIZE_RE_15) { ordinal(it.groupValues[1]) }
        value = value.replace(NORMALIZE_RE_16, "for example")
            .replace(NORMALIZE_RE_17, "that is")
        val abbreviations = mapOf("etc" to "and so on", "dr" to "Doctor", "mr" to "Mister", "mrs" to "Missus",
            "ms" to "Miss", "prof" to "Professor", "vs" to "versus", "approx" to "approximately")
        value = value.replace(Regex("(?i)\\b(${abbreviations.keys.joinToString("|")})\\.(?=\\s|$)")) {
            abbreviations.getValue(it.groupValues[1].lowercase(Locale.ROOT))
        }
        value = value.replace(NORMALIZE_RE_18, "kilometers")
        value = value.replace(Regex("(?<![\\w])[-+]?$numberPattern(?![\\w])")) { number(it.value) }
        value = value.replace(NORMALIZE_RE_19) {
            if (it.value in acronyms) it.value.toList().joinToString(" ") else it.value.lowercase(Locale.ROOT)
        }
        value = value.replace(NORMALIZE_RE_20, "boss")
            .replace(NORMALIZE_RE_21, " ")
        value = value.replace('&', ' ').replace(NORMALIZE_RE_22, " ")
            .replace('’', '\'').replace('“', ' ').replace('”', ' ')
            .replace(NORMALIZE_RE_23, ", ")
            .replace(NORMALIZE_RE_24, " ")
            .replace(TextNorm.SPACES, " ")
            .replace(NORMALIZE_RE_26, "$1")
            .replace(NORMALIZE_RE_27, ".")
            .replace(NORMALIZE_RE_28, "$1")
            .trim(' ', ',', ';', ':', '-')
        return if (value.any(Char::isLetterOrDigit)) value else ""
    }

    /**
     * Splits [text] into pieces made one at a time by the voice. Each piece is said with its own tune
     * (a falling end), so pieces are whole sentences where possible. Only the start is short, for a fast
     * first sound: [firstWords], then 3x that, then [maxWords], so the next piece is ready before the last ends.
     */
    fun chunks(text: String, maxWords: Int = 20, firstWords: Int = maxWords): List<String> {
        require(maxWords > 0) { "maxWords must be positive" }
        require(firstWords > 0) { "firstWords must be positive" }
        val clean = normalize(text)
        if (clean.isBlank()) return emptyList()
        var made = 0
        return clean.split(SENTENCE_END).flatMap { sentence ->
            val words = sentence.split(TextNorm.SPACES)
            val result = mutableListOf<String>()
            var start = 0
            while (start < words.size) {
                val limit = when (made++) {
                    0 -> minOf(firstWords, maxWords)
                    1 -> minOf(firstWords * 3, maxWords)
                    else -> maxWords
                }
                var end = minOf(start + limit, words.size)
                if (end < words.size) {
                    val comma = (start + minOf(12, limit) - 1 until end)
                        .lastOrNull { words[it].endsWith(',') || words[it].endsWith(';') }
                    if (comma != null) end = comma + 1
                }
                result += words.subList(start, end).joinToString(" ")
                start = end
            }
            result
        }
    }

    /**
     * Silence after a piece, like a person breathing at punctuation. The voice's own edge silence is cut
     * (see [PcmShape]), so this is the only gap between pieces.
     */
    fun pauseAfterMs(text: String): Int = when (text.trimEnd().lastOrNull()) {
        '.', '!', '?' -> SENTENCE_PAUSE_MS
        ',', ';', ':' -> CLAUSE_PAUSE_MS
        else -> WORD_PAUSE_MS
    }

    const val SENTENCE_PAUSE_MS = 280
    const val CLAUSE_PAUSE_MS = 140
    const val WORD_PAUSE_MS = 40
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    private fun link(value: String): String = "link" + value.takeLastWhile { it in ".,!?;:" }

    private fun date(match: MatchResult, year: String, month: String, day: String): String = try {
        val date = LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        "${months[date.monthValue - 1]} ${ordinal(date.dayOfMonth.toString())} ${number(year)}"
    } catch (_: DateTimeException) { match.value }

    private fun money(value: String, dollars: Boolean): String {
        val parts = value.replace(",", "").split('.')
        val amount = parts[0]
        val currency = if (dollars) (if (amount.toLongOrNull() == 1L) "dollar" else "dollars") else "taka"
        if (parts.size == 1 || parts[1].length > 2) return "${number(value)} $currency"
        val minor = parts[1].padEnd(2, '0').toLong()
        val minorCurrency = if (dollars) (if (minor == 1L) "cent" else "cents") else "poisha"
        return "${number(amount)} $currency" + if (minor == 0L) "" else " and ${cardinal(minor)} $minorCurrency"
    }

    private fun digits(value: String): String = value.map { small[it - '0'] }.joinToString(" ")

    private fun number(value: String): String {
        val unsigned = value.removePrefix("-").removePrefix("+").replace(",", "")
        val parts = unsigned.split('.')
        val integer = parts[0]
        val spoken = if (integer.length > 1 && integer.startsWith('0')) digits(integer)
            else integer.toLongOrNull()?.let(::cardinal) ?: digits(integer)
        return (if (value.startsWith('-')) "minus " else if (value.startsWith('+')) "plus " else "") +
            spoken + if (parts.size > 1) " point ${digits(parts[1])}" else ""
    }

    private fun ordinal(value: String): String {
        val words = number(value).split(' ').toMutableList()
        val last = words.last()
        words[words.lastIndex] = ordinalEnds[last] ?: if (last.endsWith('y')) last.dropLast(1) + "ieth" else last + "th"
        return words.joinToString(" ")
    }

    private fun cardinal(value: Long): String = when {
        value < 20 -> small[value.toInt()]
        value < 100 -> tens[(value / 10).toInt()] + if (value % 10 == 0L) "" else " ${cardinal(value % 10)}"
        else -> {
            val (scale, name) = when {
                value >= 1_000_000_000_000_000_000L -> 1_000_000_000_000_000_000L to "quintillion"
                value >= 1_000_000_000_000_000L -> 1_000_000_000_000_000L to "quadrillion"
                value >= 1_000_000_000_000L -> 1_000_000_000_000L to "trillion"
                value >= 1_000_000_000L -> 1_000_000_000L to "billion"
                value >= 1_000_000L -> 1_000_000L to "million"
                value >= 1_000L -> 1_000L to "thousand"
                else -> 100L to "hundred"
            }
            "${cardinal(value / scale)} $name" + if (value % scale == 0L) "" else " ${cardinal(value % scale)}"
        }
    }
}
