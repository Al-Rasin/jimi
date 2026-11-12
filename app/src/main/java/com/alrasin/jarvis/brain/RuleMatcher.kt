package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.TextNorm
import com.alrasin.jarvis.core.ToolCall
import java.time.LocalDateTime

/**
 * Brain level 1 (PLAN 5.1): fast patterns for common commands → [ToolCall] (< 1 ms, no model).
 * Input: any text; it is normalized here (lower case, no punctuation, numbers as digits).
 * Returns null when no rule fits (then the LLM / "I can't do that yet" takes over).
 */
object RuleMatcher {
    @Volatile var appNameMatches: ((String) -> Boolean)? = null


    // Debugging first: "wireless debugging" must not become Wi-Fi.
    private val TOGGLE_TARGETS = linkedMapOf(
        "wireless_debugging" to listOf("wireless debugging", "wireless debug", "wifi debugging", "wi fi debugging", "wireless adb", "adb wifi", "adb over wifi", "wifi adb"),
        "usb_debugging" to listOf("usb debugging", "usb debug", "adb debugging", "developer debugging"),
        "flashlight" to listOf("flashlight", "flash light", "torch", "flash"),
        "wifi" to listOf("wifi", "wi fi", "wireless"),
        "bluetooth" to listOf("bluetooth", "blue tooth"),
        "data" to listOf("mobile data", "data", "internet", "cellular"),
        "dnd" to listOf("do not disturb", "dnd", "silent mode", "focus mode"),
        "airplane" to listOf("airplane mode", "aeroplane mode", "flight mode", "airplane"),
        "location" to listOf("location", "gps"),
        "hotspot" to listOf("hotspot", "hot spot", "tethering"),
        "battery_saver" to listOf("battery saver", "power saving", "power saver", "battery saving"),
        "auto_rotate" to listOf("auto rotate", "rotation", "auto rotation"),
    )

    fun normalize(text: String, keepClockColon: Boolean = false): String = NumberWords.normalize(
        text.lowercase()
            .replace("%", " percent")
            .replace(if (keepClockColon) NOT_WORD_OR_COLON else NOT_WORD, " ")
            .replace("'s ", "s ").replace("'", "")
            .replace(TextNorm.SPACES, " ").trim(),
    ).let { polite(mishears(it)) }

    /** Speech-to-text mistakes seen in the user's real commands (files/commands, 2026-10-22). */
    private val MISHEARS = listOf(
        Regex("\\b(?:flush ?light|flesh ?light|flash ?lite|flashlights)\\b") to "flashlight",
        Regex("\\bsound (up|down|off|of|max|maximum|full)\\b") to "volume $1",
        Regex("\\b(volume|sound) of\\b") to "$1 off",
        Regex("\\b(?:turn|switch) of\\b") to "turn off",
    )

    private fun mishears(t: String) = MISHEARS.fold(t) { s, (r, to) -> r.replace(s, to) }

    /** Drops "please", "can you", "jarvis" etc. around the command. */
    private fun polite(t: String): String {
        var s = " $t "
        listOf(
            " please ", " pls ", " can you ", " could you ", " would you ", " will you ", " i want you to ",
            " i want to ", " i would like to ", " i'd like to ", " for me ", " jarvis ", " jimi ", " jimmy ",
            " hey ", " ok ", " okay ", " just ", " now ",
        ).forEach { w -> s = s.replace(w, " ") }
        return s.replace(TextNorm.SPACES, " ").trim()
    }

    internal fun interrupt(input: String): ToolCall? = talk(normalize(input))
        ?.takeIf { it.tool == "cancel" || it.tool == "panic" }

    fun match(input: String, now: LocalDateTime = LocalDateTime.now()): ToolCall? {
        val t = normalize(input)
        if (t.isEmpty()) return null
        val actionText = TextNorm.squash(input)
            .replace(POLITE_LEAD_RE, "")
        return talk(t) ?: TimeRules.calendar(t, now) ?: CommsRules.comms(actionText) ?: media(actionText.trimEnd('.', '!', '?')) ?: maps(t) ?: appMap(t) ?: skills(t)
            ?: knowledge(t)?.takeIf { it.tool == "web_search" || it.tool == "wiki_lookup" }
            ?: toggle(t) ?: TimeRules.time(normalize(input, keepClockColon = true), now) ?: TimeRules.notes(t, now) ?: media(t) ?: system(t) ?: screen(t)
            ?: notifications(t) ?: noisyTool(t, now) ?: knowledge(t) ?: memory(t, actionText) ?: apps(t)
    }

