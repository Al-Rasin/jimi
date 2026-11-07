package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fast web info without a browser (§J.8): currency rates (free, no key) and prayer times (worked out on the phone,
 * no internet needed). [AnswerCache] keeps the last good answer so offline we can say "from 2 hours ago".
 */
fun freeApiTools(
    web: Web,
    here: suspend () -> CurrentLocation.Fix?,
    rates: AnswerCache<Map<String, Double>> = AnswerCache(TimeUnit.HOURS.toMillis(6)),
    homeCity: suspend () -> String,
): List<Tool> {
    val cityPlaces = ConcurrentHashMap<String, Pair<Double, Double>>()

    suspend fun ratesFor(base: String): Pair<Map<String, Double>, Long?> {
        rates.fresh(base)?.let { return it to null }
        val b = base.lowercase(Locale.ROOT)
        val urls = listOf(
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/$b.json",
            "https://latest.currency-api.pages.dev/v1/currencies/$b.json",
        )
        var error: Throwable? = null
        for (url in urls) {
            val got = runCatching { Currency.parseRates(Json.parse(web.get(url, Web.WIKI_UA, fast = true)), b) }
            got.getOrNull()?.let { rates.put(base, it); return it to null }
            error = got.exceptionOrNull()
        }
        rates.stale(base, TimeUnit.DAYS.toMillis(3))?.let { (map, age) -> return map to age }
        throw error ?: IllegalStateException("no rates")
    }

    suspend fun place(): Pair<Pair<Double, Double>, String>? {
        here()?.let { return (it.lat to it.lon) to (it.name ?: "your area") }
        val city = homeCity().trim().ifBlank { return null }
        val key = city.lowercase(Locale.ROOT)
        cityPlaces[key]?.let { return it to city }
        val hit = runCatching {
            val res = Json.parse(web.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${Web.enc(city)}", fast = true)) as Map<*, *>
            val first = (res["results"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
            (first["latitude"] as Number).toDouble() to (first["longitude"] as Number).toDouble()
        }.getOrNull() ?: return null
        cityPlaces[key] = hit
        return hit to city
    }

    return listOf(
        SimpleTool(
            "currency",
            "Money exchange rate / convert an amount between currencies (default to Bangladeshi taka)",
            listOf(
                arg("from", "currency code or name, like USD or dollar"),
                arg("to", "currency code or name; default BDT", required = false),
                arg("amount", "how much; default 1", required = false),
            ),
        ) { c, _ ->
            val from = Currency.code(c.arg("from")) ?: return@SimpleTool ToolResult.fail("Which currency?")
            val to = Currency.code(c.arg("to")) ?: "BDT"
            if (from == to) return@SimpleTool ToolResult.fail("Those are the same currency.")
            val amount = c.arg("amount")?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            runCatching { ratesFor(from) }.fold({ (map, age) ->
                val rate = map[to.lowercase(Locale.ROOT)] ?: return@SimpleTool ToolResult.fail("I don't have a rate for ${Currency.name(to, 2.0)}.")
                val say = Currency.say(amount, from, to, rate)
                JarvisLog.i("currency: $from→$to rate=$rate age=$age")
                ToolResult.ok(if (age == null) say else "I'm offline. ${AnswerCache.ageSay(age).replaceFirstChar { it.uppercase() }}: $say")
            }, { webFailure("the exchange rate service", it) })
        },
        SimpleTool(
            "prayer_times",
            "Muslim prayer times (Fajr, Dhuhr, Asr, Maghrib, Isha), sunrise and sunset, worked out offline for where the user is",
            listOf(
                arg("prayer", "one prayer, or next = the next one", required = false,
                    choices = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha", "sunset", "next")),
                arg("day", "today, tomorrow or yyyy-MM-dd", required = false),
            ),
        ) { c, _ ->
            val (pos, name) = place() ?: return@SimpleTool ToolResult.fail("I need your location or a home city in Settings for prayer times.")
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.now(zone)
            val day = when (val d = c.arg("day")) {
                null, "today" -> now.toLocalDate()
                "tomorrow" -> now.toLocalDate().plusDays(1)
                else -> runCatching { LocalDate.parse(d) }.getOrElse { now.toLocalDate() }
            }
            val times = PrayerTimes.forDay(day, pos.first, pos.second, zone)
            ToolResult.ok(PrayerTimes.say(times, c.arg("prayer"), day, now, name) {
                PrayerTimes.forDay(day.plusDays(1), pos.first, pos.second, zone)
            })
        },
    )
}

/** Last good answers with a time-to-live. Fresh = younger than [ttlMs]; stale = any age up to a limit (offline use). */
class AnswerCache<T>(private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val items = ConcurrentHashMap<String, Pair<Long, T>>()

    fun put(key: String, value: T) { items[key] = now() to value }

    fun fresh(key: String): T? = items[key]?.takeIf { now() - it.first <= ttlMs }?.second

    /** Value + its age in ms, or null when missing or older than [maxAgeMs]. */
    fun stale(key: String, maxAgeMs: Long): Pair<T, Long>? =
        items[key]?.let { (at, v) -> (now() - at).takeIf { it <= maxAgeMs }?.let { v to it } }

    companion object {
        fun ageSay(ms: Long): String {
            val min = ms / 60_000
            return when {
                min < 2 -> "from just now"
                min < 60 -> "from $min minutes ago"
                min < 120 -> "from an hour ago"
                min < 24 * 60 -> "from ${min / 60} hours ago"
                min < 48 * 60 -> "from yesterday"
                else -> "from ${min / (24 * 60)} days ago"
            }
        }
    }
}

internal object Currency {
    private val NAMES = linkedMapOf(
        "BDT" to listOf("taka", "takas", "tk", "bdt", "bangladeshi taka", "bangladesh taka"),
        "USD" to listOf("dollar", "dollars", "usd", "us dollar", "us dollars", "american dollar", "american dollars", "buck", "bucks"),
        "EUR" to listOf("euro", "euros", "eur"),
        "GBP" to listOf("pound", "pounds", "gbp", "british pound", "british pounds", "sterling", "pound sterling"),
        "INR" to listOf("rupee", "rupees", "inr", "indian rupee", "indian rupees"),
        "PKR" to listOf("pakistani rupee", "pakistani rupees", "pkr"),
        "SAR" to listOf("riyal", "riyals", "saudi riyal", "saudi riyals", "sar"),
        "AED" to listOf("dirham", "dirhams", "uae dirham", "aed"),
        "QAR" to listOf("qatari riyal", "qatari riyals", "qar"),
        "KWD" to listOf("kuwaiti dinar", "kuwaiti dinars", "dinar", "dinars", "kwd"),
        "OMR" to listOf("omani rial", "omani rials", "omr"),
        "MYR" to listOf("ringgit", "ringgits", "malaysian ringgit", "myr"),
        "SGD" to listOf("singapore dollar", "singapore dollars", "sgd"),
        "CAD" to listOf("canadian dollar", "canadian dollars", "cad"),
        "AUD" to listOf("australian dollar", "australian dollars", "aud"),
        "JPY" to listOf("yen", "japanese yen", "jpy"),
        "CNY" to listOf("yuan", "chinese yuan", "rmb", "renminbi", "cny"),
        "KRW" to listOf("won", "korean won", "krw"),
        "THB" to listOf("baht", "thai baht", "thb"),
        "TRY" to listOf("lira", "turkish lira", "try"),
        "RUB" to listOf("ruble", "rubles", "rouble", "russian ruble", "rub"),
        "CHF" to listOf("swiss franc", "swiss francs", "franc", "francs", "chf"),
        "BTC" to listOf("bitcoin", "bitcoins", "btc"),
    )
    private val BY_NAME: Map<String, String> = NAMES.flatMap { (code, names) -> names.map { it to code } }.toMap()
    private val SPOKEN = mapOf(
        "BDT" to "taka", "USD" to "US dollar", "EUR" to "euro", "GBP" to "British pound", "INR" to "Indian rupee",
        "PKR" to "Pakistani rupee", "SAR" to "Saudi riyal", "AED" to "UAE dirham", "QAR" to "Qatari riyal",
        "KWD" to "Kuwaiti dinar", "OMR" to "Omani rial", "MYR" to "Malaysian ringgit", "SGD" to "Singapore dollar",
        "CAD" to "Canadian dollar", "AUD" to "Australian dollar", "JPY" to "yen", "CNY" to "Chinese yuan",
        "KRW" to "Korean won", "THB" to "Thai baht", "TRY" to "Turkish lira", "RUB" to "Russian ruble",
        "CHF" to "Swiss franc", "BTC" to "bitcoin",
    )
    /** Regex alternative of every spoken name, longest first (so "canadian dollar" wins over "dollar"). */
    val NAME_PATTERN: String = BY_NAME.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    fun code(raw: String?): String? {
        val s = raw?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return null
        BY_NAME[s]?.let { return it }
        return s.uppercase(Locale.ROOT).takeIf { it.length == 3 && it.all(Char::isLetter) }
    }

    fun name(code: String, amount: Double): String {
        val base = SPOKEN[code] ?: code
        val plural = amount != 1.0 && code !in setOf("BDT", "JPY", "KRW", "THB", "CNY")
        return if (plural) "${base}s" else base
    }

    fun parseRates(json: Any?, base: String): Map<String, Double> {
        val m = (json as? Map<*, *>)?.get(base) as? Map<*, *> ?: error("no $base rates")
        return m.entries.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Number)?.toDouble()?.let { key to it } } }.toMap()
            .also { require(it.isNotEmpty()) }
    }

    fun number(x: Double): String = when {
        x >= 1000 -> String.format(Locale.US, "%,d", x.roundToLong())
        x >= 1 -> String.format(Locale.US, "%,.2f", x).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.4f", x).trimEnd('0').trimEnd('.')
    }

    fun say(amount: Double, from: String, to: String, rate: Double): String {
        val out = amount * rate
        val a = if (amount == floor(amount)) String.format(Locale.US, "%,d", amount.toLong()) else number(amount)
        return "$a ${name(from, amount)} is ${number(out)} ${name(to, out)}."
    }
}

/**
 * Prayer times from the sun's position (praytimes.org method), so they work with no internet. Settings fit Bangladesh:
 * Karachi angles (Fajr and Isha 18°) and Hanafi Asr (shadow = 2× length). Accurate to about a minute.
 */
internal object PrayerTimes {
    val ORDER = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
    private val TITLE = mapOf("fajr" to "Fajr", "sunrise" to "Sunrise", "dhuhr" to "Dhuhr", "asr" to "Asr", "maghrib" to "Maghrib", "isha" to "Isha")
    private const val FAJR_ANGLE = 18.0
    private const val ISHA_ANGLE = 18.0
    private const val ASR_SHADOW = 2.0

    fun forDay(day: LocalDate, lat: Double, lon: Double, zone: ZoneId): Map<String, LocalTime> {
        val jd = julian(day) - lon / (15 * 24.0)
        val (decl, eqt) = sun(jd + 0.5)
        val noon = 12 - eqt - lon / 15 // UTC hours
        fun hourAngle(altitude: Double): Double {
            val v = (sinD(altitude) - sinD(decl) * sinD(lat)) / (cosD(decl) * cosD(lat))
            return Math.toDegrees(acos(v.coerceIn(-1.0, 1.0))) / 15
        }
        val asrAlt = Math.toDegrees(atan(1 / (ASR_SHADOW + tan(Math.toRadians(abs(lat - decl))))))
        val utc = mapOf(
            "fajr" to noon - hourAngle(-FAJR_ANGLE),
            "sunrise" to noon - hourAngle(-0.833),
            "dhuhr" to noon + 1 / 60.0,
            "asr" to noon + hourAngle(asrAlt),
            "maghrib" to noon + hourAngle(-0.833),
            "isha" to noon + hourAngle(-ISHA_ANGLE),
        )
        val offset = zone.rules.getOffset(day.atTime(12, 0)).totalSeconds / 3600.0
        return utc.mapValues { (_, h) -> clock(h + offset) }
    }

    /** Spoken answer: all five (+ sunrise), one prayer, or the next one from [now]. */
    fun say(
        times: Map<String, LocalTime>,
        prayer: String?,
        day: LocalDate,
        now: LocalDateTime,
        place: String,
        tomorrow: () -> Map<String, LocalTime>,
    ): String {
        val today = now.toLocalDate()
        val dayWord = when (day) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> "on ${day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.US))}" }
        when (val p = prayer?.lowercase(Locale.ROOT)?.let { if (it == "sunset") "maghrib" else it }) {
            null, "" -> {
                val list = listOf("fajr", "dhuhr", "asr", "maghrib", "isha").joinToString(", ") { "${TITLE[it]} ${hm(times.getValue(it))}" }
                return "Prayer times ${dayWord} in $place: $list."
            }
            "next" -> {
                val (name, time, next) = ORDER.filter { it != "sunrise" }.firstOrNull { day == today && times.getValue(it) > now.toLocalTime() }
                    ?.let { Triple(it, times.getValue(it), false) }
                    ?: Triple("fajr", tomorrow().getValue("fajr"), true)
                val mins = java.time.Duration.between(now, (if (next) today.plusDays(1) else today).atTime(time)).toMinutes()
                return "Next is ${TITLE[name]} at ${hm(time)}, in ${inSay(mins)}."
            }
            else -> {
                val time = times[p] ?: return "Prayer times ${dayWord}: " + ORDER.joinToString(", ") { "${TITLE[it]} ${hm(times.getValue(it))}" } + "."
                val label = if (prayer.equals("sunset", true)) "Sunset" else TITLE[p]
                return "$label is at ${hm(time)} $dayWord."
            }
        }
    }

    fun hm(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

    private fun inSay(mins: Long): String {
        val h = mins / 60
        val m = mins % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { if (it == 1L) "1 hour" else "$it hours" },
            m.takeIf { it > 0 || h == 0L }?.let { if (it == 1L) "1 minute" else "$it minutes" },
        ).joinToString(" ")
    }

    private fun clock(hours: Double): LocalTime {
        val mins = ((hours * 60).roundToInt() % 1440 + 1440) % 1440
        return LocalTime.of(mins / 60, mins % 60)
    }

    private fun julian(d: LocalDate): Double {
        var y = d.year
        var m = d.monthValue
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d.dayOfMonth + b - 1524.5
    }

    /** Sun declination (degrees) and equation of time (hours) for a Julian date. */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * sinD(g) + 0.020 * sinD(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = fix(Math.toDegrees(atan2(cosD(e) * sinD(l), cosD(l))) / 15, 24.0)
        val eqt = q / 15 - ra
        val decl = Math.toDegrees(asin(sinD(e) * sinD(l)))
        return decl to (eqt - 24 * Math.round(eqt / 24.0))
    }

    private fun fix(a: Double, b: Double) = ((a % b) + b) % b
    private fun sinD(x: Double) = sin(Math.toRadians(x))
    private fun cosD(x: Double) = cos(Math.toRadians(x))
}
