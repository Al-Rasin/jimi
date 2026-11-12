package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.TextNorm
import java.util.Locale
import kotlin.math.ln

object PageRanker {
    data class Chunk(val text: String, val sourceUrl: String, val score: Double)

    private val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
    private val sentenceEnd = Regex("(?<=[.!?])\\s+")
    private val stopWords = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "from", "with",
        "by", "as", "is", "are", "was", "were", "be", "been", "it", "its", "this", "that",
        "these", "those", "i", "me", "my", "you", "your", "we", "our", "they", "their",
        "what", "when", "where", "who", "why", "how", "which", "can", "could", "would",
        "do", "does", "did", "have", "has", "had", "please", "tell", "know", "about", "hey", "jimi",
    )
    private val navigation = setOf(
        "home", "menu", "main menu", "navigation", "search", "sign in", "log in", "login",
        "subscribe", "newsletter", "advertisement", "advertisements", "skip to content",
        "privacy policy", "terms of use", "terms and conditions", "all rights reserved",
        "accept all", "reject all", "manage preferences", "cookie settings", "print recipe",
        "jump to recipe", "share this article", "follow us",
    )
    private val boilerplate = Regex(
        "^(?:(?:we|this (?:site|website)) (?:use|uses) cookies\\b|" +
            "(?:accept|reject|manage) (?:all )?cookies\\b|cookie (?:policy|preferences|consent)\\b|" +
            "subscribe (?:to|now|for)\\b|sign up (?:for|to)\\b|" +
            "(?:menu|navigation)\\s*[:|]|home\\s*[|›>]).*",
    )
    private val months = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "jan", "feb", "mar", "apr",
        "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    /** maxChars counts returned text only; callers must budget source labels/separators separately. */
    fun rank(
        question: String,
        pageText: String,
        sourceUrl: String,
        title: String = "",
        topN: Int = 3,
        maxChars: Int = 3000,
    ): List<Chunk> {
        if (topN <= 0 || maxChars <= 0) return emptyList()
        val questionTokens = tokens(question)
        val meaningfulQuestion = questionTokens.filterNot { it in stopWords }
        val query = meaningfulQuestion.distinct()
        if (query.isEmpty()) return emptyList()
        val chunks = splitChunks(pageText)
        if (chunks.isEmpty()) return emptyList()
        val allTerms = chunks.map(::tokens)
        val terms = allTerms.map { it.filterNot { word -> word in stopWords } }
        val frequencies = terms.map { it.groupingBy { word -> word }.eachCount() }
        val averageLength = terms.sumOf { it.size }.toDouble().div(terms.size).coerceAtLeast(1.0)
        val documentFrequency = query.associateWith { term -> frequencies.count { term in it } }
        val titleTerms = tokens(title).filterNot { it in stopWords }.toSet()
        val dateTerms = query.filter { term -> term.any(Char::isDigit) || term in months }
        val phrase = meaningfulQuestion.joinToString(" ")
        val literalPhrase = questionTokens.joinToString(" ")
        val ranked = chunks.indices.mapNotNull { index ->
            val counts = frequencies[index]
            if (query.none { it in counts }) return@mapNotNull null
            var score = query.sumOf { term ->
                val frequency = counts[term]?.toDouble() ?: return@sumOf 0.0
                val df = documentFrequency.getValue(term)
                val idf = ln(1.0 + (chunks.size - df + 0.5) / (df + 0.5))
                idf * (frequency * 2.2) / (frequency + 1.2 *
                    (0.25 + 0.75 * terms[index].size / averageLength))
            }
            val normalized = " ${terms[index].joinToString(" ")} "
            val literalText = " ${allTerms[index].joinToString(" ")} "
            if ((meaningfulQuestion.size > 1 && " $phrase " in normalized) ||
                (questionTokens.size > 1 && " $literalPhrase " in literalText)
            ) score += 0.35
            score += 0.15 * dateTerms.count { it in counts }
            score += 0.10 * titleTerms.count { it in counts }.coerceAtMost(3)
            index to score
        }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        val result = mutableListOf<Chunk>()
        var remaining = maxChars
        for ((index, score) in ranked) {
            if (result.size >= topN || remaining <= 0) break
            val text = fit(chunks[index], remaining, query)
            if (text.isEmpty()) continue
            result += Chunk(text, sourceUrl, score)
            remaining -= text.length
        }
        return result
    }

    private fun tokens(text: String): List<String> =
        words.findAll(text.lowercase(Locale.ROOT)).map { it.value.replace('’', '\'') }.toList()

    private fun splitChunks(text: String): List<String> {
        val result = mutableListOf<String>()
        val pending = mutableListOf<String>()
        var count = 0
        fun flush() {
            if (pending.isNotEmpty()) result += pending.joinToString(" ")
            pending.clear()
            count = 0
        }
        for (rawLine in text.lineSequence()) {
            val line = rawLine.replace('\u00a0', ' ').trim().replace(TextNorm.SPACES, " ")
            if (line.isEmpty()) {
                if (count >= 60) flush()
                continue
            }
            val lower = line.lowercase(Locale.ROOT).trimEnd('.', ':', '!')
            val lineTokens = tokens(line)
            if (lower in navigation || boilerplate.matches(lower)) continue
            // Keep short ingredient/measurement lines such as "2 eggs" and "200 C".
            if (lineTokens.size < 3 && !(lineTokens.size >= 2 && line.any(Char::isDigit))) continue
            for (sentence in sentenceEnd.split(line)) {
                val sentenceWords = sentence.split(' ').filter { it.isNotEmpty() }
                for (piece in sentenceWords.chunked(120)) {
                    if (count + piece.size > 120) flush()
                    pending += piece.joinToString(" ")
                    count += piece.size
                    if (count >= 90) flush()
                }
            }
        }
        flush()
        return result
    }

    private fun fit(text: String, budget: Int, query: List<String>): String {
        if (text.length <= budget) return text
        val sentences = sentenceEnd.split(text)
        val relevant = sentences.firstOrNull { sentence -> tokens(sentence).any { it in query } }
            ?: return ""
        val start = text.indexOf(relevant)
        val tail = text.substring(start)
        val complete = sentenceEnd.split(tail).runningFold("") { acc, sentence ->
            if (acc.isEmpty()) sentence else "$acc $sentence"
        }.lastOrNull { it.isNotEmpty() && it.length <= budget }
        if (complete != null) return complete
        // A narrow budget starts at a matching word so clipping never returns unrelated context.
        val match = words.findAll(relevant).firstOrNull {
            it.value.lowercase(Locale.ROOT).replace('’', '\'') in query
        } ?: return ""
        if (match.value.length > budget) return ""
        val window = relevant.substring(match.range.first)
        if (window.length <= budget) return window
        val cut = window.lastIndexOf(' ', budget)
        return if (cut > 0) window.substring(0, cut).trimEnd() else ""
    }
}
