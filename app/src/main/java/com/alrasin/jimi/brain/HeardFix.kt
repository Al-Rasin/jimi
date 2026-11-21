package com.alrasin.jimi.brain

/**
 * Speech-to-text slips ("why the sky colour display" for "why is the sky blue"): the cloud model answers the real
 * question anyway, so it also returns what the user most likely said and the chat shows that (D-097).
 */
object HeardFix {
    private const val EXAMPLE = "e.g. \"why the sky colour display\" was really \"why is the sky blue\""
    const val TOOL_HINT = "The command comes from speech recognition, so words are often misheard ($EXAMPLE). " +
        "If any words look wrong or the grammar is broken, also add \"heard\":\"<what the user most likely said>\" to the JSON. "
    const val CHAT_HINT = "\nThe user's words come from speech recognition, so words are often misheard ($EXAMPLE). " +
        "If any words look wrong or the grammar is broken, your reply MUST start with [heard: <what the user most likely said>] " +
        "and then the answer. If the words are fine, do not add it."

    private val VOICE_SOURCES = setOf("voice", "barge_in", "follow_up")

    fun isVoice(source: String) = source in VOICE_SOURCES

    /** [fixed] if it is a real, sane change of [original]; else null. */
    fun accept(original: String, fixed: String?): String? {
        val f = fixed?.trim()?.trim('"')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (f.length > original.length * 2 + 20) return null
        return f.takeIf { norm(it) != norm(original) }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Strips a leading "[heard: …]" from a streamed reply; [heard] holds it once seen. */
    class StreamPrefix {
        var heard: String? = null
            private set
        private var pending = ""
        private var decided = false
        private var afterTag = false

        /** Text safe to pass on, or "" while the start is still unknown. */
        fun feed(piece: String): String {
            if (afterTag) {
                val rest = piece.trimStart()
                if (rest.isNotEmpty()) afterTag = false
                return rest
            }
            if (decided) return piece
            pending += piece
            val t = pending.trimStart()
            if (t.isEmpty()) return ""
            if (!t.startsWith("[")) return release(pending)
            val tag = "[heard:"
            if (t.length < tag.length) return if (tag.startsWith(t.lowercase())) "" else release(pending)
            if (!t.lowercase().startsWith(tag)) return release(pending)
            val end = t.indexOf(']')
            if (end < 0) return if (t.length > MAX_TAG) release(pending) else ""
            heard = t.substring(tag.length, end).trim()
            val rest = t.substring(end + 1).trimStart()
            afterTag = rest.isEmpty()
            return release(rest)
        }

        /** Whatever was held back when the stream ends. */
        fun flush(): String = if (decided) "" else release(pending)

        private fun release(s: String): String {
            decided = true
            pending = ""
            return s
        }

        private companion object { const val MAX_TAG = 300 }
    }

    /** Non-streamed form: (heard, answer). */
    fun split(reply: String): Pair<String?, String> {
        val p = StreamPrefix()
        val out = p.feed(reply) + p.flush()
        return p.heard to out
    }
}
