package com.alrasin.jarvis.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttVocabularyRebuildTest {
    @Test fun rebuiltVocabularyDropsRemovedContactsAndApps() {
        val before = SttVocabulary(listOf("Ada Lovelace"), listOf("Music Player"))
        val after = SttVocabulary(listOf("Grace Hopper"), listOf("Video Player"))
        assertTrue(before.hotwords.contains("Ada Lovelace"))
        assertFalse(after.hotwords.contains("Ada Lovelace"))
        assertFalse(after.hotwords.contains("Music Player"))
        assertTrue(after.hotwords.contains("Grace Hopper"))
        assertTrue(after.hotwords.contains("Video Player"))
        assertTrue(after.hotwords.contains(SttVocabulary.WAKE_PHRASE))
        assertTrue(after.hotwords.containsAll(SttVocabulary.TOOL_WORDS))
    }

    @Test fun revokedContactPermissionCanClearPersonalNames() {
        val rebuilt = SttVocabulary(emptyList(), listOf("Music Player"))
        assertTrue(rebuilt.contacts.isEmpty())
        assertTrue(rebuilt.hotwords.contains("Music Player"))
    }
}
