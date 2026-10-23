package com.alrasin.jarvis.actions

import java.util.Locale
import java.text.Normalizer

object ContactResolver {
    sealed interface Resolution {
        data class Found(val contact: Contacts.Contact) : Resolution
        data class Ambiguous(val query: String, val candidates: List<Contacts.Contact>) : Resolution {
            val question: String get() = ContactResolver.question(query, candidates)
        }
        data object NotFound : Resolution
    }

    fun resolve(query: String, contacts: List<Contacts.Contact>): Resolution {
        number(query)?.let { return Resolution.Found(Contacts.Contact(it, it)) }
        val q = queryName(query)
        if (q.isEmpty()) return Resolution.NotFound
        val available = contacts.filter { number(it.number) != null && clean(it.name).isNotEmpty() }
            .groupBy { clean(it.name) to number(it.number) }.values.map { rows ->
                rows.first().copy(aliases = rows.flatMap { it.aliases }.distinct())
            }
            .sortedWith(compareBy({ clean(it.name) }, { number(it.number) }))
        val names = available.filter { contact ->
            contactNames(contact).any { matches(q, it) || relationMatch(q, it) || phoneticMatch(q, it) }
        }
        val matches = names.ifEmpty {
            if (q.split(' ').any { it in relations }) emptyList()
            else available.filter { contact -> contactNames(contact).any { Fuzzy.score(q, it) >= 0.65 } }
        }
        val best = wholeName(q, matches)
        return when (best.size) {
            0 -> Resolution.NotFound
            1 -> Resolution.Found(best.single())
            else -> Resolution.Ambiguous(query.trim(), best)
        }
    }

    /**
     * A multi-word name that is a contact's whole name beats names that only contain it:
     * "mother g" (heard "madar ji") is "Mother G", not "Choto Ammu G". One word ("Rahim") still asks.
     */
    private fun wholeName(q: String, matches: List<Contacts.Contact>): List<Contacts.Contact> {
        if (matches.size < 2 || ' ' !in q) return matches
        val key = relationKey(q)
        return matches.filter { c -> contactNames(c).any { relationKey(it) == key } }.ifEmpty { matches }
    }

    private fun relationKey(name: String) = name.split(' ').map { relations[it] ?: it }

    fun choose(reply: String, candidates: List<Contacts.Contact>): Contacts.Contact? {
        if (Regex("^\\s*-\\s*[0-9]").containsMatchIn(reply)) return null
        number(reply)?.let { chosen ->
            return candidates.filter { number(it.number) == chosen }.singleOrNull()
        }
        val text = clean(reply).removeSuffix(" please").trim()
        val ordinal = Regex("^(?:the |number |option )?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|one|two|three|four|five|six|seven|eight|nine|ten|[0-9]+(?:st|nd|rd|th)?)(?: one| contact| person)?$")
            .matchEntire(text)?.groupValues?.get(1)
        if (ordinal != null) {
            val index = when (ordinal) {
                "first", "one" -> 0
                "second", "two" -> 1
                "third", "three" -> 2
                "fourth", "four" -> 3
                "fifth", "five" -> 4
                "sixth", "six" -> 5
                "seventh", "seven" -> 6
                "eighth", "eight" -> 7
                "ninth", "nine" -> 8
                "tenth", "ten" -> 9
                "last" -> candidates.lastIndex
                else -> ordinal.takeWhile { it.isDigit() }.toIntOrNull()?.minus(1) ?: return null
            }
            return candidates.getOrNull(index)
        }
        if (text.isEmpty()) return null
        val exact = candidates.filter { clean(it.name) == text }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val q = queryName(text)
        if (q.isEmpty()) return null
        return wholeName(q, candidates.filter { contact ->
            contactNames(contact).any { matches(q, it) || relationMatch(q, it) }
        }).singleOrNull()
    }

    fun question(query: String, candidates: List<Contacts.Contact>): String {
        val repeatedNames = candidates.groupingBy { clean(it.name) }.eachCount()
        val labels = candidates.map {
            if (repeatedNames[clean(it.name)]!! > 1) "${it.name} (number ending ${it.number.filter(Char::isDigit).takeLast(4)})"
            else it.name
        }
        val choices = when (labels.size) {
            0 -> return "Which contact did you mean?"
            1 -> labels.single()
            2 -> labels.joinToString(" or ")
            else -> labels.dropLast(1).joinToString(", ") + ", or " + labels.last()
        }
        return "Which ${query.trim()}: $choices?"
    }

