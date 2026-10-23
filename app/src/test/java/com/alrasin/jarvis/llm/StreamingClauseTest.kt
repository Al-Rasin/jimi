package com.alrasin.jarvis.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingClauseTest {
    @Test fun sentenceFlushesAsSoonAsFollowingWhitespaceArrives() {
        assertEquals(listOf("That is ready.") to "", ChatFormat.speechChunks("That is ready. "))
    }

    @Test fun longClauseSpeaksBeforeSentenceCompletes() {
        assertEquals(listOf("The answer has two parts,") to "starting with",
            ChatFormat.speechChunks("The answer has two parts, starting with"))
    }

    @Test fun noLostOrRepeatedWordsAcrossTokenBoundaries() {
        val text = "The answer has two parts, starting with the first. Next!\n"
        for (cut in 0..text.length) {
            val first = ChatFormat.speechChunks(text.take(cut))
            val second = ChatFormat.speechChunks(first.second + text.drop(cut))
            assertEquals("cut=$cut", text.trim(), (first.first + second.first).joinToString(" "))
            assertEquals("", second.second)
        }
    }

    @Test fun incompleteThinkBlockNeverSpeaks() {
        val held = ChatFormat.speechChunks("<think>Private thought. More")
        assertEquals(emptyList<String>(), held.first)
        assertEquals(listOf("Hello there.") to "",
            ChatFormat.speechChunks(held.second + "</think>Hello there. "))
    }
}
