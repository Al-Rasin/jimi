package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class FreeApisTest {
    @Test fun currencyNames() {
        assertEquals("USD", Currency.code("dollars"))
        assertEquals("BDT", Currency.code("Taka"))
        assertEquals("EUR", Currency.code("eur"))
        assertEquals("XAU", Currency.code("xau"))
        assertNull(Currency.code("banana"))
        assertNull(Currency.code(""))
    }

    @Test fun currencySpeech() {
        assertEquals("100 US dollars is 12,210 taka.", Currency.say(100.0, "USD", "BDT", 122.1))
        assertEquals("1 US dollar is 122.1 taka.", Currency.say(1.0, "USD", "BDT", 122.1))
        assertEquals("50 euros is 7,000 taka.", Currency.say(50.0, "EUR", "BDT", 140.0))
        assertEquals("1 taka is 0.0082 US dollars.", Currency.say(1.0, "BDT", "USD", 0.00819))
    }

    @Test fun parseRates() {
        val r = Currency.parseRates(Json.parse("""{"date":"2026-11-07","usd":{"bdt":122.1,"eur":0.92}}"""), "usd")
        assertEquals(122.1, r.getValue("bdt"), 1e-9)
    }

    @Test fun cacheFreshAndStale() {
        var t = 0L
        val cache = AnswerCache<String>(1_000) { t }
        cache.put("k", "v")
        t = 500
        assertEquals("v", cache.fresh("k"))
        t = 2 * 3_600_000L
        assertNull(cache.fresh("k"))
        assertEquals("v" to 2 * 3_600_000L, cache.stale("k", 3 * 3_600_000L))
        assertNull(cache.stale("k", 1_000))
        assertEquals("from 2 hours ago", AnswerCache.ageSay(2 * 3_600_000L))
        assertEquals("from 5 minutes ago", AnswerCache.ageSay(5 * 60_000L))
    }

    private val dhaka = ZoneId.of("Asia/Dhaka")

    private fun near(expected: String, actual: LocalTime, tolMin: Long = 6) {
        val diff = Duration.between(LocalTime.parse(expected), actual).toMinutes()
        assertTrue("expected ~$expected got $actual", kotlin.math.abs(diff) <= tolMin)
    }

    @Test fun dhakaPrayerTimes() {
        // Dhaka, 7 Nov: sunrise ~6:08 and sunset ~5:16 (real); Fajr/Isha at 18° sit ~78 min outside them.
        val t = PrayerTimes.forDay(LocalDate.of(2026, 11, 7), 23.8103, 90.4125, dhaka)
        near("04:50", t.getValue("fajr"))
        near("06:08", t.getValue("sunrise"), 3)
        near("11:43", t.getValue("dhuhr"))
        near("15:40", t.getValue("asr"))
        near("17:16", t.getValue("maghrib"), 3)
        near("18:33", t.getValue("isha"))
    }

    @Test fun prayerSpeech() {
        val day = LocalDate.of(2026, 11, 7)
        val t = PrayerTimes.forDay(day, 23.8103, 90.4125, dhaka)
        val tomorrow = { PrayerTimes.forDay(day.plusDays(1), 23.8103, 90.4125, dhaka) }
        val noon = LocalDateTime.of(day, LocalTime.of(14, 0))
        assertTrue(PrayerTimes.say(t, null, day, noon, "Dhaka", tomorrow).startsWith("Prayer times today in Dhaka: Fajr 4:"))
        assertTrue(PrayerTimes.say(t, "next", day, noon, "Dhaka", tomorrow).startsWith("Next is Asr at 3:"))
        assertTrue(PrayerTimes.say(t, "maghrib", day, noon, "Dhaka", tomorrow).matches(Regex("Maghrib is at 5:\\d\\d PM today\\.")))
        val late = LocalDateTime.of(day, LocalTime.of(22, 0))
        assertTrue(PrayerTimes.say(t, "next", day, late, "Dhaka", tomorrow).matches(Regex("Next is Fajr at 4:\\d\\d AM, in 6 hours \\d+ minutes\\.")))
    }
}
