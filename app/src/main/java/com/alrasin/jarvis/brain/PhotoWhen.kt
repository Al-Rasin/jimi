package com.alrasin.jarvis.brain

import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit

/** Spoken time words for photo search ("last week", "march", "the last 3 days") → date range. Pure Kotlin: unit tested. */
object PhotoWhen {
    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december"
    const val WHEN = "today|yesterday|(?:this|last|past) (?:week|month|year)|(?:the )?(?:last|past) \\d+ (?:days?|weeks?|months?)|(?:$MONTHS)(?: \\d{4})?|(?:19|20)\\d{2}"
    private val WHEN_START = Regex("^(?:the )?($WHEN)\\b(?: (?:in|at|from) )?(.*)$")
    private val WHEN_END = Regex("^(.*?) (?:in |from |during |on )?(?:the )?($WHEN)$")
    private val LAST_N = Regex("^(?:the )?(?:last|past) (\\d+) (day|week|month)s?$")
    private val MONTH_YEAR = Regex("^($MONTHS)(?: (\\d{4}))?$")

    /** Tail after "photos from …" → (time words, place). Either may be null. */
    fun split(tail: String): Pair<String?, String?> {
        val t = tail.trim()
        WHEN_START.find(t)?.let { return it.groupValues[1] to place(it.groupValues[2]) }
        WHEN_END.find(t)?.let { return it.groupValues[2] to place(it.groupValues[1]) }
        return null to place(t)
    }

    private fun place(s: String): String? =
        s.trim().removePrefix("in ").removePrefix("at ").removePrefix("from ").removePrefix("the ").trim()
            .takeIf { it.isNotEmpty() && it !in setOf("my phone", "phone", "gallery", "my gallery", "camera") }

    /** Inclusive date range, or null when the words are not a time. */
    fun range(said: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val w = said.trim().removePrefix("the ").trim()
        return when (w) {
            "today" -> today to today
            "yesterday" -> today.minusDays(1).let { it to it }
            "this week", "last week", "past week" -> today.minusDays(7) to today
            "this month" -> today.withDayOfMonth(1) to today
            "last month", "past month" -> today.minusMonths(1).withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
            "this year" -> today.withDayOfYear(1) to today
            "last year", "past year" -> today.minusYears(1).withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
            else -> LAST_N.find(w)?.let {
                val n = it.groupValues[1].toLong()
                val unit = when (it.groupValues[2]) { "day" -> ChronoUnit.DAYS; "week" -> ChronoUnit.WEEKS; else -> ChronoUnit.MONTHS }
                today.minus(n, unit) to today
            } ?: MONTH_YEAR.find(w)?.let {
                val month = Month.valueOf(it.groupValues[1].uppercase())
                val year = it.groupValues[2].toIntOrNull() ?: if (month.value > today.monthValue) today.year - 1 else today.year
                val start = LocalDate.of(year, month, 1)
                start to start.plusMonths(1).minusDays(1)
            } ?: w.toIntOrNull()?.takeIf { it in 1900..2100 }?.let { LocalDate.of(it, 1, 1) to LocalDate.of(it, 12, 31) }
        }
    }
}

/** Spoken file kind → extension group for find_file. */
object FileKind {
    fun of(said: String): String? = when {
        "pdf" in said -> "pdf"
        "word" in said || said == "doc" -> "doc"
        "excel" in said || "sheet" in said -> "sheet"
        "presentation" in said || "slides" in said -> "slides"
        "text" in said -> "text"
        else -> null
    }

    fun extensions(kind: String?): Set<String> = when (kind) {
        "pdf" -> setOf("pdf")
        "doc" -> setOf("doc", "docx", "odt", "rtf")
        "sheet" -> setOf("xls", "xlsx", "csv", "ods")
        "slides" -> setOf("ppt", "pptx", "odp")
        "text" -> setOf("txt", "md")
        else -> setOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp", "txt", "md", "epub")
    }

    fun mime(ext: String): String = when (ext.lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "csv" -> "text/csv"
        "txt", "md" -> "text/plain"
        "epub" -> "application/epub+zip"
        "rtf" -> "application/rtf"
        else -> "*/*"
    }

    private val WORD_SPLIT = Regex("[^a-z0-9]+")
    private val SKIP = setOf("the", "a", "an", "my", "of", "and", "for", "about", "on", "to", "in")

    fun words(text: String): List<String> = text.lowercase().split(WORD_SPLIT).filter { it.length >= 2 && it !in SKIP }

    /** How many query words appear in [text] (0 = no match). A word matches by prefix ("tax" in "taxes"). */
    fun score(query: String, text: String): Int {
        val have = words(text).map(::stem)
        return words(query).map(::stem).count { q -> have.any { it.startsWith(q) || (it.length >= 3 && q.startsWith(it)) } }
    }

    /** "taxes" → "tax", "bills" → "bill", so plural and singular match. */
    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("es") && w[w.length - 3] in "sxzh" -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }
}
