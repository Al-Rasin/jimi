package com.alrasin.jarvis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhraseCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun cache(dir: File, maxBytes: Long = 30L * 1024 * 1024) =
        PhraseCache(dir, "alan", 1f, 22050, maxBytes)

    @Test
    fun roundTripPersistsExactSamplesAndSampleRate() {
        val dir = temporary.newFolder()
        val samples = floatArrayOf(-1f, -0.1234567f, -0.0f, 0f, 0.33333334f, 1f)
        cache(dir).put("Done.", samples, 24000)

        val audio = cache(dir).get("Done.")!!
        assertEquals(24000, audio.sampleRate)
        assertArrayEquals(samples, audio.samples, 0f)
        assertEquals(samples.map(Float::toRawBits), audio.samples.map(Float::toRawBits))
    }

    @Test
    fun normalizesWhitespaceButPreservesCaseAndPunctuation() {
        val dir = temporary.newFolder()
        val cache = cache(dir)
        cache.put("  On\tit,\n  Boss.  ", floatArrayOf(0.5f))

        assertNotNull(cache.get("On it, Boss."))
        assertNotNull(cache.get("\nOn   it, Boss.\t"))
        assertNotNull(cache.get("\u00a0On\u2003it, Boss.\u00a0"))
        assertNull(cache.get("on it, boss."))
        assertNull(cache.get("On it Boss"))
        cache.put("On it, Boss.", floatArrayOf(0.25f))
        assertEquals(1, dir.listFiles()!!.size)
        assertArrayEquals(floatArrayOf(0.25f), cache.get("On it, Boss.")!!.samples, 0f)
    }

    @Test
    fun voiceAndSpeedHaveSeparateKeys() {
        val dir = temporary.newFolder()
        val first = cache(dir)
        val otherVoice = PhraseCache(dir, "amy", 1f, 22050)
        val otherSpeed = PhraseCache(dir, "alan", 1.1f, 22050)
        first.put("Done.", floatArrayOf(0.1f))
        assertNull(otherVoice.get("Done."))
        assertNull(otherSpeed.get("Done."))
        otherVoice.put("Done.", floatArrayOf(0.2f))
        otherSpeed.put("Done.", floatArrayOf(0.3f))

        assertArrayEquals(floatArrayOf(0.1f), first.get("Done.")!!.samples, 0f)
        assertArrayEquals(floatArrayOf(0.2f), otherVoice.get("Done.")!!.samples, 0f)
        assertArrayEquals(floatArrayOf(0.3f), otherSpeed.get("Done.")!!.samples, 0f)
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun evictsLeastRecentlyReadEntryWithinByteLimit() {
        val dir = temporary.newFolder()
        val samples = FloatArray(16) { it / 16f }
        cache(dir).put("First", samples)
        val first = dir.listFiles()!!.single()
        val limit = first.length() * 2
        val cache = cache(dir, limit)
        cache.put("Second", samples)
        val second = dir.listFiles()!!.single { it != first }
        assertTrue(first.setLastModified(1000L))
        assertTrue(second.setLastModified(2000L))
        assertNotNull(cache.get("First"))

        cache.put("Third", samples)

        assertNotNull(cache.get("First"))
        assertNull(cache.get("Second"))
        assertNotNull(cache.get("Third"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= limit)
    }

    @Test
    fun oversizedEntryDoesNotDisplaceExistingAudio() {
        val dir = temporary.newFolder()
        cache(dir).put("Small", floatArrayOf(0.1f))
        val limit = dir.listFiles()!!.single().length()
        val cache = cache(dir, limit)

        cache.put("Large", FloatArray(100))

        assertNull(cache.get("Large"))
        assertNotNull(cache.get("Small"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= limit)
    }

    @Test
    fun corruptHeaderIsIgnoredAndDeleted() {
        val dir = temporary.newFolder()
        val cache = cache(dir)
        cache.put("Done.", floatArrayOf(0.1f))
        val file = dir.listFiles()!!.single()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(cache.get("Done."))
        assertFalse(file.exists())
    }

    @Test
    fun truncatedAudioIsIgnoredAndDeleted() {
        val dir = temporary.newFolder()
        val cache = cache(dir)
        cache.put("Done.", FloatArray(16) { 0.5f })
        val file = dir.listFiles()!!.single()
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }

        assertNull(cache.get("Done."))
        assertFalse(file.exists())
    }

    @Test
    fun changedPayloadIsIgnoredAndDeleted() {
        val dir = temporary.newFolder()
        val cache = cache(dir)
        cache.put("Done.", FloatArray(16) { 0.5f })
        val file = dir.listFiles()!!.single()
        RandomAccessFile(file, "rw").use {
            val position = it.length() - 1
            it.seek(position)
            val previous = it.readUnsignedByte()
            it.seek(position)
            it.writeByte(previous xor 1)
        }

        assertNull(cache.get("Done."))
        assertFalse(file.exists())
    }

    @Test
    fun invalidSampleCountsAreRejectedBeforeAllocating() {
        val dir = temporary.newFolder()
        val cache = cache(dir)
        for (count in listOf(-1, Int.MAX_VALUE)) {
            cache.put("Done.", floatArrayOf(0.1f))
            val file = dir.listFiles()!!.single()
            RandomAccessFile(file, "rw").use {
                it.seek(12)
                it.writeInt(count)
            }

            assertNull(cache.get("Done."))
            assertFalse(file.exists())
        }
    }

    @Test
    fun clearRemovesCachedVoicesAndAllowsReuse() {
        val dir = temporary.newFolder()
        val cache = cache(dir)
        val otherVoice = PhraseCache(dir, "amy", 1f, 22050)
        cache.put("Done.", floatArrayOf(0.1f))
        otherVoice.put("Done.", floatArrayOf(0.2f))

        cache.clear()

        assertNull(cache.get("Done."))
        assertNull(otherVoice.get("Done."))
        cache.put("Done.", floatArrayOf(0.3f))
        assertNotNull(cache.get("Done."))
    }

    @Test
    fun warmGeneratesOnlyMissingPhrasesAndRetriesNullResults() {
        val cache = cache(temporary.newFolder())
        assertTrue(PhraseCache.COMMON.size > 1)
        val existing = PhraseCache.COMMON.first()
        val missing = PhraseCache.COMMON.last()
        cache.put(existing, floatArrayOf(0.25f))
        val generated = mutableListOf<String>()

        cache.warm {
            generated += it
            if (it == missing) null else floatArrayOf(0.5f)
        }

        assertEquals(PhraseCache.COMMON.toSet() - existing, generated.toSet())
        assertEquals(generated.size, generated.toSet().size)
        assertArrayEquals(floatArrayOf(0.25f), cache.get(existing)!!.samples, 0f)
        assertNull(cache.get(missing))
        generated.clear()
        cache.warm { generated += it; floatArrayOf(0.75f) }
        assertEquals(listOf(missing), generated)
        assertEquals(22050, cache.get(missing)!!.sampleRate)
        generated.clear()
        cache.warm { generated += it; floatArrayOf(1f) }
        assertTrue(generated.isEmpty())
    }

    @Test
    fun clearDuringWarmStopsAndDoesNotReinsertStaleAudio() {
        val cache = cache(temporary.newFolder())
        val generated = mutableListOf<String>()

        cache.warm {
            generated += it
            cache.clear()
            floatArrayOf(0.5f)
        }

        assertEquals(listOf(PhraseCache.COMMON.first()), generated)
        PhraseCache.COMMON.forEach { assertNull(cache.get(it)) }
    }

    @Test
    fun concurrentWritesAndReadsKeepCompleteAudio() {
        val dir = temporary.newFolder()
        val caches = listOf(cache(dir), cache(dir))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker ->
                Callable {
                    val cache = caches[worker % caches.size]
                    repeat(20) { index ->
                        val phrase = "$worker:$index"
                        val samples = FloatArray(16) { worker / 4f }
                        cache.put(phrase, samples)
                        assertArrayEquals(samples, cache.get(phrase)!!.samples, 0f)
                    }
                }
            }
            executor.invokeAll(tasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
