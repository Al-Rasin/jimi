package com.alrasin.jimi.brain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Offline facts that need no tool and no model: "what time is it in london", "5 km in miles",
 * "how many grams in a kilo", "how many days until december 25", "what day was 1 january 2000".
 */
object QuickFacts {
    fun answer(input: String, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val t = prepare(input)
        if (t.isEmpty()) return null
        return worldTime(t, now) ?: units(t) ?: dates(t, now.toLocalDate()) ?: spell(t) ?: capital(t) ?: moon(t, now) ?: fact(t)
    }

    // ---- a few fixed facts the small model often gets wrong ----

    private val ASK = "(?:(?:do you know |tell me )?(?:whats|what is|what are|how far is|how far away is|how big is|how hot is|how many|how much|how fast is|how fast does|how long is|how tall is|how deep is|how old is|which is|who is|who was|when is|when was) )?(?:the )?"
    private val FACTS = listOf(
        "(?:distance (?:to|from earth to|between earth and) the moon|the moon from earth|moon(?: from earth)?(?: from us)?)" to
            "The Moon is about 384,400 kilometers from Earth.",
        "(?:distance (?:to|from earth to|between earth and) the sun|the sun from earth|sun(?: from earth)?(?: from us)?)" to
            "The Sun is about 150 million kilometers from Earth.",
        "speed of light|light travel|fast does light travel" to "Light travels about 300,000 kilometers per second.",
        "speed of sound|sound travel|fast does sound travel" to "Sound travels about 343 meters per second in air.",
        "boiling point of water|water boil(?: at)?|temperature (?:does )?water boils?(?: at)?" to "Water boils at 100 degrees Celsius at sea level.",
        "freezing point of water|water freeze(?: at)?|temperature (?:does )?water freezes?(?: at)?" to "Water freezes at 0 degrees Celsius.",
        "(?:days are there in|days in|days does) (?:a |one )?year(?: have)?" to "A year has 365 days, or 366 in a leap year.",
        "(?:days are there in|days in|days does) (?:a |one )?week(?: have)?" to "A week has 7 days.",
        "(?:weeks are there in|weeks in|weeks does) (?:a |one )?year(?: have)?" to "A year has 52 weeks and one or two days.",
        "(?:hours are there in|hours in|hours does) (?:a |one )?day(?: have)?" to "A day has 24 hours.",
        "(?:seconds are there in|seconds in) (?:a |one )?(?:minute)" to "A minute has 60 seconds.",
        "(?:seconds are there in|seconds in) (?:a |one )?(?:hour)" to "An hour has 3,600 seconds.",
        "(?:seconds are there in|seconds in) (?:a |one )?(?:day)" to "A day has 86,400 seconds.",
        "(?:planets are there|planets in (?:the |our )?solar system)" to
            "There are 8 planets: Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus and Neptune.",
        "(?:biggest|largest) planet(?: in (?:the |our )?solar system)?" to "Jupiter is the biggest planet.",
        "(?:smallest) planet(?: in (?:the |our )?solar system)?" to "Mercury is the smallest planet.",
        "(?:closest|nearest) planet to the sun" to "Mercury is the closest planet to the Sun.",
        "(?:hottest) planet(?: in (?:the |our )?solar system)?" to "Venus is the hottest planet.",
        "(?:continents are there|continents in the world)" to
            "There are 7 continents: Asia, Africa, North America, South America, Antarctica, Europe and Australia.",
        "(?:oceans are there|oceans in the world)" to "There are 5 oceans: Pacific, Atlantic, Indian, Southern and Arctic.",
        "(?:biggest|largest) ocean(?: in the world)?" to "The Pacific is the biggest ocean.",
        "(?:tallest|highest) mountain(?: in the world)?|mount everest|everest" to "Mount Everest is the tallest mountain, about 8,849 meters high.",
        "(?:longest) river(?: in the world)?" to "The Nile is usually called the longest river, about 6,650 kilometers.",
        "(?:biggest|largest) country(?: in the world)?" to "Russia is the biggest country by area.",
        "(?:smallest) country(?: in the world)?" to "Vatican City is the smallest country.",
        "(?:biggest|largest|most populated|most populous) country by population|country with the most people" to
            "India has the most people, just ahead of China.",
        "(?:biggest|largest) animal(?: in the world)?" to "The blue whale is the biggest animal ever known.",
        "(?:fastest) (?:land )?animal(?: in the world)?" to "The cheetah is the fastest land animal, up to about 110 kilometers per hour.",
        "(?:bones are there in|bones in|bones does) (?:the |a )?(?:human body|adult human|body)(?: have)?" to "An adult human has 206 bones.",
        "(?:teeth does|teeth do|teeth in) (?:an )?(?:adult|human|person|people)(?: have)?" to "An adult has 32 teeth.",
        "(?:chemical formula (?:of|for) water|formula of water)" to "Water is H2O.",
        "(?:value of )?pi" to "Pi is about 3.14159.",
    ).map { (q, a) -> Regex("^$ASK(?:$q)(?: in kilometers| in km| in miles| from here)?$") to a }

