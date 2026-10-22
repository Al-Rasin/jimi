package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SttHotwordsTest {
    private val pieces = listOf("<unk>", "▁") + ('a'..'z').map(Char::toString) + listOf("'", "▁call", "ing")
    private val tokens = pieces.mapIndexed { i, p -> "$p $i" }.joinToString("\n")
    private val vocab = pieces.mapIndexed { i, p -> "$p -${i + 1}.0" }.joinToString("\n")

    private fun prepare(vararg words: String) = SttHotwords.prepare(tokens, vocab, words.toList())

    @Test fun acceptsMatchingVocabulary() {
        assertEquals("call alice/hey jimi", prepare("Call Alice", "Hey Jimi").phrases)
    }

    @Test fun noVocabularyKeepsGreedySearch() {
        assertFalse(SttHotwords.prepare(tokens, vocab, emptyList()).enabled)
    }

    @Test fun missingTokenizerExplicitlyDisablesBias() {
        val result = SttHotwords.prepare(tokens, null, listOf("alice"))
        assertFalse(result.enabled)
        assertTrue(result.reason.contains("missing"))
    }

    @Test fun emptyTokenizerDisablesBias() {
        assertFalse(SttHotwords.prepare(tokens, "", listOf("alice")).enabled)
    }

    @Test fun rejectsMismatchedTokenizer() {
        assertFalse(SttHotwords.prepare(tokens, "$vocab\nwrong -3", listOf("alice")).enabled)
    }

    @Test fun rejectsMissingPieces() {
        assertFalse(SttHotwords.prepare(tokens, vocab.substringBeforeLast('\n'), listOf("alice")).enabled)
    }

    @Test fun rejectsCarriageReturnOnlyLinesUnsupportedByNativeTokenizer() {
        assertFalse(SttHotwords.prepare(tokens, vocab.replace('\n', '\r'), listOf("alice")).enabled)
    }

    @Test fun rejectsInvalidScores() {
        for (score in listOf("NaN", "Infinity", "words")) {
            assertFalse(SttHotwords.prepare(tokens, vocab.replace("-1.0", score), listOf("alice")).enabled)
        }
    }

    @Test fun rejectsDuplicatePieces() {
        assertFalse(SttHotwords.prepare(tokens, "$vocab\na -2", listOf("alice")).enabled)
    }

    @Test fun rejectsBlankLinesThatWouldTerminateNativeTokenizer() {
        assertFalse(SttHotwords.prepare(tokens, "$vocab\n\n", listOf("alice")).enabled)
        assertFalse(SttHotwords.prepare(tokens, "\n$vocab", listOf("alice")).enabled)
    }

    @Test fun acceptsNormalFinalNewline() {
        assertTrue(SttHotwords.prepare(tokens, "$vocab\n", listOf("alice")).enabled)
        assertTrue(SttHotwords.prepare(tokens, vocab.replace("\n", "\r\n") + "\r\n", listOf("alice")).enabled)
    }

    @Test fun rejectsInvalidTokenIds() {
        assertFalse(SttHotwords.prepare("a wrong", vocab, listOf("alice")).enabled)
    }

    @Test fun rejectsNativeSyntaxInjection() {
        assertEquals("alice", prepare("bob:99", "bob/alice", "bob\nalice", "bob\ralice", "@bob", "#bob", "alice").phrases)
    }

    @Test fun filtersUnknownCharactersWithoutDamagingOtherNames() {
        assertEquals("alice", prepare("张三", "alíce", "alice").phrases)
    }

    @Test fun retainsApostrophesWhenSupported() {
        assertEquals("o'brien", prepare("O'Brien").phrases)
    }

    @Test fun trimsCollapsesWhitespaceAndDeduplicates() {
        assertEquals("call alice", prepare(" Call  Alice ", "call\talice", "CALL ALICE", " ").phrases)
    }

    @Test fun capsPhraseLength() {
        assertFalse(prepare("a".repeat(81)).enabled)
    }

    @Test fun capsVocabularySize() {
        val words = (1..300).map { "a".repeat(it / 26 + 1) + ('a' + it % 26) }
        assertEquals(SttHotwords.MAX_PHRASES, SttHotwords.prepare(tokens, vocab, words).phrases.split('/').size)
    }

    @Test fun handlesUppercaseModels() {
        assertEquals("CALL ALICE", SttHotwords.prepare(tokens.uppercase(Locale.ROOT), vocab.uppercase(Locale.ROOT), listOf("Call Alice")).phrases)
    }

    @Test fun caseConversionDoesNotDependOnPhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("jimi", prepare("JIMI").phrases)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun requiresWordBoundaryToken() {
        assertFalse(SttHotwords.prepare("a 0", "a -1", listOf("aaa")).enabled)
    }

    @Test fun rejectsOversizedMetadata() {
        assertFalse(SttHotwords.prepare(tokens, " ".repeat(4 * 1024 * 1024 + 1), listOf("alice")).enabled)
    }
}
