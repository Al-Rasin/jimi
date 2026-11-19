package com.alrasin.jarvis.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchingTest {
    @Test fun keepsValueAndError() {
        assertEquals(2, catching { 1 + 1 }.getOrNull())
        assertTrue(catching { error("boom") }.isFailure)
    }

    @Test(expected = CancellationException::class)
    fun rethrowsCancel() {
        catching { throw CancellationException("stop") }
    }

    @Test fun timeoutStillReturnsNull() = runBlocking {
        val r = withTimeoutOrNull(50) { catching { awaitCancellation() } }
        assertNull(r)
    }

    @Test fun cancelReachesCaller() = runBlocking {
        val job = async { catching { awaitCancellation() }; "kept going" }
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
    }
}