    private fun fact(t: String): String? = FACTS.firstOrNull { it.first.matches(t) }?.second

    // ---- moon phase (simple synodic month from a known new moon) ----

    private val MOON = Regex("^(?:whats |what is |tell me |show me )?(?:the |todays |tonights )?(?:moon phase|phase of the moon|moon)(?: today| tonight| now)?$|^is (?:it|there) (?:a )?full moon(?: today| tonight)?$")

    private fun moon(t: String, now: ZonedDateTime): String? {
        if (!MOON.matches(t)) return null
        val days = (now.toInstant().epochSecond - 947_182_440L) / 86_400.0 // new moon 6 Jan 2000 18:14 UTC
        val age = ((days % 29.530588853) + 29.530588853) % 29.530588853
        val phase = when {
            age < 1.85 -> "a new moon"
            age < 5.54 -> "a waxing crescent"
            age < 9.23 -> "the first quarter"
            age < 12.92 -> "a waxing gibbous"
            age < 16.61 -> "a full moon"
            age < 20.30 -> "a waning gibbous"
            age < 23.99 -> "the last quarter"
            age < 27.68 -> "a waning crescent"
            else -> "a new moon"
        }
        if (t.contains("full moon")) {
            return if (phase == "a full moon") "Yes, tonight is a full moon, ${Persona.ADDRESS}." else "No, tonight it's $phase, ${Persona.ADDRESS}."
        }
        return "Tonight it's $phase, ${Persona.ADDRESS}."
    }

    // ---- spelling ----

    private val SPELL = Regex("^(?:how (?:do|does|would) (?:you|i|one) spell|how is|can you spell|please spell|spell|spelling of|whats the spelling of|what is the spelling of) ([a-z]{2,24})(?: spelled| spelt)?$")

    /** "how do you spell necessary": speech-to-text already wrote the word right, so read its letters. */
    private fun spell(t: String): String? {
        val m = SPELL.matchEntire(t) ?: return null
        val w = m.groupValues[1]
        if (t.startsWith("how is ") && !t.endsWith(" spelled") && !t.endsWith(" spelt")) return null
        return "${w.replaceFirstChar { it.uppercase() }} is spelled ${w.uppercase().toList().joinToString("-")}."
    }

    // ---- capitals (the small model often gets these wrong) ----

    private val CAPITALS = mapOf(
        "bangladesh" to "Dhaka", "india" to "New Delhi", "pakistan" to "Islamabad", "nepal" to "Kathmandu",
        "bhutan" to "Thimphu", "sri lanka" to "Colombo", "myanmar" to "Naypyidaw", "china" to "Beijing",
        "japan" to "Tokyo", "south korea" to "Seoul", "north korea" to "Pyongyang", "thailand" to "Bangkok",
        "malaysia" to "Kuala Lumpur", "indonesia" to "Jakarta", "philippines" to "Manila", "vietnam" to "Hanoi",
        "singapore" to "Singapore", "afghanistan" to "Kabul", "iran" to "Tehran", "iraq" to "Baghdad",
        "saudi arabia" to "Riyadh", "uae" to "Abu Dhabi", "united arab emirates" to "Abu Dhabi", "qatar" to "Doha",
        "kuwait" to "Kuwait City", "oman" to "Muscat", "turkey" to "Ankara", "egypt" to "Cairo", "israel" to "Jerusalem",
        "palestine" to "Ramallah", "syria" to "Damascus", "jordan" to "Amman", "lebanon" to "Beirut",
        "russia" to "Moscow", "ukraine" to "Kyiv", "uk" to "London", "united kingdom" to "London",
        "england" to "London", "france" to "Paris", "germany" to "Berlin", "italy" to "Rome", "spain" to "Madrid",
        "portugal" to "Lisbon", "netherlands" to "Amsterdam", "belgium" to "Brussels", "switzerland" to "Bern",
        "austria" to "Vienna", "sweden" to "Stockholm", "norway" to "Oslo", "denmark" to "Copenhagen",
        "finland" to "Helsinki", "poland" to "Warsaw", "greece" to "Athens", "ireland" to "Dublin",
        "usa" to "Washington, D.C.", "america" to "Washington, D.C.", "united states" to "Washington, D.C.",
        "canada" to "Ottawa", "mexico" to "Mexico City", "brazil" to "Brasilia", "argentina" to "Buenos Aires",
        "australia" to "Canberra", "new zealand" to "Wellington", "south africa" to "Pretoria", "nigeria" to "Abuja",
        "kenya" to "Nairobi", "ethiopia" to "Addis Ababa", "morocco" to "Rabat", "maldives" to "Male",
    )
    private val CAPITAL = Regex("^(?:whats |what is |tell me |which is |which city is )?(?:the )?capital (?:city )?of (?:the )?([a-z ]+?)$|^([a-z ]+?)s capital(?: city)?$")

