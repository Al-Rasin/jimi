package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SttVocabularyTest {
    @Test fun includesToolWordsAndWakePhraseWithoutPermissions() {
        val vocabulary = SttVocabulary()
        assertTrue(vocabulary.hotwords.contains("flashlight"))
        assertTrue(vocabulary.hotwords.contains("Hey Jimi"))
        assertTrue(vocabulary.contacts.isEmpty())
        assertTrue(vocabulary.apps.isEmpty())
    }

    @Test fun includesContactsAndApps() {
        val vocabulary = SttVocabulary(listOf("Nadia Rahman"), listOf("YouTube"))
        assertTrue(vocabulary.hotwords.contains("Nadia Rahman"))
        assertTrue(vocabulary.hotwords.contains("YouTube"))
    }

    @Test fun deduplicatesIgnoringCaseAndSortsDeterministically() {
        val names = listOf("Zara", "alice", "Alice", "ZARA")
        assertEquals(listOf("Alice", "ZARA"), SttVocabulary(names).contacts)
        assertEquals(SttVocabulary(names).contacts, SttVocabulary(names.reversed()).contacts)
    }

    @Test fun deduplicatesHotwordsAcrossSources() {
        val words = SttVocabulary(listOf("WhatsApp"), listOf("whatsapp")).hotwords
        assertEquals(1, words.count { it.equals("whatsapp", ignoreCase = true) })
    }

    @Test fun collapsesWhitespaceAndPreventsHotwordSyntaxInjection() {
        val vocabulary = SttVocabulary(listOf("  Nadia\n Rahman\t", "Alice :9 #Bob"))
        assertEquals(listOf("Alice 9 Bob", "Nadia Rahman"), vocabulary.contacts)
        assertTrue(vocabulary.hotwords.none { '\n' in it || ':' in it || '#' in it })
    }

    @Test fun preservesApostrophesHyphensAndAccentedLetters() {
        assertEquals(listOf("Anne-Marie O'Neil", "José"),
            SttVocabulary(listOf("Anne-Marie O’Neil", "José")).contacts)
    }

    @Test fun preservesNonLatinNamesForPostCorrection() {
        assertEquals(listOf("রহিম"), SttVocabulary(listOf("রহিম")).contacts)
    }

    @Test fun removesEmptySymbolsAndPhoneNumberLabels() {
        assertTrue(SttVocabulary(listOf("", " ", "♥", "123", "+880 1234567890", "Office 1234567")).contacts.isEmpty())
    }

    @Test fun removesOverlongPhrasesInsteadOfTruncatingNames() {
        assertTrue(SttVocabulary(listOf("A".repeat(81), "A B C D E F G H I")).contacts.isEmpty())
    }

    @Test fun permitsEightWords() {
        assertEquals(listOf("A B C D E F G H"), SttVocabulary(listOf("A B C D E F G H")).contacts)
    }

    @Test fun capsContactAndAppVocabulary() {
        val names = (0..900).map { "Name $it" }
        val vocabulary = SttVocabulary(names, names)
        assertEquals(SttVocabulary.MAX_CONTACTS, vocabulary.contacts.size)
        assertEquals(SttVocabulary.MAX_APPS, vocabulary.apps.size)
    }

    @Test fun cappedHotwordsKeepToolsWakeAndBothPersonalSources() {
        val vocabulary = SttVocabulary(
            contacts = (0..900).map { "Alice $it" },
            apps = (0..900).map { "Zebra $it" },
        )
        val limited = vocabulary.hotwords.take(256)
        assertTrue(limited.containsAll(SttVocabulary.TOOL_WORDS))
        assertTrue(limited.contains(SttVocabulary.WAKE_PHRASE))
        assertTrue(limited.any { it.startsWith("Alice ") })
        assertTrue(limited.any { it.startsWith("Zebra ") })
    }

    @Test fun preservesModelNumbersInAppNames() {
        assertEquals(listOf("Authenticator 2"), SttVocabulary(apps = listOf("Authenticator 2")).apps)
    }

    @Test fun normalizesCompatibilityCharacters() {
        assertEquals(listOf("Alice"), SttVocabulary(listOf("Ａlice")).contacts)
    }

    @Test fun copiesCallerLists() {
        val names = mutableListOf("Alice")
        val vocabulary = SttVocabulary(names)
        names += "Bob"
        assertFalse(vocabulary.contacts.contains("Bob"))
    }

    @Test fun caseFoldingDoesNotDependOnPhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("IRIS"), SttVocabulary(listOf("iris", "IRIS")).contacts)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
