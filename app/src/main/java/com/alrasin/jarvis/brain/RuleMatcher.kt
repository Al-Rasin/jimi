package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Brain level 1 (PLAN 5.1): fast patterns for common commands → [ToolCall] (< 1 ms, no model).
 * Input: any text; it is normalized here (lower case, no punctuation, numbers as digits).
 * Returns null when no rule fits (then the LLM / "I can't do that yet" takes over).
 */
object RuleMatcher {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    private val TOGGLE_TARGETS = linkedMapOf(
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

    fun normalize(text: String): String = NumberWords.normalize(
        text.lowercase()
            .replace("%", " percent")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace("'s ", "s ").replace("'", "")
            .replace(Regex("\\s+"), " ").trim(),
    ).let { polite(it) }

    /** Drops "please", "can you", "jarvis" etc. around the command. */
    private fun polite(t: String): String {
        var s = " $t "
        listOf(
            " please ", " pls ", " can you ", " could you ", " would you ", " will you ", " i want you to ",
            " i want to ", " i would like to ", " i'd like to ", " for me ", " jarvis ", " jimi ", " jimmy ",
            " hey ", " ok ", " okay ", " just ", " now ",
        ).forEach { w -> s = s.replace(w, " ") }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun match(input: String, now: LocalDateTime = LocalDateTime.now()): ToolCall? {
        val t = normalize(input)
        if (t.isEmpty()) return null
        return talk(t) ?: toggle(t) ?: time(t, now) ?: notes(t, now) ?: comms(t) ?: media(t) ?: system(t) ?: screen(t)
            ?: notifications(t) ?: knowledge(t) ?: memory(t) ?: apps(t)
    }

    private fun call(tool: String, vararg args: Pair<String, String?>) =
        ToolCall(tool, args.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!!.trim() })

    private fun re(p: String) = Regex("^(?:$p)$")

    private fun talk(t: String): ToolCall? = when {
        t.matches(re("cancel|stop|never ?mind|forget it|nothing|shut up|be quiet|quiet|stop talking")) -> call("cancel")
        t.matches(re("(?:say (?:that|it) again|repeat(?: that| it)?|what did you say|come again|pardon)")) -> call("repeat_last")
        else -> null
    }

    private fun toggle(t: String): ToolCall? {
        val on = Regex("\\b(turn on|switch on|enable|activate|start|open)\\b").containsMatchIn(t) || t.endsWith(" on")
        val off = Regex("\\b(turn off|switch off|disable|deactivate|stop|close|shut off)\\b").containsMatchIn(t) || t.endsWith(" off")
        if (on == off) {
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

    private fun time(t: String, now: LocalDateTime): ToolCall? {
        // timer
        val timer = Regex("^(?:set |start |put )?(?:a |an )?(?:timer|countdown)(?: for| of)? (.+)$").find(t)
            ?: Regex("^(?:set |start |put )?(?:a |an )?(.+?) (?:timer|countdown)$").find(t)
        timer?.let { m -> TimeParser.duration(m.groupValues[1])?.let { return call("set_timer", "seconds" to it.toString()) } }
        if (t.matches(re("(?:cancel|stop|delete|turn off|dismiss|remove|clear) (?:the |my |all )*(?:alarms?|timers?)"))) return call("cancel_alarm")
        // alarm
        val alarm = Regex("^(?:set |create |make |add )?(?:an |a |my )?alarm(?: for| at| to)? (.+)$").find(t)
            ?: Regex("^wake me(?: up)?(?: at| by)? (.+)$").find(t)
            ?: Regex("^set (?:an |a )?alarm(?: for)? (.+)$").find(t)
        if (alarm != null) {
            val spec = alarm.groupValues[1]
            val label = Regex("\\b(?:called|named|labelled|labeled|for) ([a-z ]+)$").find(spec)?.groupValues?.get(1)
                ?.takeUnless { it.matches(Regex(".*\\d.*|tomorrow|today|tonight")) }
            val specNoLabel = if (label != null) spec.substringBeforeLast(label).removeSuffix(" ").removeSuffix("called").removeSuffix("named").removeSuffix("for").trim() else spec
            val m = TimeParser.parse("at $specNoLabel".replace("at at ", "at "), now) ?: return call("set_alarm", "time" to null, "label" to label)
            if (!m.hasTime) return null
            val days = if (m.repeat == "daily") "daily" else if (m.repeat == "weekly") m.at.dayOfWeek.name.lowercase() else null
            return call("set_alarm", "time" to m.at.format(HM), "label" to label, "repeat" to days,
                "date" to if (m.at.toLocalDate() != now.toLocalDate() && days == null) m.at.toLocalDate().toString() else null)
        }
        return null
    }

    private fun notes(t: String, now: LocalDateTime): ToolCall? {
        if (t.matches(re("(?:read|show|tell me|list|what are|whats in|open)(?: me)? (?:all )?(?:my |the )?notes?|my notes|notes"))) return call("read_notes")
        Regex("^(?:take|make|create|add|write|save) (?:a |an )?(?:new )?note(?: that| saying| to say| about)?:? (.+)$").find(t)
            ?.let { return call("create_note", "text" to it.groupValues[1]) }
        Regex("^(?:note|note down|write down|jot down)(?: that)? (.+)$").find(t)?.let { return call("create_note", "text" to it.groupValues[1]) }
        if (t.matches(re("(?:what are |show |list |read |tell me )?(?:me )?(?:all )?(?:my |the )?(?:reminders|upcoming reminders)(?: for today| today)?|do i have any reminders(?: today)?|any reminders(?: today)?"))) return call("list_reminders")
        Regex("^(?:delete|remove|cancel|clear) (?:the |my )?(all )?reminders?(?: (?:to|about|for) (.+))?$").find(t)?.let {
            return call("delete_reminder", "text" to (it.groupValues[2].ifEmpty { if (it.groupValues[1].isNotEmpty()) "all" else "" }))
        }
        val rem = Regex("^(?:remind me|set (?:a |an )?reminder|create (?:a |an )?reminder|add (?:a |an )?reminder|reminder)(?: to| that| about| for)?(.*)$").find(t)
        if (rem != null) {
            val m = TimeParser.parse(rem.groupValues[1], now)
            val text = (m?.rest ?: rem.groupValues[1]).trim()
                .removePrefix("to ").removePrefix("that ").removePrefix("about ").removeSuffix(" at").trim()
            return call("create_reminder", "text" to text.ifEmpty { "reminder" }, "time" to m?.at?.format(ISO), "repeat" to m?.repeat?.takeIf { it != "none" })
        }
        return null
    }

    private fun comms(t: String): ToolCall? {
        Regex("^(?:call|phone|ring|dial)(?: to)? (.+?)(?: on (?:speaker|whatsapp))?$").find(t)?.let {
            if (t.contains(" on whatsapp")) return call("call", "contact" to it.groupValues[1], "app" to "whatsapp")
            return call("call", "contact" to it.groupValues[1])
        }
        Regex("^(?:send |write )?(?:a )?(?:whatsapp|whats app)(?: message| text)?(?: to)? ([a-z0-9 ]+?)(?: saying| that says| that| say|:)? (?:saying |that )?(.+)$").find(t)
            ?.let { return call("send_whatsapp", "contact" to splitContact(it.groupValues[1], it.groupValues[2]).first, "text" to splitContact(it.groupValues[1], it.groupValues[2]).second) }
        Regex("^(?:send|write) (?:a )?(?:message|text|sms|msg) to ([a-z0-9 ]+?) on (?:whatsapp|whats app)(?: saying| that)? (.+)$").find(t)
            ?.let { return call("send_whatsapp", "contact" to it.groupValues[1], "text" to it.groupValues[2]) }
        Regex("^(?:send |write )?(?:a |an )?(?:message|text|sms|msg) to ([a-z0-9 ]+?)(?: saying| that says| that)? (.+)$").find(t)
            ?.let { return call("send_sms", "contact" to it.groupValues[1], "text" to it.groupValues[2]) }
        Regex("^(?:text|message|sms) ([a-z]+(?: [a-z]+)?) (?:saying |that )(.+)$").find(t)
            ?.let { return call("send_sms", "contact" to it.groupValues[1], "text" to it.groupValues[2]) }
        if (t.matches(re("(?:read|check|show|any|do i have any)(?: me)?(?: my)?(?: new| unread| latest| last)? (?:messages|texts|sms)|(?:read|show) (?:the )?last (?:message|text|sms)"))) return call("read_messages")
        Regex("^(?:send|write|compose) (?:an |a )?(?:email|mail|e mail)(?: to ([a-z0-9 ]+?))?(?: about (.+))?$").find(t)
            ?.let { return call("compose_email", "to" to it.groupValues[1], "subject" to it.groupValues[2]) }
        return null
    }

    /** "send whatsapp to mom hi" has no clear end of the name; keep 1 word unless the message is empty. */
    private fun splitContact(name: String, text: String) = name to text

    private fun media(t: String): ToolCall? {
        when {
            t.matches(re("(?:pause|stop)(?: the)?(?: music| song| video| playback| media)?|pause it")) -> return call("media_control", "action" to "pause")
            t.matches(re("(?:resume|continue|unpause|play)(?: the)?(?: music| song| video| playback)?|play it")) -> return call("media_control", "action" to "play")
            t.matches(re("(?:next|skip)(?: the)?(?: song| track| video)?|play (?:the )?next(?: song| track)?|skip (?:this|it)")) -> return call("media_control", "action" to "next")
            t.matches(re("(?:previous|last|go back)(?: song| track)|play (?:the )?previous(?: song| track)?|previous")) -> return call("media_control", "action" to "previous")
        }
        Regex("^play (.+?)(?: on (youtube|you tube|spotify|youtube music))?$").find(t)?.let {
            val app = it.groupValues[2].replace("you tube", "youtube").ifEmpty { null }
            return call("play_media", "query" to it.groupValues[1].removePrefix("some ").removePrefix("the song "), "app" to app)
        }
        Regex("^(?:set |change |turn )?(?:the )?(?:volume|sound)(?: to| at)? (\\d+)(?: percent)?$").find(t)
            ?.let { return call("set_volume", "level" to it.groupValues[1]) }
        when {
            t.matches(re("(?:turn |increase |raise |volume )(?:the )?(?:volume )?up|(?:increase|raise|louder)(?: the)?(?: volume)?|make it louder|volume up|louder")) -> return call("set_volume", "level" to "up")
            t.matches(re("(?:turn |decrease |lower |reduce )(?:the )?(?:volume )?down|(?:decrease|lower|reduce|quieter)(?: the)?(?: volume)?|make it quieter|volume down|quieter")) -> return call("set_volume", "level" to "down")
            t.matches(re("mute(?: the)?(?: phone| volume| sound)?|(?:volume|sound) off")) -> return call("set_volume", "level" to "0")
            t.matches(re("(?:max|maximum|full) volume|volume (?:max|maximum|full)")) -> return call("set_volume", "level" to "100")
        }
        return null
    }

    private fun system(t: String): ToolCall? {
        Regex("^(?:set |change |turn )?(?:the )?(?:screen )?brightness(?: to| at)? (\\d+)(?: percent)?$").find(t)
            ?.let { return call("set_brightness", "level" to it.groupValues[1]) }
        when {
            t.matches(re("(?:increase|raise|turn up)(?: the)? brightness|brightness up|brighter|make (?:it|the screen) brighter")) -> return call("set_brightness", "level" to "up")
            t.matches(re("(?:decrease|lower|reduce|turn down|dim)(?: the)? (?:brightness|screen)|brightness down|dimmer|make (?:it|the screen) darker")) -> return call("set_brightness", "level" to "down")
            t.matches(re("(?:max|maximum|full) brightness|brightness (?:max|maximum|full)")) -> return call("set_brightness", "level" to "100")
            t.matches(re("(?:min|minimum|lowest) brightness|brightness (?:min|minimum|lowest)")) -> return call("set_brightness", "level" to "0")
            t.matches(re("auto brightness(?: on)?|(?:turn on |enable )?(?:auto|automatic|adaptive) brightness")) -> return call("set_brightness", "level" to "auto")
            t.matches(re("(?:whats |what is )?(?:the )?time(?: is it)?(?: now)?|what time is it|tell me the time|time now")) -> return call("device_status", "what" to "time")
            t.matches(re("(?:whats |what is )?(?:the |todays )?date(?: today)?|what day is (?:it|today)|which day is today|whats today")) -> return call("device_status", "what" to "date")
            t.matches(re("(?:whats |what is |check |how much )?(?:the |my )?(?:battery|charge)(?: level| percentage| status| left| is left)?|how much battery(?: is left| do i have)?(?: left)?|battery")) -> return call("device_status", "what" to "battery")
            t.matches(re("(?:how much |check )?(?:free )?(?:storage|space)(?: left| is left| do i have)?")) -> return call("device_status", "what" to "storage")
            t.matches(re("(?:device|phone|system) (?:status|info)|status(?: report)?|how is (?:my|the) phone")) -> return call("device_status", "what" to "all")
            t.matches(re("(?:take |capture )?(?:a )?screenshot|screen ?shot|capture (?:the )?screen")) -> return call("take_screenshot")
            t.matches(re("lock(?: the)?(?: phone| screen| device)?|lock it|turn off (?:the )?screen|screen off")) -> return call("lock_phone")
            t.matches(re("unlock(?: the)?(?: phone| screen| device)?|unlock it|open (?:the )?phone")) -> return call("unlock_phone")
            t.matches(re("good ?night|(?:i am|im) going to (?:sleep|bed)|bed ?time")) -> return call("run_routine", "name" to "good_night")
            t.matches(re("good ?morning|(?:i am|im) (?:awake|up)")) -> return call("run_routine", "name" to "good_morning")
        }
        Regex("^(?:run|start)(?: the)? (?:routine )?([a-z ]+?)(?: routine)$").find(t)?.let { return call("run_routine", "name" to it.groupValues[1].replace(' ', '_')) }
        Regex("^run (?:the )?routine ([a-z ]+)$").find(t)?.let { return call("run_routine", "name" to it.groupValues[1].replace(' ', '_')) }
        return null
    }

    private fun screen(t: String): ToolCall? {
        when {
            t.matches(re("(?:read|what is on|whats on|describe)(?: the| my)? screen|read (?:this|it)(?: page)?|what does (?:it|the screen) say")) -> return call("read_screen")
            t.matches(re("(?:go )?back|go to (?:the )?previous (?:screen|page)|press back")) -> return call("go_back")
            t.matches(re("(?:go )?home|go to (?:the )?home(?: screen)?|home screen|press home")) -> return call("go_home")
            t.matches(re("(?:open |show )?(?:the )?(?:recent apps|recents|recent|app switcher|overview)")) -> return call("open_recents")
            t.matches(re("scroll(?: the page| it)? (up|down)|(?:page|swipe) (up|down)|scroll")) -> {
                val dir = Regex("\\b(up|down)\\b").find(t)?.value ?: "down"
                return call("scroll", "direction" to dir)
            }
        }
        Regex("^(?:tap|click|press|touch|select) (?:on )?(?:the )?(.+?)(?: button)?$").find(t)?.let { return call("tap", "text" to it.groupValues[1]) }
        Regex("^(?:type|write|enter)(?: text)? (.+)$").find(t)?.let { return call("type_text", "text" to it.groupValues[1]) }
        return null
    }

    private fun notifications(t: String): ToolCall? = when {
        t.matches(re("(?:clear|dismiss|remove|delete)(?: all)?(?: my| the)? notifications")) -> call("clear_notifications")
        t.matches(re("(?:notification summary|summarize (?:my )?notifications|anything important|any important (?:notifications|messages)|what did i miss|whats new)")) -> call("notification_summary")
        t.matches(re("(?:read|check|show|any|what are)(?: me)?(?: my)?(?: new| the| latest)? notifications|do i have any notifications|notifications")) -> call("read_notifications")
        else -> null
    }

    private fun knowledge(t: String): ToolCall? {
        Regex("^(?:whats |what is |how is |hows )?(?:the )?weather(?: like)?(?: (?:today|now|tomorrow))?(?: in ([a-z ]+?))?(?: (?:today|now|tomorrow))?$").find(t)
            ?.let { return call("weather", "place" to it.groupValues[1], "day" to if (t.contains("tomorrow")) "tomorrow" else null) }
        if (t.matches(re("(?:will|is) it (?:going to )?rain(?: today| tomorrow)?|do i need an umbrella(?: today)?|how (?:hot|cold) is it(?: today| outside)?|temperature(?: outside)?|whats the temperature(?: outside)?"))) {
            return call("weather", "day" to if (t.contains("tomorrow")) "tomorrow" else null)
        }
        Regex("^(?:whats in the |read |tell me |give me |latest |todays )?(?:the )?(?:latest )?(?:news|headlines)(?: about| on)?(?: ([a-z ]+))?(?: today)?$").find(t)
            ?.let { return call("news", "topic" to it.groupValues[1].removeSuffix(" today")) }
        Regex("^(?:search|google|look up|search for|search the web for|search google for|find)(?: for)? (.+?)(?: on (?:google|the internet|the web|internet))?$").find(t)
            ?.let { if (!t.startsWith("find my")) return call("web_search", "query" to it.groupValues[1]) }
        Regex("^(?:wikipedia|wiki|look up on wikipedia|search wikipedia for|tell me about) (.+)$").find(t)
            ?.let { return call("wiki_lookup", "topic" to it.groupValues[1].removeSuffix(" on wikipedia")) }
        Regex("^(?:who is|who was|who were) (.+)$").find(t)?.let { return call("wiki_lookup", "topic" to it.groupValues[1]) }
        Regex("^(?:open|go to|visit) (?:the )?(?:website )?([a-z0-9]+(?: dot [a-z]+)+|[a-z0-9]+ (?:com|org|net|io|dev|bd))$").find(t)
            ?.let { return call("open_url", "url" to it.groupValues[1].replace(" dot ", ".").replace(Regex(" (com|org|net|io|dev|bd)$"), ".$1")) }
        return null
    }

    private fun memory(t: String): ToolCall? {
        Regex("^(?:remember|dont forget|keep in mind|note that i)(?: that)? (.+)$").find(t)?.let {
            if (it.groupValues[1].startsWith("to ")) return null   // "remember to buy milk" = reminder, handled by LLM/chat
            return call("remember_fact", "text" to it.groupValues[1])
        }
        Regex("^forget(?: that| about)? (.+)$").find(t)?.let { return call("forget_fact", "text" to it.groupValues[1]) }
        return null
    }

    private fun apps(t: String): ToolCall? {
        Regex("^(?:close|kill|exit|quit|force stop|stop) (?:the )?(.+?)(?: app| application)?$").find(t)
            ?.let { return call("close_app", "app" to it.groupValues[1]) }
        Regex("^(?:open|launch|start|run|show)(?: the| my)? (.+?)(?: app| application)?$").find(t)
            ?.let { return call("open_app", "app" to it.groupValues[1]) }
        return null
    }
}
