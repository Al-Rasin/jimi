package com.alrasin.jarvis.llm

import com.alrasin.jarvis.brain.Exchange

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
        Regex("[.!?](\\s|$)").findAll(buf).forEach { m ->
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

    /** Speech can start at a clause boundary while the rest of the sentence is generated. */
    fun speechChunks(buf: String): Pair<List<String>, String> {
        val visible = buf.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        if (visible.contains("<think>") || Regex("<[^>]*$").containsMatchIn(visible)) {
            return emptyList<String>() to visible
        }
        val (sentences, pending) = sentences(visible)
        val out = sentences.toMutableList()
        var start = 0
        Regex("[,;:]\\s+").findAll(pending).forEach { boundary ->
            val clause = pending.substring(start, boundary.range.first + 1).trim()
            if (clause.split(Regex("\\s+")).size >= 5) {
                out.add(clause)
                start = boundary.range.last + 1
            }
        }
        val remainder = pending.substring(start)
        val words = Regex("\\S+\\s+").findAll(remainder).take(5).toList()
        if (words.size == 5) {
            val end = words.last().range.last + 1
            out.add(remainder.substring(0, end).trim())
            return out to remainder.substring(end)
        }
        return out to remainder
    }

    /** Drops think blocks, chat control tokens and "/no_think" echoes so they are never spoken or shown (§M.10). */
    fun clean(text: String) = text.replace(Regex("<think>.*?(?:</think>|$)", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|im_start\\|>\\s*(?:system|user|assistant)?|</?think>|<\\|[^|>]*\\|>|</?(?:start|end)_of_turn>|<bos>|<eos>"), " ")
        .replace(Regex("(?i)/\\s?(?:no_?think|think)\\b"), " ")
        .replace(Regex("[*#`_]"), "").replace(Regex("\\s+"), " ").trim()
}
