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
            if (m.range.last + 1 < buf.length || buf.endsWith("\n")) {
                val s = prefix.trim()
                if (s.length > 1) out.add(s)
                start = m.range.last + 1
            }
        }
        return out to buf.substring(start)
    }

    fun clean(text: String) = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("[*#`_]"), "").replace(Regex("\\s+"), " ").trim()
}
