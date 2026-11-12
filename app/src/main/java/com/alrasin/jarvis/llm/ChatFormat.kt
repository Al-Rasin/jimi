package com.alrasin.jarvis.llm

import com.alrasin.jarvis.brain.Exchange

// Regexes built once, not on every call (ROADMAP §L.4).
private val SENTENCES_RE = Regex("[.!?](\\s|$)")
private val SPEECH_CHUNKS_RE = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
private val SPEECH_CHUNKS_RE_2 = Regex("<[^>]*$")
private val SPEECH_CHUNKS_RE_3 = Regex("[,;:]\\s+")
private val SPEECH_CHUNKS_RE_4 = Regex("\\s+")
private val SPEECH_CHUNKS_RE_5 = Regex("\\S+\\s+")
private val CLEAN_RE = Regex("<think>.*?(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CLEAN_RE_2 = Regex("<\\|im_start\\|>\\s*(?:system|user|assistant)?|</?think>|<\\|[^|>]*\\|>|</?(?:start|end)_of_turn>|<bos>|<eos>")
private val CLEAN_RE_3 = Regex("(?i)/\\s?(?:no_?think|think)\\b")
private val CLEAN_RE_4 = Regex("[*#`_]")

/** ChatML for Qwen3 (PLAN 9.3). "/no_think" + empty think block = answer directly (fast). */
object ChatFormat {
    private val abbreviation = Regex(
        "\\b(?:dr|mr|mrs|ms|prof|sr|jr|st|vs|etc|e\\.g|i\\.e|a\\.m|p\\.m|[a-z])\\.$",
        RegexOption.IGNORE_CASE,
    )
    fun lfm(system: String, history: List<Exchange>, user: String): String = buildString {
        append("<|startoftext|><|im_start|>system\n$system<|im_end|>\n")
        history.takeLast(3).forEach {
            append("<|im_start|>user\n${it.user}<|im_end|>\n<|im_start|>assistant\n${it.reply}<|im_end|>\n")
        }
        append("<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n")
    }

    fun qwen(system: String, history: List<Exchange>, user: String): String = buildString {
        append("<|im_start|>system\n$system<|im_end|>\n")
        history.takeLast(3).forEach {
            append("<|im_start|>user\n${it.user}<|im_end|>\n<|im_start|>assistant\n${it.reply}<|im_end|>\n")
        }
        append("<|im_start|>user\n$user /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    /** Splits finished sentences off a growing buffer. Returns (sentences, rest). */
    fun sentences(buf: String): Pair<List<String>, String> {
        val out = ArrayList<String>()
        var start = 0
        SENTENCES_RE.findAll(buf).forEach { m ->
            val prefix = buf.substring(start, m.range.first + 1)
            val finalLine = buf.endsWith("\n") && buf.substring(m.range.last + 1).isBlank()
            if (buf[m.range.first] == '.' && abbreviation.containsMatchIn(prefix) && !finalLine) {
                return@forEach
            }
            if (m.value.last().isWhitespace()) {
                val s = prefix.trim()
                if (s.length > 1) out.add(s)
                start = m.range.last + 1
            }
        }
        return out to buf.substring(start)
    }

    /**
     * Speech can start at a clause boundary while the rest of the sentence is generated. Before anything is
     * [spoke]n, 5 words are enough (fast first sound); after that pieces are longer, because every piece is
     * said with its own tune and many small ones sound broken. A forced cut lands before a joining word
     * ("and", "to", "which"...) so each piece ends where a person would breathe, not mid-phrase.
     */
    fun speechChunks(buf: String, spoke: Boolean = false): Pair<List<String>, String> {
        val clauseWords = if (spoke) 6 else 5
        val cutWords = if (spoke) 16 else 5
        val visible = buf.replace(SPEECH_CHUNKS_RE, "")
        if (visible.contains("<think>") || SPEECH_CHUNKS_RE_2.containsMatchIn(visible)) {
            return emptyList<String>() to visible
        }
        val (sentences, pending) = sentences(visible)
        val out = sentences.toMutableList()
        var start = 0
        SPEECH_CHUNKS_RE_3.findAll(pending).forEach { boundary ->
            val clause = pending.substring(start, boundary.range.first + 1).trim()
            if (clause.split(SPEECH_CHUNKS_RE_4).size >= clauseWords) {
                out.add(clause)
                start = boundary.range.last + 1
            }
        }
        val remainder = pending.substring(start)
        val words = SPEECH_CHUNKS_RE_5.findAll(remainder).take(cutWords).toList()
        if (words.size == cutWords) {
            val end = phraseCut(words, spoke)
            out.add(remainder.substring(0, end).trim())
            return out to remainder.substring(end)
        }
        return out to remainder
    }

    /** Words that start a new phrase; a forced cut just before one sounds like a natural breath. */
    private val JOINERS = setOf(
        "and", "but", "or", "so", "because", "which", "that", "who", "when", "while", "if", "then",
        "with", "to", "for", "in", "on", "at", "from",
    )

    /** End offset of a forced cut: before the last joining word (index 3+) once speaking, else after all words. */
    private fun phraseCut(words: List<MatchResult>, spoke: Boolean): Int {
        if (spoke) {
            val joiner = (words.size - 1 downTo 3).firstOrNull {
                words[it].value.trim().lowercase().trim { c -> !c.isLetter() } in JOINERS
            }
            if (joiner != null) return words[joiner].range.first
        }
        return words.last().range.last + 1
    }

    /** Drops think blocks, chat control tokens and "/no_think" echoes so they are never spoken or shown (§M.10). */
    fun clean(text: String) = text.replace(CLEAN_RE, "")
        .replace(CLEAN_RE_2, " ")
        .replace(CLEAN_RE_3, " ")
        .replace(CLEAN_RE_4, "").replace(SPEECH_CHUNKS_RE_4, " ").trim()
}
