package com.alrasin.jarvis.brain

/** "twenty five" → "25", "a hundred" → "100", "one" → "1". Works on normalized (lower case, no punctuation) text. */
object NumberWords {
    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    fun normalize(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            // "oh" only counts as 0 inside a time ("seven oh five"), not "oh no"
            if (w == "oh" && !(out.lastOrNull()?.all(Char::isDigit) == true && words.getOrNull(i + 1) in UNITS)) {
                out.add(w); i++; continue
            }
            var value: Int? = null
            var j = i
            if (w in TENS) {
                value = TENS.getValue(w); j++
                val u = words.getOrNull(j)?.let(UNITS::get)
                if (u != null && u in 1..9) { value += u; j++ }
            } else if (w in UNITS) {
                value = UNITS.getValue(w); j++
            } else if ((w == "a" || w == "one") && words.getOrNull(i + 1) == "hundred") {
                value = 1
                j++
            }
            if (value != null && words.getOrNull(j) == "hundred") {
                value *= 100; j++
                if (words.getOrNull(j) == "and") j++
                val t = words.getOrNull(j)
                if (t in TENS) {
                    value += TENS.getValue(t!!); j++
                    val u = words.getOrNull(j)?.let(UNITS::get)
                    if (u != null && u in 1..9) { value += u; j++ }
                } else if (t in UNITS && t != "oh") { value += UNITS.getValue(t!!); j++ }
            }
            if (value == null) { out.add(w); i++; continue }
            out.add(value.toString())
            i = j
        }
        return out.joinToString(" ")
    }
}
