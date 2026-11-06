package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFallbackTest {
    @Test fun weatherCacheUsesFreshReportOnly() {
        var t = 1_000_000L
        val cache = WeatherCache { t }
        assertFalse(cache.fallback("dhaka|false", "err").ok)
        cache.put("dhaka|false", "It's 30 degrees.")
        t += 60_000
        val fresh = cache.fallback("dhaka|false", "err")
        assertTrue(fresh.ok)
        assertTrue(fresh.say.endsWith("It's 30 degrees."))
        t += 7 * 3_600_000L
        assertFalse(cache.fallback("dhaka|false", "err").ok)
    }

    @Test fun wikiExtractReadsQueryPages() {
        val json = mapOf("query" to mapOf("pages" to mapOf("123" to mapOf("title" to "Dhaka", "extract" to "Dhaka is the capital."))))
        assertEquals("Dhaka is the capital.", wikiExtract(json))
        assertEquals("", wikiExtract(null))
        assertEquals("", wikiExtract(mapOf("query" to emptyMap<String, Any>())))
    }
}
