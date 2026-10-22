package com.alrasin.jarvis.actions

import java.util.Locale

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
        val q = clean(query)
        if (q.isEmpty()) return Resolution.NotFound
        val available = contacts.filter { number(it.number) != null && clean(it.name).isNotEmpty() }
            .distinctBy { clean(it.name) to number(it.number) }
            .sortedWith(compareBy({ clean(it.name) }, { number(it.number) }))
        val names = available.filter { matches(q, clean(it.name)) }
        val matches = names.ifEmpty {
            available.filter { Fuzzy.score(query, it.name) >= 0.65 }
        }
        return when (matches.size) {
            0 -> Resolution.NotFound
            1 -> Resolution.Found(matches.single())
            else -> Resolution.Ambiguous(query.trim(), matches)
        }
    }

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
        return candidates.filter { matches(text, clean(it.name)) }.singleOrNull()
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

    private fun clean(text: String) = text.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun matches(query: String, name: String): Boolean =
        name == query || name.startsWith("$query ") || name.endsWith(" $query") ||
            name.contains(" $query ") || name.split(' ').any { it.startsWith(query) }
}