    private fun capital(t: String): String? {
        val m = CAPITAL.matchEntire(t) ?: return null
        val place = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val city = CAPITALS[place] ?: return null
        val name = if (place.length <= 3) place.uppercase() else place.split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        return "The capital of $name is ${city.trimEnd('.')}."
    }

    private fun prepare(input: String): String {
        val t = input.lowercase().replace("'", "").replace("’", "")
            .replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        return NumberWords.normalize(t).replace(Regex("^(?:(?:hey |ok |okay )?(?:jimi |jimmy )?)"), "")
            .replace(Regex("(?:please|jimi)$"), "").trim()
    }

    // ---- world time ----

    private val CITIES = mapOf(
        "london" to "Europe/London", "uk" to "Europe/London", "england" to "Europe/London",
        "paris" to "Europe/Paris", "france" to "Europe/Paris", "berlin" to "Europe/Berlin", "germany" to "Europe/Berlin",
        "rome" to "Europe/Rome", "italy" to "Europe/Rome", "madrid" to "Europe/Madrid", "spain" to "Europe/Madrid",
        "moscow" to "Europe/Moscow", "russia" to "Europe/Moscow", "istanbul" to "Europe/Istanbul", "turkey" to "Europe/Istanbul",
        "new york" to "America/New_York", "washington" to "America/New_York", "toronto" to "America/Toronto",
        "chicago" to "America/Chicago", "los angeles" to "America/Los_Angeles", "california" to "America/Los_Angeles",
        "san francisco" to "America/Los_Angeles", "america" to "America/New_York", "usa" to "America/New_York",
        "canada" to "America/Toronto", "dubai" to "Asia/Dubai", "uae" to "Asia/Dubai", "abu dhabi" to "Asia/Dubai",
        "saudi" to "Asia/Riyadh", "saudi arabia" to "Asia/Riyadh", "riyadh" to "Asia/Riyadh", "mecca" to "Asia/Riyadh",
        "makkah" to "Asia/Riyadh", "medina" to "Asia/Riyadh", "madina" to "Asia/Riyadh", "jeddah" to "Asia/Riyadh",
        "qatar" to "Asia/Qatar", "doha" to "Asia/Qatar", "kuwait" to "Asia/Kuwait", "oman" to "Asia/Muscat",
        "india" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "new delhi" to "Asia/Kolkata", "kolkata" to "Asia/Kolkata",
        "mumbai" to "Asia/Kolkata", "pakistan" to "Asia/Karachi", "karachi" to "Asia/Karachi", "nepal" to "Asia/Kathmandu",
        "kathmandu" to "Asia/Kathmandu", "dhaka" to "Asia/Dhaka", "bangladesh" to "Asia/Dhaka",
        "malaysia" to "Asia/Kuala_Lumpur", "kuala lumpur" to "Asia/Kuala_Lumpur", "singapore" to "Asia/Singapore",
        "thailand" to "Asia/Bangkok", "bangkok" to "Asia/Bangkok", "china" to "Asia/Shanghai", "beijing" to "Asia/Shanghai",
        "hong kong" to "Asia/Hong_Kong", "japan" to "Asia/Tokyo", "tokyo" to "Asia/Tokyo", "korea" to "Asia/Seoul",
        "seoul" to "Asia/Seoul", "australia" to "Australia/Sydney", "sydney" to "Australia/Sydney",
        "melbourne" to "Australia/Melbourne", "egypt" to "Africa/Cairo", "cairo" to "Africa/Cairo",
    )
    private val CITY = CITIES.keys.sortedByDescending { it.length }.joinToString("|")
    private val WORLD_TIME = Regex(
        "^(?:(?:whats|what is|tell me|check)(?: the)? )?(?:current |local )?time (?:is it )?(?:now |right now )?(?:in|at) ($CITY)(?: now| right now)?" +
            "|what time is it (?:now |right now )?in ($CITY)(?: now| right now)?" +
            "|(?:whats|what is) ($CITY) time(?: now)?$",
    )

