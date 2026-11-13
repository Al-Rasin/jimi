package com.alrasin.jarvis.knowledge

import com.alrasin.jarvis.brain.PageRanker
import kotlin.math.sqrt

/**
 * Picks the text that best answers a question (ROADMAP #42, §K 11.3). Uses the small ONNX [Embedder] when its
 * model is on the phone, else keyword ranking ([PageRanker]). Null when nothing matches, so the caller uses the intro.
 */
class ChunkRanker(private val embedder: () -> Embedder? = { null }) {
    fun best(query: String, paragraphs: List<String>): String? {
        if (paragraphs.isEmpty() || words(query).isEmpty()) return null
        val emb = runCatching { embedder() }.getOrNull()
        val scores = emb?.let { e -> runCatching { e.similarities(query, paragraphs) }.getOrNull() }
        if (scores != null) {
            val i = scores.indices.maxByOrNull { scores[it] } ?: return null
            return paragraphs[i].takeIf { scores[i] >= MIN_COSINE }
        }
        return PageRanker.rank(query, paragraphs.joinToString("\n\n"), "", topN = 1, maxChars = MAX_CHARS).firstOrNull()?.text
    }

    companion object {
        private const val MIN_COSINE = 0.25f
        private const val MAX_CHARS = 700

        private val STOP = setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "of", "in", "on", "at", "to", "for", "and", "or",
            "what", "whats", "who", "whom", "which", "when", "where", "why", "how", "does", "did", "do", "it", "its", "this",
            "that", "tell", "me", "about", "please", "can", "could", "you", "i", "my", "by", "with", "from", "as",
            "hey", "jimi", "wikipedia", "wiki", "look", "up", "search", "say", "says",
        )

        /** Meaningful lower-case words of [s] (no filler like "what is", "tell me about"). */
        fun words(s: String): List<String> =
            Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.filter { it !in STOP && it.length > 1 }.toList()

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
        }
    }
}
