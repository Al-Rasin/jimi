package com.alrasin.jarvis.voice

/**
 * Known STT mishears seen on the phone (ROADMAP §M.1). Each fix needs its command context,
 * so normal dictation ("two more minutes", "shark facts") stays as it was heard.
 */
object HeardFixes {
    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val alarmContext = Regex("\\b(?:alarm|alum|lum|remind|wake)\\b", ic)
    private val twoMore = Regex("\\btwo more\\b", ic)
    private val alarmAfterSet = Regex("\\b((?:set|create|make|add)\\s+(?:an\\s+|a\\s+|my\\s+)?)a?lum\\b", ic)
    private val alarmAtStart = Regex("^a?lum\\b(?=\\s+(?:at|for|to|in|tomorrow|\\d))", ic)
    /** Typed text, message bodies, notes and searches are kept word for word. */
    private val dictation = Regex("^(?:type|note|text|message|sms|send|write|search|google|reply)\\b|\\b(?:saying|that says)\\b", ic)
    private val debugging = Regex("\\b(?:wireless\\s+)?(?:divagging|diwangi|dewagging)\\b", ic)
    private val search = Regex("^shark(?=\\s+(?:for|on)\\s|\\s+.+\\s+(?:in|on)\\s+(?:youtube|google|chrome|play store)$)", ic)
    private val unlock = Regex("^unbox(?=$|\\s+(?:the\\s+|my\\s+)?(?:phone|screen|device)$)", ic)

    fun apply(text: String): String {
        if (dictation.containsMatchIn(text)) return text
        var t = joinSpelledLetters(text)
        t = debugging.replace(t, "wireless debugging")
        t = alarmAfterSet.replace(t) { "${it.groupValues[1]}alarm" }
        t = alarmAtStart.replace(t, "alarm")
        if (alarmContext.containsMatchIn(t)) t = twoMore.replace(t, "tomorrow")
        t = search.replace(t, "search")
        t = unlock.replace(t, "unlock")
        return t
    }

    /** "call m o m" → "call mom": a run of 3+ single letters is a spelled word. */
    private fun joinSpelledLetters(text: String): String {
        val words = text.split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var end = i
            while (end < words.size && words[end].length == 1 && words[end][0].isLetter()) end++
            if (end - i >= 3) {
                out += words.subList(i, end).joinToString("")
                i = end
            } else {
                out += words[i]
                i++
            }
        }
        return out.joinToString(" ")
    }
}
