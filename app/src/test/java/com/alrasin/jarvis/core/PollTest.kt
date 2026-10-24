package com.alrasin.jarvis.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollTest {
    @Test fun returnsTrueAssoonAsCheckMatches() = runBlocking {
        var calls = 0
        val ok = pollUntil(5, 0) { calls++; calls == 2 }
        assertTrue(ok)
        assertEquals(2, calls)
    }

    @Test fun returnsFalseAfterRunningOutOfTries() = runBlocking {
        var calls = 0
        val ok = pollUntil(3, 0) { calls++; false }
        assertFalse(ok)
        assertEquals(3, calls)
    }

    @Test fun checksBeforeDelayingByDefault() = runBlocking {
        // 1 try is enough when the check passes on the very first (undelayed) look.
        assertTrue(pollUntil(1, 0) { true })
    }

    @Test fun delayFirstStillChecksUpToTriesTimes() = runBlocking {
        var calls = 0
        val ok = pollUntil(4, 0, delayFirst = true) { calls++; calls == 4 }
        assertTrue(ok)
        assertEquals(4, calls)
    }
}