    internal fun call(tool: String, vararg args: Pair<String, String?>) =
        ToolCall(tool, args.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!!.trim() })

    private fun re(p: String) = Regex("^(?:$p)$")

    /** Panic word (PLAN 12.4): stop everything, lock, refuse risky things until unlocked by hand. */
    private const val PANIC = "panic|panic mode|code red|lock ?down|emergency lock|stop everything(?: and lock)?"

    private fun talk(t: String): ToolCall? = when {
        t.matches(re(PANIC)) -> call("panic")
        t.matches(TALK_WHOLE_RE) -> call("cancel")
        t.matches(TALK_WHOLE_RE_2) -> call("cancel")
        t.matches(TALK_WHOLE_RE_3) -> call("repeat_last")
        else -> null
    }

    private fun toggle(t: String): ToolCall? {
        if (TOGGLE_RE.containsMatchIn(t)) return null
        if (t == "on flushed light") return call("toggle", "target" to "flashlight", "state" to "on")
        val on = TOGGLE_RE_2.containsMatchIn(t) || t.endsWith(" on")
        val off = TOGGLE_RE_3.containsMatchIn(t) || t.endsWith(" off")
        if (on == off) {
            // "down on flashlight" / "do you mean on flashlight": STT lost "turn", but "on" right before the name is enough
            for ((target, names) in TOGGLE_TARGETS) {
                val m = Regex("\\b(on|off) (?:the |my )?(?:${names.joinToString("|")})$").find(t) ?: continue
                return call("toggle", "target" to target, "state" to m.groupValues[1])
            }
            // "toggle wifi" / "flashlight" alone
            if (!t.startsWith("toggle ")) return null
        }
        for ((target, names) in TOGGLE_TARGETS) {
            if (names.any { Regex("\\b$it\\b").containsMatchIn(t) }) {
                // "open data usage" / "start hotspot settings" are not toggles but this is close enough
                if (t.contains("settings")) return null
                return call("toggle", "target" to target, "state" to if (on && !off) "on" else if (off && !on) "off" else "toggle")
            }
        }
        return null
    }

    private fun media(t: String): ToolCall? {
        when {
            t.matches(MEDIA_WHOLE_RE) -> return call("media_control", "action" to "pause")
            t.matches(MEDIA_WHOLE_RE_2) -> return call("media_control", "action" to "play")
            t.matches(MEDIA_WHOLE_RE_3) -> return call("media_control", "action" to "next")
            t.matches(MEDIA_WHOLE_RE_4) -> return call("media_control", "action" to "previous")
        }
        storeSearch(t)?.let { return it }
        val mediaApps = "youtube music|you tube music|youtube|you tube|spotify"
        Regex("^(?:open|launch) ($mediaApps)(?: app)? and (?:play|search(?: for)?) (.+)$").find(t)?.let {
            return call("play_media", "query" to it.groupValues[2], "app" to mediaApp(it.groupValues[1]))
        }
        Regex("^search(?: for)? (.+?) (?:on|in) ($mediaApps)$").find(t)?.let {
            return call("play_media", "query" to it.groupValues[1], "app" to mediaApp(it.groupValues[2]))
        }
        Regex("^search ($mediaApps)(?: for)? (.+)$").find(t)?.let {
            return call("play_media", "query" to it.groupValues[2], "app" to mediaApp(it.groupValues[1]))
        }
        Regex("^play (.+?)(?: (?:on|in|using) ($mediaApps))?$").find(t)?.let {
            val app = it.groupValues[2].takeIf { it.isNotEmpty() }?.let(::mediaApp)
            return call("play_media", "query" to it.groupValues[1].removePrefix("some ").removePrefix("the song "), "app" to app)
        }
        MEDIA_RE.find(t)
            ?.let { return call("set_volume", "level" to it.groupValues[1]) }
        when {
            t.matches(MEDIA_WHOLE_RE_5) -> return call("set_volume", "level" to "up")
            t.matches(MEDIA_WHOLE_RE_6) -> return call("set_volume", "level" to "down")
            t.matches(MEDIA_WHOLE_RE_7) -> return call("set_volume", "level" to "0")
            t.matches(MEDIA_WHOLE_RE_8) -> return call("set_volume", "level" to "100")
        }
        return null
    }

    private const val STORE = "(?:the )?(?:google play store|google play|play store|playstore|app store)"

    /** "search X in Play Store" → Play Store results (§M.8), before appMap opens the store or web_search runs. */
    private fun storeSearch(t: String): ToolCall? {
        val s = t.trimEnd('.', '!', '?')
        val query = Regex("^(?:search|find|look for|look up)(?: for)? (.+?) (?:on|in|from) $STORE$").find(s)?.groupValues?.get(1)
            ?: Regex("^(?:search|find|look for) $STORE(?: for)? (.+)$").find(s)?.groupValues?.get(1)
            ?: Regex("^(?:open|launch|go to) $STORE(?: app)? (?:and|then) (?:search|find|look for|look up)(?: for)? (.+)$").find(s)?.groupValues?.get(1)
            ?: return null
        return call("store_search", "query" to query.replace(STORE_SEARCH_RE, ""))
    }

    private fun mediaApp(app: String) = app.replace("you tube", "youtube").replace(' ', '_')

    private fun noisyTool(t: String, now: LocalDateTime): ToolCall? {
        if (NOISY_TOOL_RE.containsMatchIn(t)) return null
        if (NOISY_TOOL_RE_2.containsMatchIn(t)) return null
        if (NOISY_TOOL_RE_3.containsMatchIn(t)) {
            return if (NOISY_TOOL_RE_4.containsMatchIn(t) && !NOISY_TOOL_RE_5.containsMatchIn(t)) {
                call("device_status", "what" to "battery")
            } else null
        }
        if (NOISY_TOOL_RE_4.containsMatchIn(t) && !NOISY_TOOL_RE_6.containsMatchIn(t)) {
            return call("device_status", "what" to "battery")
        }
        if (NOISY_TOOL_RE_7.containsMatchIn(t)) {
            TimeParser.duration(t)?.let { return call("set_timer", "seconds" to it.toString()) }
        }
        NOISY_TOOL_RE_8.find(t)?.let {
            if (TimeParser.parse("at ${it.groupValues[1]}", now)?.hasTime == true) {
                return TimeRules.time("alarm ${it.groupValues[1]}", now)
            }
        }
        NOISY_TOOL_RE_9.find(t)?.let {
            return call("set_volume", "level" to it.groupValues[1])
        }
        NOISY_TOOL_RE_10.find(t)?.let {
            return call("set_volume", "level" to when (val level = it.groupValues[1]) {
                "off" -> "0"
                "max", "maximum", "full" -> "100"
                else -> level
            })
        }
        for ((target, names) in TOGGLE_TARGETS) {
            val directions = Regex("\\b(?:${names.joinToString("|")}) (on|off)\\b").findAll(t)
                .map { it.groupValues[1] }.toSet()
            if (directions.size == 1 && !NOISY_TOOL_RE_11.findAll(t).any { it.value !in directions }) {
                return call("toggle", "target" to target, "state" to directions.single())
            }
        }
        return null
    }

    /** Any setting (D-056): "open nfc settings", "turn on dark mode", "disable battery saver mode". */
    private fun setting(t: String): ToolCall? {
        SETTING_RE.find(t)?.let {
            return call("setting", "name" to it.groupValues[1], "state" to "open")
        }
        val m = SETTING_RE_2.find(t) ?: return null
        val name = m.groupValues[2]
        if (!SETTING_RE_3.containsMatchIn(name)) return null
        val on = m.groupValues[1].let { it == "turn on" || it == "switch on" || it == "enable" }
        return call("setting", "name" to name.replace(SETTING_RE_4, ""), "state" to if (on) "on" else "off")
    }

    private fun system(t: String): ToolCall? {
        setting(t)?.let { return it }
        SYSTEM_RE.find(t)
            ?.let { return call("set_brightness", "level" to it.groupValues[1]) }
        when {
            t.matches(SYSTEM_WHOLE_RE) -> return call("set_brightness", "level" to "up")
            t.matches(SYSTEM_WHOLE_RE_2) -> return call("set_brightness", "level" to "down")
            t.matches(SYSTEM_WHOLE_RE_3) -> return call("set_brightness", "level" to "100")
            t.matches(SYSTEM_WHOLE_RE_4) -> return call("set_brightness", "level" to "0")
            t.matches(SYSTEM_WHOLE_RE_5) -> return call("set_brightness", "level" to "auto")
            t.matches(SYSTEM_WHOLE_RE_6) -> return call("device_status", "what" to "time")
            t.matches(SYSTEM_WHOLE_RE_7) -> return call("device_status", "what" to "date")
            t.matches(SYSTEM_WHOLE_RE_8) -> return call("device_status", "what" to "battery")
            t.matches(SYSTEM_WHOLE_RE_9) -> return call("device_status", "what" to "storage")
            t.matches(SYSTEM_WHOLE_RE_10) -> return call("device_status", "what" to "all")
            t.matches(SYSTEM_WHOLE_RE_11) -> return call("take_screenshot")
            t.matches(SYSTEM_WHOLE_RE_12) -> return call("lock_phone")
            t.matches(SYSTEM_WHOLE_RE_13) -> return call("unlock_phone")
            t.matches(SYSTEM_WHOLE_RE_14) -> return call("run_routine", "name" to "good_night")
            t.matches(SYSTEM_WHOLE_RE_15) -> return call("run_routine", "name" to "good_morning")
        }
        SYSTEM_RE_2.find(t)?.let { return call("run_routine", "name" to it.groupValues[1].replace(' ', '_')) }
        SYSTEM_RE_3.find(t)?.let { return call("run_routine", "name" to it.groupValues[1].replace(' ', '_')) }
        return null
    }

    private fun screen(t: String): ToolCall? {
        when {
            t.matches(SCREEN_WHOLE_RE) -> return call("read_screen")
            t.matches(SCREEN_WHOLE_RE_2) -> return call("go_back")
            t.matches(SCREEN_WHOLE_RE_3) -> return call("go_home")
            t.matches(SCREEN_WHOLE_RE_4) -> return call("open_recents")
            t.matches(SCREEN_WHOLE_RE_5) -> {
                val dir = SCREEN_RE.find(t)?.value ?: "down"
                return call("scroll", "direction" to dir)
            }
        }
        SCREEN_RE_2.find(t)?.let { return call("tap", "text" to it.groupValues[1]) }
        SCREEN_RE_3.find(t)?.let { return call("type_text", "text" to it.groupValues[1]) }
        return null
    }

    private fun notifications(t: String): ToolCall? = when {
        t.matches(NOTIFICATIONS_WHOLE_RE) -> call("clear_notifications")
        t.matches(NOTIFICATIONS_WHOLE_RE_2) -> call("notification_summary")
        t.matches(NOTIFICATIONS_WHOLE_RE_3) -> call("read_notifications")
        else -> null
    }

    private fun knowledge(t: String): ToolCall? {
        WeatherRequest.match(t)?.let { return it }
        money(t)?.let { return it }
        prayer(t)?.let { return it }
        if (t.matches(KNOWLEDGE_WHOLE_RE)) return call("phone_profile", "scan" to "true")
        if (t.matches(KNOWLEDGE_WHOLE_RE_2)) return call("phone_profile")
        if (t.matches(KNOWLEDGE_WHOLE_RE_3)) return call("phone_profile", "ask" to "apps")
        if (t.matches(KNOWLEDGE_WHOLE_RE_4)) return call("phone_profile", "ask" to "abilities")
        if (t.matches(KNOWLEDGE_WHOLE_RE_5)) return call("my_location")
        KNOWLEDGE_RE.find(t)
            ?.let { return call("weather", "place" to it.groupValues[1], "day" to if (t.contains("tomorrow")) "tomorrow" else null) }
        if (t.matches(KNOWLEDGE_WHOLE_RE_6)) {
            return call("weather", "day" to if (t.contains("tomorrow")) "tomorrow" else null)
        }
        KNOWLEDGE_RE_2.find(t)
            ?.let { return call("news", "topic" to it.groupValues[1]) }
        KNOWLEDGE_RE_3.find(t)
            ?.let { if (!t.startsWith("find my")) return call("web_search", "query" to it.groupValues[1]) }
        KNOWLEDGE_RE_4.find(t)
            ?.let { return call("wiki_lookup", "topic" to it.groupValues[1].removeSuffix(" on wikipedia")) }
        KNOWLEDGE_RE_5.find(t)?.let { return call("wiki_lookup", "topic" to it.groupValues[1]) }
        KNOWLEDGE_RE_6.find(t)
            ?.let { return call("open_url", "url" to it.groupValues[1].replace(" dot ", ".").replace(KNOWLEDGE_RE_7, ".$1")) }
        return null
    }

    private val moneyName = com.alrasin.jarvis.actions.Currency.NAME_PATTERN
    private val moneyConvert = Regex(
        "^(?:(?:whats|what is|how much is|how much are|convert|change|tell me)(?: the)? )?(?:(\\d+) )?($moneyName)" +
            "(?: to| in| into| vs| versus)? ($moneyName)(?: (?:rate|exchange rate|rate today|today|now|price))?$",
    )
    private val moneyRate = Regex(
        "^(?:(?:whats|what is|tell me|check)(?: the)? )?(?:todays |current )?(?:($moneyName) (?:rate|exchange rate|price)|" +
            "(?:exchange )?rate (?:of|for) ($moneyName))(?: today| now| in bangladesh)?$",
    )
    private val moneyHowMany = Regex("^how many ($moneyName) (?:is|are|for|in|make) (?:(\\d+) )?($moneyName)$")

    /** "100 dollars to taka", "dollar rate", "how many taka is 50 euro" (§J.8; info only, never a money action). */
    private fun money(t: String): ToolCall? {
        moneyConvert.find(t)?.let { m ->
            if (m.groupValues[2] == m.groupValues[3]) return null
            return call("currency", "from" to m.groupValues[2], "to" to m.groupValues[3], "amount" to m.groupValues[1])
        }
        moneyRate.find(t)?.let { m -> return call("currency", "from" to m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        moneyHowMany.find(t)?.let { m -> return call("currency", "from" to m.groupValues[3], "to" to m.groupValues[1], "amount" to m.groupValues[2]) }
        return null
    }

    private val prayerWord = "(?:prayer|prayers|namaz|namaj|salah|salat|azan|adhan)"
    private val prayerNames = mapOf(
        "fajr" to "fajr", "fajar" to "fajr", "fozor" to "fajr", "fojor" to "fajr", "sunrise" to "sunrise",
        "dhuhr" to "dhuhr", "zuhr" to "dhuhr", "zohr" to "dhuhr", "johr" to "dhuhr", "zuhur" to "dhuhr", "duhr" to "dhuhr", "zohor" to "dhuhr", "johor" to "dhuhr",
        "asr" to "asr", "asar" to "asr", "maghrib" to "maghrib", "magrib" to "maghrib", "sunset" to "sunset",
        "isha" to "isha", "esha" to "isha", "eshaa" to "isha", "ishaa" to "isha",
    )
    private val prayerName = prayerNames.keys.joinToString("|")
    private val prayerAll = Regex(
        "^(?:(?:whats|what are|tell me|show me|give me|read|check)(?: me)? )?(?:the )?(?:(todays|tomorrows) )?$prayerWord" +
            "(?: (?:times?|schedule|timings?|time table|timetable))(?: (?:for )?(today|tomorrow))?$",
    )
    private val prayerOne = Regex(
        "^(?:(?:when is|whens|what time is|at what time is|what time|time (?:of|for))(?: the)? )?(?:(todays|tomorrows) )?($prayerName)" +
            "(?: $prayerWord)?(?: (?:time|waqt))?(?: (?:is )?(today|tomorrow))?$",
    )
    private val prayerNext = Regex("^(?:(?:whats|what is|when is|whens|tell me)(?: the)? )?next $prayerWord(?: time)?$")

    /** Offline prayer times (§J.8): "prayer times", "when is maghrib", "fajr time tomorrow", "next prayer". */
    private fun prayer(t: String): ToolCall? {
        fun day(a: String, b: String) = if (a == "tomorrows" || b == "tomorrow") "tomorrow" else null
        if (prayerNext.matches(t)) return call("prayer_times", "prayer" to "next")
        prayerAll.find(t)?.let { return call("prayer_times", "day" to day(it.groupValues[1], it.groupValues[2])) }
        prayerOne.find(t)?.let { m ->
            if (' ' !in t) return null // a bare "sunset" / "fajr" is too vague
            return call("prayer_times", "prayer" to prayerNames.getValue(m.groupValues[2]), "day" to day(m.groupValues[1], m.groupValues[3]))
        }
        return null
    }

    /** [raw] keeps words polite() drops ("jimi", "just", "now"), so "remember jimi means my assistant" saves it all (§M.10). */
    private fun memory(t: String, raw: String = t): ToolCall? {
        if (t.matches(MEMORY_WHOLE_RE)) return call("recall_facts")
        MEMORY_RE.find(t)?.let {
            if (it.groupValues[1].startsWith("to ")) return null   // "remember to buy milk" = reminder, handled by LLM/chat
            val whole = MEMORY_RE_2.find(raw)
                ?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?', ',')?.takeIf { it.isNotBlank() }
            return call("remember_fact", "text" to (whole ?: it.groupValues[1]))
        }
        MEMORY_RE_3.find(t)?.let { return call("forget_fact", "text" to it.groupValues[1]) }
        return null
    }

    /** Maps (§H.2): "navigate to gulshan 2", "walking directions to the mosque", "show dhanmondi lake on the map". */
    private fun maps(t: String): ToolCall? {
        val travel = "walking|walk|on foot|foot|driving|drive|car|cycling|bicycle|bike|motorbike|motorcycle|scooter"
        Regex("^(?:(?:get|give me|show me|start) )?(?:($travel) )?(?:directions|route|navigation|the way) to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[2], it.groupValues[1].ifEmpty { it.groupValues[3] })
        }
        Regex("^(?:navigate|(walk|drive|cycle|ride)) (?:me )?to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[2], it.groupValues[1].ifEmpty { it.groupValues[3] })
        }
        Regex("^(?:how far(?: away)? is|whats the distance to|what is the distance to|distance to|how far (?:is it|am i) (?:to|from)|how long (?:does it take |will it take )?to (?:get|go) to) (?:the )?(.+?)(?: from here)?(?: (?:by|on) ($travel))?$").find(t)?.let {
            val p = place(it.groupValues[1])?.takeUnless { p -> NOT_ON_MAP.containsMatchIn(p) } ?: return null
            return navigate(p, it.groupValues[2])?.let { c -> call("open_map", "place" to p, "mode" to "distance", "travel" to c.arg("travel")) }
        }
        Regex("^how (?:do i|can i|to|do we|can we) (?:get|go) to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[1], it.groupValues[2])
        }
        MAPS_RE.find(t)?.let {
            return place(it.groupValues[1])?.let { p -> call("open_map", "place" to p, "mode" to "show") }
        }
        MAPS_RE_2.find(t)?.let {
            return place(it.groupValues[1])?.let { p -> call("open_map", "place" to p, "mode" to "show") }
        }
        return null
    }

    // "how far is the moon from earth" is a question for the brain, not a map.
    private val NOT_ON_MAP = Regex("\\bfrom\\b|^(?:it|that|this|there)$|\\b(?:moon|sun|mars|earth|space|planet|stars?|galaxy|jupiter|venus|saturn|mercury|pluto|universe)\\b")

    private fun navigate(place: String, travel: String): ToolCall? {
        val p = place(place) ?: return null
        val mode = when {
            travel.isEmpty() -> null
            NAVIGATE_RE.containsMatchIn(travel) -> "walk"
            NAVIGATE_RE_2.containsMatchIn(travel) -> "motorbike"
            NAVIGATE_RE_3.containsMatchIn(travel) -> "bike"
            else -> "drive"
        }
        return call("open_map", "place" to p, "mode" to "navigate", "travel" to mode)
    }

    /** Not a place: phone screens ("navigate to wifi settings") and app names stay with the app/screen tools. */
    private fun place(raw: String): String? {
        val p = raw.trim().removePrefix("the ").trim()
        if (p.isEmpty() || PLACE_RE.containsMatchIn(p)) return null
        if (appNameMatches?.invoke(p) == true) return null
        val inApp = PLACE_RE_2.find(p)?.groupValues?.get(1)
        if (inApp != null && !inApp.matches(PLACE_RE_3) && appNameMatches?.invoke(inApp) == true) return null
        return p
    }

    /** App map (ROADMAP §J.4): "open privacy settings in whatsapp", "what can you do in youtube". */
    private fun appMap(t: String): ToolCall? {
        // Permission queue (§J.7): plain "yes"/"no" stay with the confirm flow, so these need "learn it"/"skip it".
        if (t.matches(APP_MAP_WHOLE_RE)) return call("app_questions", "action" to "list")
        if (t.matches(APP_MAP_WHOLE_RE_2)) return call("app_questions", "action" to "yes")
        if (t.matches(APP_MAP_WHOLE_RE_3)) return call("app_questions", "action" to "no")
        APP_MAP_RE.find(t)
            ?.let {
                val app = it.groupValues[1]
                return if (appNameMatches?.invoke(app) == true) call("app_limits", "app" to app) else null
            }
        // Explorer (§J.5): "learn this app", "learn mi remote".
        if (t.matches(APP_MAP_WHOLE_RE_4)) return call("learn_app")
        APP_MAP_RE_2.find(t)?.let {
            val app = it.groupValues[1]
            if (appNameMatches?.invoke(app) == true) return call("learn_app", "app" to app)
        }
        // polite() already dropped "can you": "what can you do in x" arrives as "what do in x"
        APP_MAP_RE_3.find(t)?.let {
            val app = it.groupValues[1]
            return if (appNameMatches?.invoke(app) == true) call("app_abilities", "app" to app) else null
        }
        APP_MAP_RE_4.find(t)?.let {
            val app = it.groupValues[2]
            if (appNameMatches?.invoke(app) == true) return call("app_go", "app" to app, "target" to it.groupValues[1])
        }
        return null
    }

    /** General skills for any app (ROADMAP §H.4). Plain "tap X" stays on the old tap tool. */
    private fun skills(t: String): ToolCall? {
        // "open messenger and find rahim": one tool searches and opens the match (§M.9).
        SKILLS_RE
            .find(t)?.let {
                val app = it.groupValues[1]
                if (appNameMatches?.invoke(app) == true) return call("find_in_app", "query" to it.groupValues[2], "app" to app)
            }
        SKILLS_RE_2.find(t)
            ?.let { return call("find_in_app", "query" to it.groupValues[1], "app" to it.groupValues[2]) }
        SKILLS_RE_3.find(t)?.let {
            val app = it.groupValues[2]
            val web = app.matches(SKILLS_WHOLE_RE)
            if (!web && appNameMatches?.invoke(app) == true) return call("search_in_app", "query" to it.groupValues[1], "app" to app)
        }
        if (t.matches(SKILLS_WHOLE_RE_2)) return call("open_result")
        SKILLS_RE_4.find(t)
            ?.let { return call("open_result", "query" to it.groupValues[1]) }
        SKILLS_RE_5.find(t)?.let { return call("go_tab", "name" to it.groupValues[1]) }
        if (t.matches(SKILLS_WHOLE_RE_3)) {
            return call("read_list")
        }
        SKILLS_RE_6.find(t)
            ?.let { return call("toggle_named", "name" to it.groupValues[2], "state" to it.groupValues[1]) }
        SKILLS_RE_7.find(t)
            ?.let { return call("toggle_named", "name" to it.groupValues[1], "state" to it.groupValues[2]) }
        SKILLS_RE_8.find(t)
            ?.takeIf { t.matches(SKILLS_WHOLE_RE_4) }
            ?.let { return call("toggle_named", "name" to it.groupValues[1]) }
        return null
    }

    private fun apps(t: String): ToolCall? {
        APPS_RE.find(t)
            ?.let { return call("close_app", "app" to it.groupValues[1]) }
        APPS_RE_2.find(t)
            ?.let {
                val app = it.groupValues[1]
                if (!IntentRouter.isAppLaunchQuestion(t) && appNameMatches?.invoke(app) != false) {
                    return call("open_app", "app" to app)
                }
            }
        return null
    }
}
