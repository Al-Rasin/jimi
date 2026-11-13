package com.alrasin.jarvis.knowledge

import com.alrasin.jarvis.actions.budget
import com.alrasin.jarvis.brain.PageRanker
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkMergeTest {
    @Test fun noModelKeepsKeywordOrder() {
        assertEquals(listOf(2, 0), ChunkRanker.merge(listOf(2, 0, 1), null, 2))
    }

    @Test fun meaningBeatsKeywordOrder() {
        val sims = floatArrayOf(0.30f, 0.80f, 0.10f)
        assertEquals(listOf(1, 0, 2), ChunkRanker.merge(listOf(0, 1, 2), sims, 3))
    }

    @Test fun meaningOnlyItemNeedsMinScore() {
        // 0 is a keyword hit (always kept); 1 means the question; 2 is noise with no keyword hit
        val sims = floatArrayOf(0.05f, 0.70f, 0.10f)
        assertEquals(listOf(1, 0), ChunkRanker.merge(listOf(0), sims, 3))
    }

    @Test fun budgetKeepsWholeChunksAndCutsOnlyFirst() {
        fun c(n: Int) = PageRanker.Chunk("x".repeat(n), "u", 1.0)
        assertEquals(listOf(5, 3), budget(listOf(c(5), c(9), c(3)), 10).map { it.text.length })
        assertEquals(listOf(10), budget(listOf(c(50), c(3)), 10).map { it.text.length })
    }
}
