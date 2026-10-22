package com.alrasin.jarvis.brain

import java.util.Locale
import kotlin.math.ln

object MemoryRetriever {
    enum class Kind { FACT, NOTE }

    data class Memory(val id: Long, val text: String, val timeMs: Long, val kind: Kind = Kind.FACT)
    data class ForgetCandidate(val memory: Memory, val requiresConfirmation: Boolean)

    /** The budget includes newlines between complete memory texts. */
    fun retrieve(question: String, memories: List<Memory>, topK: Int = 4, charBudget: Int = 1200): List<Memory> {
        if (topK <= 0 || charBudget <= 0) return emptyList()
        val result = mutableListOf<Memory>()
        var remaining = charBudget
        for (memory in ranked(question, memories)) {
            val cost = memory.text.length + if (result.isEmpty()) 0 else 1
            if (cost > remaining) continue
            result += memory
            remaining -= cost
            if (result.size == topK) break
        }
        return result
    }

    fun selectForgetting(query: String, memories: List<Memory>, selectedFactId: Long? = null): ForgetCandidate? {
        val facts = memories.filter { it.kind == Kind.FACT }
        val target = normalized(query).replace(FORGET_PREFIX, "").trim()
        if (target in setOf("that", "this", "it", "that fact", "this fact")) {
            return facts.singleOrNull { it.id == selectedFactId }?.let { ForgetCandidate(it, true) }
        }
        if (target.isBlank() || target in setOf("all", "everything")) return null
        // Keep punctuation: passwords such as "secret!" and "secret?" are different facts.
        val exact = facts.filter { normalized(it.text) == target }
        if (exact.isNotEmpty()) {
            return ForgetCandidate(exact.maxBy { it.timeMs }, exact.size != 1)
        }
        val terms = tokens(target).toSet()
        if (terms.isEmpty()) return null
        // A shared generic word (e.g. "password") must not delete an unrelated account.
        val candidates = facts.filter { tokens(it.text).toSet().containsAll(terms) }
        return ranked(target, candidates).firstOrNull()?.let { ForgetCandidate(it, true) }
    }

    private fun ranked(question: String, memories: List<Memory>): List<Memory> {
        val terms = tokens(question).toSet()
        if (terms.isEmpty() || memories.isEmpty()) return emptyList()
        val documents = memories.map { memory -> memory to tokens(memory.text).groupingBy { it }.eachCount() }
        val averageLength = documents.sumOf { it.second.values.sum() }.toDouble().div(documents.size).coerceAtLeast(1.0)
        val frequencies = terms.associateWith { term -> documents.count { term in it.second } }
        return documents.mapNotNull { (memory, counts) ->
            val matched = terms.count { it in counts }
            if (matched == 0) return@mapNotNull null
            val length = counts.values.sum()
            val score = terms.sumOf { term ->
                val frequency = (counts[term] ?: 0).toDouble()
                val documentFrequency = frequencies.getValue(term)
                val idf = ln(1.0 + (documents.size - documentFrequency + 0.5) / (documentFrequency + 0.5))
                idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * length / averageLength))
            } * matched / terms.size
            memory to score
        }.sortedWith(compareByDescending<Pair<Memory, Double>> { it.second }
            .thenByDescending { it.first.timeMs }.thenBy { it.first.kind }.thenBy { it.first.id })
            .map { it.first }
    }

    private fun normalized(text: String) = text.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private fun tokens(text: String): List<String> {
        val clean = normalized(text).replace('’', '\'').replace(WIFI, "wifi")
            .replace(POSSESSIVE, "")
        return WORD.findAll(clean).map { it.value }.filter { it !in STOP_WORDS }
            .map { SYNONYMS[it] ?: it }.toList()
    }

    private val WHITESPACE = Regex("\\s+")
    private val WIFI = Regex("\\bwi[ -]fi\\b")
    private val POSSESSIVE = Regex("'s\\b")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val FORGET_PREFIX = Regex("^(?:please )?(?:forget|delete|remove)(?: about)?\\s+")
    private val STOP_WORDS = setOf(
        "a", "an", "the", "i", "me", "my", "mine", "you", "your", "our", "we", "it", "its",
        "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had",
        "what", "whats", "where", "when", "who", "how", "which", "can", "could", "would", "will",
        "please", "tell", "say", "remember", "recall", "forget", "about", "that", "this", "of", "to",
        "for", "in", "on", "at", "and", "with", "s",
    )
    private val SYNONYMS = mapOf(
        "birthdate" to "birthday", "bday" to "birthday", "birthdays" to "birthday",
        "parked" to "park", "parking" to "park", "parks" to "park",
        "passcode" to "password", "passphrase" to "password", "passwords" to "password",
        "wireless" to "wifi", "automobile" to "car", "vehicle" to "car",
        "mom" to "mother", "mum" to "mother", "dad" to "father",
        "residence" to "home", "house" to "home", "office" to "work",
    )
}
