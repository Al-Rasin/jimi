package com.alrasin.jarvis.voice

/**
 * Speech-to-text accuracy for the phone benchmark (ROADMAP #48, B §K 0.3/0.4). A clip `x.wav` may have `x.txt`
 * beside it with what was really said; the speech test then logs word error rate and writes one summary row.
 * Pure so it is unit tested.
 */
object SpeechScore {
    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}' ]")
    private val SPACES = Regex("\\s+")

    fun words(text: String): List<String> =
        text.lowercase().replace(NON_WORD, " ").replace(SPACES, " ").trim().split(' ').filter { it.isNotEmpty() }

    /** Word edits (substitute, insert, delete) to turn [heard] into [expected]. */
    fun edits(expected: List<String>, heard: List<String>): Int {
        var prev = IntArray(heard.size + 1) { it }
        for (i in 1..expected.size) {
            val cur = IntArray(heard.size + 1)
            cur[0] = i
            for (j in 1..heard.size) {
                val sub = prev[j - 1] + if (expected[i - 1] == heard[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[heard.size]
    }

    /** Word error rate 0..∞ (1.0 = as many errors as words). Empty expected text scores 0 only if nothing was heard. */
    fun wer(expected: String, heard: String): Float {
        val e = words(expected)
        val h = words(heard)
        if (e.isEmpty()) return if (h.isEmpty()) 0f else 1f
        return edits(e, h).toFloat() / e.size
    }

    data class Clip(val name: String, val seconds: Float, val rtf: Float, val wer: Float?)

    /** One Markdown table row for docs/BENCHMARKS.md: model | clips | avg rtf | WER over scored clips | load. */
    fun summaryRow(model: String, loadMs: Long, clips: List<Clip>): String {
        val rtf = if (clips.isEmpty()) 0f else clips.sumOf { it.rtf.toDouble() * it.seconds }.toFloat() /
            clips.sumOf { it.seconds.toDouble() }.toFloat().coerceAtLeast(0.001f)
        val scored = clips.mapNotNull { it.wer }
        val wer = if (scored.isEmpty()) "—" else "${fmt("%.0f", scored.average() * 100)}% (${scored.size})"
        return "| $model | ${clips.size} | ${fmt("%.2f", rtf)} | $wer | ${fmt("%.1f", loadMs / 1000f)} s |"
    }

    private fun fmt(pattern: String, v: Number) = String.format(java.util.Locale.ROOT, pattern, v)

    const val HEADER = "| Model | Clips | rtf | WER (scored) | Load |\n|---|---|---|---|---|"
}
