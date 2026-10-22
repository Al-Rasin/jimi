package com.alrasin.jarvis.voice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedSpeechDecoderTest {
    private val audio = shortArrayOf(1)

    @Test fun disabledAndEmptyRequestsDoNotLoadOrDecode() = runBlocking {
        val calls = AtomicInteger()
        BoundedSpeechDecoder({ calls.incrementAndGet(); "new" }, {}, prepare = { calls.incrementAndGet() }).use {
            assertEquals("old", it.refine(audio, "old", false))
            assertEquals("", it.refine(audio, "", true))
            assertEquals("old", it.refine(shortArrayOf(), "old", true))
            assertEquals(0, calls.get())
        }
    }

    @Test fun coldLoadReturnsImmediatelyAndBusyRequestsAreSkipped() = runBlocking {
        val loading = CountDownLatch(1)
        val finishLoad = CountDownLatch(1)
        val released = CountDownLatch(1)
        val loads = AtomicInteger()
        val decoder = BoundedSpeechDecoder({ "new" }, { released.countDown() }, prepare = {
            loads.incrementAndGet()
            loading.countDown()
            finishLoad.await(2, TimeUnit.SECONDS)
        })
        try {
            assertEquals("old", decoder.refine(audio, "old", true))
            assertTrue(loading.await(1, TimeUnit.SECONDS))
            assertEquals("old", decoder.refine(audio, "old", true))
            assertEquals(1, loads.get())
            decoder.close()
            assertEquals(1L, released.count)
        } finally {
            finishLoad.countDown()
            decoder.close()
        }
        assertTrue(released.await(1, TimeUnit.SECONDS))
    }

    @Test fun timeoutDiscardsLateResultAndReleasesOnlyAfterNativeReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val released = CountDownLatch(1)
        val decoder = BoundedSpeechDecoder({
            entered.countDown()
            finish.await(2, TimeUnit.SECONDS)
            "late"
        }, { released.countDown() }, deadlineMillis = 30)
        try {
            assertEquals("old", decoder.refine(audio, "old", true))
            assertEquals(0L, entered.count)
            assertEquals(1L, released.count)
            assertEquals("old", decoder.refine(audio, "old", true))
        } finally {
            finish.countDown()
            decoder.close()
        }
        assertTrue(released.await(1, TimeUnit.SECONDS))
    }

    @Test fun cancellationDoesNotReleaseActiveNativeCall() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val released = CountDownLatch(1)
        val decoder = BoundedSpeechDecoder({
            entered.countDown()
            finish.await(2, TimeUnit.SECONDS)
            "new"
        }, { released.countDown() })
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { decoder.refine(audio, "old", true) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals("old", decoder.refine(audio, "old", true))
            pending.cancelAndJoin()
            assertEquals(1L, released.count)
        } finally {
            finish.countDown()
            decoder.close()
        }
        assertTrue(released.await(1, TimeUnit.SECONDS))
    }

    @Test fun successfulBlankAndFailedResultsKeepTheirContracts() = runBlocking {
        BoundedSpeechDecoder({ "new" }, {}).use {
            assertEquals("new", it.refine(audio, "old", true))
        }
        BoundedSpeechDecoder({ " " }, {}).use {
            assertEquals("old", it.refine(audio, "old", true))
        }
        val calls = AtomicInteger()
        BoundedSpeechDecoder({ calls.incrementAndGet(); error("model failed") }, {}).use {
            assertEquals("old", it.refine(audio, "old", true))
            assertEquals("old", it.refine(audio, "old", true))
            assertEquals(1, calls.get())
        }
    }
}