    private fun worldTime(t: String, now: ZonedDateTime): String? {
        val m = WORLD_TIME.matchEntire(t) ?: return null
        val city = m.groupValues.drop(1).first { it.isNotEmpty() }
        val there = now.withZoneSameInstant(ZoneId.of(CITIES.getValue(city)))
        val day = if (there.toLocalDate() == now.toLocalDate()) "" else ", ${there.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}"
        val name = city.split(' ').joinToString(" ") { w -> if (w in setOf("uk", "usa", "uae")) w.uppercase() else w.replaceFirstChar(Char::uppercase) }
        return "It's ${there.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))} in $name$day."
    }

    // ---- units ----

    private class Unit(val kind: String, val toBase: Double, val one: String, val many: String)

    private val UNITS: Map<String, Unit> = buildMap {
        fun add(kind: String, f: Double, one: String, many: String, vararg names: String) {
            val u = Unit(kind, f, one, many)
            for (n in names) put(n, u)
        }
        add("mass", 0.001, "milligram", "milligrams", "mg", "milligram", "milligrams")
        add("mass", 1.0, "gram", "grams", "g", "gram", "grams", "gm", "gms")
        add("mass", 1000.0, "kilogram", "kilograms", "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
        add("mass", 453.59237, "pound", "pounds", "lb", "lbs", "pound", "pounds")
        add("mass", 28.349523125, "ounce", "ounces", "oz", "ounce", "ounces")
        add("mass", 1_000_000.0, "ton", "tons", "ton", "tons", "tonne", "tonnes")
        add("length", 0.001, "millimeter", "millimeters", "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        add("length", 0.01, "centimeter", "centimeters", "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        add("length", 1.0, "meter", "meters", "m", "meter", "meters", "metre", "metres")
        add("length", 1000.0, "kilometer", "kilometers", "km", "kms", "kilometer", "kilometers", "kilometre", "kilometres")
        add("length", 0.0254, "inch", "inches", "inch", "inches")
        add("length", 0.3048, "foot", "feet", "ft", "foot", "feet")
        add("length", 0.9144, "yard", "yards", "yard", "yards")
        add("length", 1609.344, "mile", "miles", "mile", "miles")
        add("volume", 0.001, "milliliter", "milliliters", "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        add("volume", 1.0, "liter", "liters", "l", "liter", "liters", "litre", "litres")
        add("volume", 3.785411784, "gallon", "gallons", "gallon", "gallons")
        add("time", 1.0, "second", "seconds", "sec", "secs", "second", "seconds")
        add("time", 60.0, "minute", "minutes", "min", "mins", "minute", "minutes")
        add("time", 3600.0, "hour", "hours", "hr", "hrs", "hour", "hours")
        add("time", 86_400.0, "day", "days", "day", "days")
        add("time", 604_800.0, "week", "weeks", "week", "weeks")
        add("temp", 1.0, "degree Celsius", "degrees Celsius", "c", "celsius", "centigrade")
        add("temp", 1.0, "degree Fahrenheit", "degrees Fahrenheit", "f", "fahrenheit")
        add("temp", 1.0, "kelvin", "kelvin", "k", "kelvin", "kelvins")
    }
    private val UNIT = UNITS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val NUM = "(\\d+(?:\\.\\d+)?|a|an|one)"
    private val CONVERT = Regex(
        "^(?:(?:convert|change|whats|what is|how much is|how many is)(?: the)? )?$NUM ?(?:degrees? )?($UNIT) (?:to|in|into|in to|is how many|equals how many) (?:degrees? )?($UNIT)$",
    )
    private val HOW_MANY = Regex(
        "^how many ($UNIT) (?:are |is )?(?:there )?(?:in|make|makes|is) (?:a |an |one |(\\d+(?:\\.\\d+)?) )?($UNIT)$",
    )

    private fun units(t: String): String? {
        CONVERT.matchEntire(t)?.let { m -> return convert(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        HOW_MANY.matchEntire(t)?.let { m -> return convert(m.groupValues[2].ifEmpty { "1" }, m.groupValues[3], m.groupValues[1]) }
        return null
    }

    private fun convert(amount: String, fromName: String, toName: String): String? {
        val n = when (amount) { "a", "an", "one" -> 1.0; else -> amount.toDoubleOrNull() } ?: return null
        val from = UNITS.getValue(fromName)
        val to = UNITS.getValue(toName)
        if (from.kind != to.kind || from === to) return null
        val v = if (from.kind == "temp") temperature(n, from.one, to.one) else n * from.toBase / to.toBase
        return "${show(n)} ${if (n == 1.0) from.one else from.many} is ${show(v)} ${if (v == 1.0) to.one else to.many}."
    }

    private fun temperature(n: Double, from: String, to: String): Double {
        val c = when {
            "Fahrenheit" in from -> (n - 32) * 5 / 9
            from == "kelvin" -> n - 273.15
            else -> n
        }
        return when {
            "Fahrenheit" in to -> c * 9 / 5 + 32
            to == "kelvin" -> c + 273.15
            else -> c
        }
    }

    private fun show(v: Double): String {
        val r = BigDecimal(v).round(MathContext(6)).let {
            if (it.abs() >= BigDecimal.ONE) it.setScale(2, RoundingMode.HALF_UP) else it
        }.stripTrailingZeros()
        return if (r.signum() == 0) "0" else r.toPlainString()
    }

    // ---- dates ----

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )
    private val SHORT = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
    private val MONTH = (MONTHS + SHORT.keys).sortedByDescending { it.length }.joinToString("|")
    private const val DAY = "(\\d{1,2})(?:st|nd|rd|th)?"
    private val DATE = "(?:(?:the )?$DAY (?:of )?($MONTH)|($MONTH) (?:the )?$DAY)(?: (\\d{4}))?"
    private val DAYS_UNTIL = Regex("^how many days (?:are )?(?:left )?(?:until|till|til|to|before|for|left for|left until|left till) $DATE$")
    private val WHAT_DAY = Regex("^(?:what|which) day (?:of the week )?(?:is|was|will be|falls on|is it on) $DATE$")

    private val DAYS_IN_MONTH = Regex("^how many days (?:are there )?(?:in|does) ($MONTH)(?: (\\d{4}))?(?: have)?$")
    private val LEAP = Regex("^is (\\d{4}) a leap year$|^is (?:this|it) (?:a )?leap year(?: this year)?$")
    private val WHAT_YEAR = Regex("^(?:whats|what is|which|what) (?:the )?(?:current )?year(?: is it)?(?: now)?$")
    private val WHAT_MONTH = Regex("^(?:whats|what is|which|what) (?:the )?(?:current )?month(?: is it)?(?: now)?$")
    private val DAYS_LEFT = Regex("^how many days (?:are )?(?:left|remaining) (?:in|of|this) (?:the |this )?year$")
    private val WEEKDAYS = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase() }
    private val NEXT_DAY = Regex(
        "^(?:whats|what is|which) (?:the )?date (?:is it )?(?:on )?(next|this|coming|this coming) (${WEEKDAYS.joinToString("|")})$" +
            "|^(?:when is|what date is) (next|this|coming|this coming) (${WEEKDAYS.joinToString("|")})$",
    )

    private val HOLIDAYS = mapOf(
        "christmas" to (12 to 25), "christmas day" to (12 to 25), "xmas" to (12 to 25),
        "new year" to (1 to 1), "new years" to (1 to 1), "new years day" to (1 to 1), "new year day" to (1 to 1),
        "new years eve" to (12 to 31), "valentines day" to (2 to 14), "valentines" to (2 to 14), "halloween" to (10 to 31),
    )
    private val HOLIDAY = Regex(
        "^(?:how many days (?:are )?(?:left )?(?:until|till|til|to|before|for) |when is |what day is |how long (?:until|till|to) )" +
            "(?:the )?(?:next )?(${HOLIDAYS.keys.sortedByDescending { it.length }.joinToString("|")})(?: this year)?$",
    )
    private val YESTERDAY = Regex("^(?:what|which) day (?:was|is) (?:it )?yesterday$|^(?:whats|what is|what was) (?:the )?(?:date )?yesterdays? ?(?:date)?$")

    private fun dates(t: String, today: LocalDate): String? {
        HOLIDAY.matchEntire(t)?.let { m ->
            val (month, day) = HOLIDAYS.getValue(m.groupValues[1])
            var d = LocalDate.of(today.year, month, day)
            if (d.isBefore(today)) d = d.plusYears(1)
            val n = ChronoUnit.DAYS.between(today, d)
            val name = when (month to day) { 12 to 25 -> "Christmas"; 1 to 1 -> "New Year's Day"; 12 to 31 -> "New Year's Eve"; 2 to 14 -> "Valentine's Day"; else -> "Halloween" }
            val when_ = when (n) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $n days" }
            return "$name is ${spoken(d, today)}, a ${weekday(d)}, $when_."
        }
        if (YESTERDAY.matches(t)) {
            val d = today.minusDays(1)
            return "Yesterday was ${weekday(d)}, ${spoken(d, today)}."
        }
        DAYS_IN_MONTH.matchEntire(t)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[1]).takeIf { it >= 0 }?.plus(1) ?: SHORT[m.groupValues[1]] ?: return null
            val year = m.groupValues[2].toIntOrNull() ?: today.year
            val name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.US)
            val n = YearMonth.of(year, month).lengthOfMonth()
            return if (month == 2) "$name $year has $n days." else "$name has $n days."
        }
        LEAP.matchEntire(t)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: today.year
            return if (Year.isLeap(year.toLong())) "Yes, $year is a leap year." else "No, $year is not a leap year."
        }
        if (WHAT_YEAR.matches(t)) return "It's ${today.year}."
        if (WHAT_MONTH.matches(t)) return "It's ${today.month.getDisplayName(TextStyle.FULL, Locale.US)}."
        if (DAYS_LEFT.matches(t)) {
            val n = ChronoUnit.DAYS.between(today, LocalDate.of(today.year, 12, 31))
            return "$n days are left in ${today.year}."
        }
        NEXT_DAY.matchEntire(t)?.let { m ->
            val word = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val dow = DayOfWeek.entries[WEEKDAYS.indexOf(word)]
            val d = today.with(TemporalAdjusters.next(dow))
            return "${dow.getDisplayName(TextStyle.FULL, Locale.US)} is ${spoken(d, today)}."
        }
        DAYS_UNTIL.matchEntire(t)?.let { m ->
            val d = date(m, today, futureOnly = true) ?: return null
            val n = ChronoUnit.DAYS.between(today, d)
            val when_ = when (n) { 0L -> "today"; 1L -> "tomorrow"; else -> if (n > 0) "in $n days" else "${-n} days ago" }
            return "${spoken(d, today)} is $when_, a ${weekday(d)}."
        }
        WHAT_DAY.matchEntire(t)?.let { m ->
            val d = date(m, today, futureOnly = false) ?: return null
            val verb = when { d.isBefore(today) -> "was"; d == today -> "is"; else -> "is" }
            return "${spoken(d, today)} $verb a ${weekday(d)}."
        }
        return null
    }

    private fun date(m: MatchResult, today: LocalDate, futureOnly: Boolean): LocalDate? {
        val g = m.groupValues
        val day = (g[1].ifEmpty { g[4] }).toInt()
        val monthWord = g[2].ifEmpty { g[3] }
        val month = MONTHS.indexOf(monthWord).takeIf { it >= 0 }?.plus(1) ?: SHORT[monthWord] ?: return null
        return try {
            if (g[5].isNotEmpty()) return LocalDate.of(g[5].toInt(), month, day)
            val d = LocalDate.of(today.year, month, day)
            if (futureOnly && d.isBefore(today)) d.plusYears(1) else d
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)

    private fun spoken(d: LocalDate, today: LocalDate): String {
        val base = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.US)}"
        return if (d.year == today.year) base else "$base ${d.year}"
    }
}
