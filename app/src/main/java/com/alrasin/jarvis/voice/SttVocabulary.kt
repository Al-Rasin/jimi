package com.alrasin.jarvis.voice

import com.alrasin.jarvis.core.TextNorm
import java.text.Normalizer
import java.util.Locale

class SttVocabulary(contacts: List<String> = emptyList(), apps: List<String> = emptyList()) {
    val contacts: List<String> = clean(contacts, MAX_CONTACTS)
    val apps: List<String> = clean(apps, MAX_APPS)
    val hotwords: List<String> = buildList {
        addAll(TOOL_WORDS)
        add(WAKE_PHRASE)
        for (index in 0 until maxOf(this@SttVocabulary.contacts.size, this@SttVocabulary.apps.size)) {
            this@SttVocabulary.contacts.getOrNull(index)?.let(::add)
            this@SttVocabulary.apps.getOrNull(index)?.let(::add)
        }
    }.distinctBy { it.lowercase(Locale.ROOT) }

    companion object {
        const val WAKE_PHRASE = "Hey Jimi"
        const val MAX_CONTACTS = 512
        const val MAX_APPS = 256
        const val MAX_PHRASE_LENGTH = 80
        private const val MAX_PHRASE_WORDS = 8
        private val punctuation = Regex("[^\\p{L}\\p{M}\\p{N}' -]+")

        val TOOL_WORDS = listOf(
            "flashlight", "torch", "wifi", "bluetooth", "mobile data", "hotspot",
            "airplane mode", "do not disturb", "battery saver", "location", "brightness",
            "volume", "mute", "unmute", "screenshot", "notifications", "messages",
            "alarm", "timer", "reminder", "calendar", "weather", "battery", "settings",
            "call", "dial", "message", "text", "WhatsApp", "email", "open", "launch",
            "close", "play", "pause", "resume", "stop", "next", "previous", "cancel",
            "repeat", "lock", "unlock", "scroll", "screen", "notes", "remember", "forget",
        )

        private fun clean(values: List<String>, limit: Int): List<String> = values.asSequence()
            .mapNotNull(::sanitize)
            .sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(limit)
            .toList()

        internal fun sanitize(value: String): String? {
            if (value.length > MAX_PHRASE_LENGTH * 2) return null
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u2019', '\'')
                .replace(punctuation, " ")
                .replace(TextNorm.SPACES, " ")
                .trim(' ', '-', '\'')
            // Contacts may use a phone number as their display name; never bias toward one.
            if (normalized.none { it.isLetter() } || normalized.count { it.isDigit() } >= 7) return null
            if (normalized.length > MAX_PHRASE_LENGTH || normalized.count { it == ' ' } >= MAX_PHRASE_WORDS) return null
            return normalized
        }
    }
}
