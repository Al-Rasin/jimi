package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/** RuleMatcher photo and file search rules (ROADMAP #35): "photos from last week", "find my pdf about taxes". */
internal object FileRules {
    private fun call(tool: String, vararg args: Pair<String, String?>) = RuleMatcher.call(tool, *args)

    private const val PICS = "photos|photo|pictures|picture|pics|pic|images|image|photographs|photograph"
    private const val VERB = "(?:(?:show|find|open|search|search for|get|display|look for)(?: me)? )?"
    private val PHOTO_RE = Regex("^$VERB(?:(?:all|my|the) )*(?:$PICS)(?: (?:i took|taken|i clicked|i shot))? (?:from|in|at|on|during) (.+)$")
    private val PHOTO_WHEN_FIRST_RE = Regex("^$VERB(?:my )?(${PhotoWhen.WHEN})s? (?:$PICS)(?: (?:from|in|at) (.+))?$")
    private const val KIND = "pdf|document|doc|file|word file|word document|excel file|excel sheet|spreadsheet|sheet|presentation|slides|text file"
    private val FILE_ABOUT_RE = Regex("^(?:find|search for|search|look for|open|where is|show me|get)(?: me)? (?:my |the |a )?($KIND)s? (?:about|called|named|on|for|with|of) (.+)$")
    private val FILE_NAMED_RE = Regex("^(?:find|search for|look for|open|where is|show me|get)(?: me)? (?:my |the )?(.+?) ($KIND)$")
    private val NOT_QUERY = Regex("^(?:a|an|the|my|this|that|new|last|recent|latest|some|any)$")

    fun files(t: String): ToolCall? {
        PHOTO_WHEN_FIRST_RE.find(t)?.let { return photos(it.groupValues[1] + (it.groupValues[2].takeIf(String::isNotEmpty)?.let { p -> " in $p" } ?: "")) }
        PHOTO_RE.find(t)?.let { return photos(it.groupValues[1]) }
        FILE_ABOUT_RE.find(t)?.let { return file(it.groupValues[2], it.groupValues[1]) }
        FILE_NAMED_RE.find(t)?.let { m ->
            val q = m.groupValues[1]
            if (q.matches(NOT_QUERY)) return null
            return file(q, m.groupValues[2])
        }
        return null
    }

    private fun photos(tail: String): ToolCall? {
        val (whenSaid, place) = PhotoWhen.split(tail)
        if (whenSaid == null && place == null) return null
        return call("find_photos", "when" to whenSaid, "place" to place)
    }

    private fun file(query: String, kind: String): ToolCall? {
        val q = query.trim().removePrefix("the ").trim()
        if (q.isEmpty() || q.matches(NOT_QUERY)) return null
        return call("find_file", "query" to q, "type" to FileKind.of(kind))
    }
}