    fun number(text: String): String? {
        val trimmed = text.trim()
        if (!Regex("^\\+?[0-9(][0-9\\s().-]*$").matches(trimmed)) return null
        val digits = trimmed.filter(Char::isDigit)
        if (digits.length < 5) return null
        return if (trimmed.startsWith('+')) "+$digits" else digits
    }

    private fun clean(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    private fun queryName(text: String) = spoken(
        clean(text).replace(Regex("^(?:no |not that )?(?:i said |i mean |i meant |i told you )?(?:to )?"), "")
            .removePrefix("my ").removePrefix("amar "),
    )

    /**
     * Speech fixes for names: spelled letters ("m o t h e r" → "mother") and a trailing spoken letter after a name
     * ("mother ji" → "mother g"), because contacts are often saved as "Name X".
     */
    internal fun spoken(q: String): String {
        val joined = q.replace(Regex("\\b(?:\\p{L} ){2,}\\p{L}\\b")) { it.value.replace(" ", "") }
        val words = joined.split(' ').filter(String::isNotEmpty)
        if (words.size < 2) return joined
        val letter = LETTERS[words.last()] ?: return joined
        return (words.dropLast(1) + letter).joinToString(" ")
    }

    private val LETTERS = mapOf(
        "ji" to "g", "jee" to "g", "gee" to "g", "bee" to "b", "be" to "b", "bi" to "b", "tee" to "t", "ti" to "t",
        "ef" to "f", "eff" to "f", "dee" to "d", "di" to "d", "pee" to "p", "pi" to "p", "kay" to "k", "jay" to "j",
        "em" to "m", "en" to "n", "es" to "s", "ess" to "s", "vee" to "v", "ay" to "a", "eh" to "a", "el" to "l",
        "ar" to "r", "are" to "r", "see" to "c", "si" to "c", "zed" to "z", "zee" to "z", "ex" to "x", "why" to "y",
    )

    private fun contactNames(contact: Contacts.Contact) = (listOf(contact.name) + contact.aliases)
        .map(::clean).filter(String::isNotEmpty)

    private val relations = listOf(
        listOf("mother", "mom", "mum", "mummy", "mommy", "mama", "amma", "ammu", "ammum", "ammi", "maa", "ma", "madar", "mader", "modar", "moder", "muther", "mather", "মা", "আম্মু", "আম্মা"),
        listOf("father", "dad", "daddy", "papa", "baba", "abba", "abbu", "fader", "fadar", "farther", "বাবা", "আব্বু", "আব্বা"),
        listOf("sister", "sis", "apu", "apa", "bon", "বোন", "আপু"),
        listOf("brother", "bro", "bhai", "vai", "ভাই"),
        listOf("grandmother", "grandma", "granny", "nani", "nanu", "dadi", "dida", "নানি", "দাদি"),
        listOf("grandfather", "grandpa", "grandad", "granddad", "nana", "dada", "নানা", "দাদা"),
        listOf("aunt", "auntie", "aunty", "khala", "fupu", "fufi", "mashi", "masi", "খালা", "ফুপু"),
        listOf("uncle", "chacha", "kaka", "mamu", "চাচা", "কাকা", "মামা"),
    ).flatMap { group -> group.map { clean(it) to group.first() } }.toMap()

    private fun relationMatch(query: String, name: String): Boolean {
        val q = query.split(' ').map { relations[it] ?: it }
        val n = name.split(' ').map { relations[it] ?: it }
        return n.windowed(q.size).any { it == q }
    }

    private fun phoneticMatch(query: String, name: String): Boolean {
        val q = query.split(' ')
        val n = name.split(' ')
        return n.windowed(q.size).any { words ->
            q.zip(words).all { (a, b) ->
                a == b || (a !in relations && b !in relations && a.length >= 4 && b.length >= 4 &&
                    phonetic(a) == phonetic(b))
            }
        }
    }

    private fun phonetic(word: String) = word.replace("ph", "f").replace("sh", "s")
        .replace("ee", "i").replace("ii", "i").replace("oo", "u")
        .replace(Regex("(.)\\1+"), "$1")

    private fun matches(query: String, name: String): Boolean =
        name == query || name.startsWith("$query ") || name.endsWith(" $query") ||
            name.contains(" $query ") || (query.length >= 3 && query !in relations && name.split(' ').any { it.startsWith(query) })
}
