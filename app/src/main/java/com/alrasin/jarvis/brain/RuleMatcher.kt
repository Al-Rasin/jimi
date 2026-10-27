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
    @Volatile var appNameMatches: ((String) -> Boolean)? = null

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

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
            .replace(Regex(if (keepClockColon) "[^a-z0-9': ]" else "[^a-z0-9' ]"), " ")
            .replace("'s ", "s ").replace("'", "")
            .replace(Regex("\\s+"), " ").trim(),
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
        return s.replace(Regex("\\s+"), " ").trim()
    }

    internal fun interrupt(input: String): ToolCall? = talk(normalize(input))
        ?.takeIf { it.tool == "cancel" || it.tool == "panic" }

    fun match(input: String, now: LocalDateTime = LocalDateTime.now()): ToolCall? {
        val t = normalize(input)
        if (t.isEmpty()) return null
        val actionText = input.lowercase().trim().replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:(?:hey |ok |okay )?(?:jarvis|jimi|jimmy)[, ]+|(?:please|pls|can you|could you|would you|will you|i want you to|i want to|i would like to|just|hey|ok|okay) )+"), "")
        return talk(t) ?: comms(actionText) ?: media(actionText.trimEnd('.', '!', '?')) ?: appMap(t) ?: skills(t)
            ?: knowledge(t)?.takeIf { it.tool == "web_search" || it.tool == "wiki_lookup" }
            ?: toggle(t) ?: time(normalize(input, keepClockColon = true), now) ?: notes(t, now) ?: media(t) ?: system(t) ?: screen(t)
            ?: notifications(t) ?: noisyTool(t, now) ?: knowledge(t) ?: memory(t) ?: apps(t)
    }

    private fun call(tool: String, vararg args: Pair<String, String?>) =
        ToolCall(tool, args.filter { !it.second.isNullOrBlank() }.associate { it.first to it.second!!.trim() })

    private fun re(p: String) = Regex("^(?:$p)$")

    /** Panic word (PLAN 12.4): stop everything, lock, refuse risky things until unlocked by hand. */
    private const val PANIC = "panic|panic mode|code red|lock ?down|emergency lock|stop everything(?: and lock)?"

    private fun talk(t: String): ToolCall? = when {
        t.matches(re(PANIC)) -> call("panic")
        t.matches(re("cancel|stop|never ?mind|forget it|nothing|shut up|be quiet|quiet|stop talking")) -> call("cancel")
        t.matches(re("(?:go to |go back to )?sleep(?: now)?|go quiet|take a (?:rest|break)|you can (?:sleep|rest)(?: now)?")) -> call("cancel")
        t.matches(re("(?:say (?:that|it) again|repeat(?: that| it)?|what did you say|come again|pardon)")) -> call("repeat_last")
        else -> null
    }

    private fun toggle(t: String): ToolCall? {
        if (Regex("^(?:explain|define|why|how|what does|what do|tell me about|dont|do not|never)\\b").containsMatchIn(t)) return null
        if (t == "on flushed light") return call("toggle", "target" to "flashlight", "state" to "on")
        val on = Regex("\\b(turn on|switch on|enable|activate|start|open)\\b").containsMatchIn(t) || t.endsWith(" on")
        val off = Regex("\\b(turn off|switch off|disable|deactivate|stop|close|shut off)\\b").containsMatchIn(t) || t.endsWith(" off")
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

    private fun time(t: String, now: LocalDateTime): ToolCall? {
        // timer
        val timer = Regex("^(?:set |start |put )?(?:a |an )?(?:timer|countdown)(?: for| of)? (.+)$").find(t)
            ?: Regex("^(?:set |start |put )?(?:a |an )?(.+?) (?:timer|countdown)$").find(t)
        timer?.let { m -> TimeParser.duration(m.groupValues[1])?.let { return call("set_timer", "seconds" to it.toString()) } }
        if (t.matches(re("(?:cancel|stop|delete|turn off|dismiss|remove|clear) (?:the |my |all )*(?:alarms?|timers?)"))) return call("cancel_alarm")
        oneAlarm(t, now)?.let { return it }
        // alarm
        val alarm = Regex("^(?:set |create |make |add )?(?:an |a |my )?alarm(?: for| at| to)? (.+)$").find(t)
            ?: Regex("^wake me(?: up)?(?: at| by)? (.+)$").find(t)
            ?: Regex("^set (?:an |a )?alarm(?: for)? (.+)$").find(t)
        if (alarm != null) {
            val spec = alarm.groupValues[1]
            val label = Regex("\\b(?:called|named|labelled|labeled|for) ([a-z ]+)$").find(spec)?.groupValues?.get(1)
                ?.takeUnless { it.matches(Regex(".*\\d.*|tomorrow|today|tonight")) }
            val specNoLabel = if (label != null) spec.substringBeforeLast(label).removeSuffix(" ").removeSuffix("called").removeSuffix("named").removeSuffix("for").trim() else spec
            var m = TimeParser.parse("at $specNoLabel".replace("at at ", "at "), now) ?: return call("set_alarm", "time" to null, "label" to label)
            if (!m.hasTime) return call("set_alarm", "label" to label)
            val bareClock = Regex("(?:at )?(1[0-2]|[1-9])(?: (\\d{2}))?(?: o clock)?").matchEntire(specNoLabel)
            if (bareClock != null) {
                val hour = bareClock.groupValues[1].toInt() % 12
                val minute = bareClock.groupValues[2].ifEmpty { "0" }.toInt()
                var next = now.toLocalDate().atTime(hour, minute)
                while (!next.isAfter(now)) next = next.plusHours(12)
                m = m.copy(at = next)
            }
            if (specNoLabel.startsWith("in ") || specNoLabel.startsWith("after ")) {
                if (!m.at.isAfter(now)) return call("set_alarm", "label" to label)
                // Clock intents only accept whole minutes; never ring before the requested delay.
                if (m.at.second != 0 || now.nano != 0) m = m.copy(at = m.at.withSecond(0).plusMinutes(1))
            }
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

    /** "stop the 11 am alarm", "cancel alarm at 7" → cancel only that alarm (24h time). */
    private fun oneAlarm(t: String, now: LocalDateTime): ToolCall? {
        val m = Regex("^(?:cancel|stop|delete|turn off|switch off|disable|dismiss|remove|clear) (?:the |my )?(?:alarm (?:at|for) (.+)|(.+?) alarm)$").find(t) ?: return null
        val spec = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (!spec.any(Char::isDigit)) return call("cancel_alarm")
        val at = TimeParser.parse("at $spec", now)?.takeIf { it.hasTime }?.at ?: return call("cancel_alarm")
        return call("cancel_alarm", "time" to "%02d:%02d".format(at.hour, at.minute))
    }

    private fun comms(t: String): ToolCall? {
        val calling = t.trimEnd('.', '!', '?').replace(Regex("(?: (?:please|for me|now|okay|ok))+$"), "")
        callControl(calling)?.let { return it }
        Regex("^(whats ?app|messenger) (?:(video|voice|audio) )?call(?: to)? (.+)$").find(calling)?.let {
            return call("call", "contact" to it.groupValues[3], "app" to commsApp(it.groupValues[1]),
                "video" to it.groupValues[2].takeIf { mode -> mode.isNotEmpty() }?.let { mode -> (mode == "video").toString() })
        }
        // "call using grameenphone" names a SIM, not a person: ask who.
        if (calling.matches(Regex("^(?:make a )?(?:call|phone|dial)(?: (?:using|with|via|from|on) (?!whats ?app|messenger).+| sim ?\\d?.*)$"))) return call("call")
        Regex("^(?:(video|voice|audio) )?(?:call|phone|ring|dial)(?: to)? (.+?)(?: (?:on|via|using) (whats ?app|messenger|phone|speaker))?$").find(calling)?.let {
            return call("call", "contact" to it.groupValues[2], "app" to it.groupValues[3].takeUnless { app -> app.isEmpty() || app == "speaker" }?.let(::commsApp),
                "video" to it.groupValues[1].takeIf { mode -> mode.isNotEmpty() }?.let { mode -> (mode == "video").toString() })
        }
        Regex("^(?:(?:send|write) (?:a |an )?)?(whats ?app|messenger)(?: message| text)?( to)? (.+)$").find(t)?.let {
            return message(it.groupValues[3], commsApp(it.groupValues[1]), addressed = it.groupValues[2].isNotEmpty())
        }
        Regex("^(?:(?:send|write) (?:a |an )?)?(?:message|text|sms|msg)(?: (?:on|via|using|in) (whats ?app|messenger|sms))?( to)? (.+)$").find(t)?.let {
            return message(it.groupValues[3], it.groupValues[1].takeIf(String::isNotEmpty)?.let(::commsApp), addressed = it.groupValues[2].isNotEmpty())
        }
        // "send a message", "send a whatsapp message", "message on messenger" → ask who and what.
        Regex("^(?:(?:send|write) )?(?:a |an )?(?:(whats ?app|messenger) )?(?:message|text|msg)(?: (?:on|via|using|in) (whats ?app|messenger))?$").find(t)?.let {
            val app = (it.groupValues[1].ifEmpty { it.groupValues[2] }).takeIf(String::isNotEmpty)?.let(::commsApp)
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "app" to app?.takeIf { a -> a == "messenger" })
        }
        anyOrderChat(t)?.let { return it }
        if (normalize(t).matches(re("(?:read|check|show|any|do i have any)(?: me)?(?: my)?(?: new| unread| latest| last)? (?:messages|texts|sms)|(?:read|show) (?:the )?last (?:message|text|sms)"))) return call("read_messages")
        Regex("^(?:send|write|compose) (?:an |a )?(?:email|mail|e mail)(?: to ([a-z0-9 ]+?))?(?: about (.+))?$").find(t)
            ?.let { return call("compose_email", "to" to it.groupValues[1], "subject" to it.groupValues[2]) }
        return null
    }

    /** Ringing call and call log (ROADMAP B §I.1). Before "call <name>" so "call back" is not a contact. */
    private fun callControl(t: String): ToolCall? = when {
        t.matches(re("(?:answer|pick up)(?: it)?|(?:answer|pick up|receive|accept|take)(?: (?:the|this|that|my))? (?:phone|call|phone call|whats ?app call|messenger call)|pick (?:it|the call|the phone) up")) -> call("answer_call")
        t.matches(re("(?:reject|decline|hang up)(?: it)?|(?:reject|decline|cut|end|hang up|drop|disconnect)(?: (?:the|this|that|my))? (?:phone|call|phone call|whats ?app call|messenger call)|hang (?:it )?up")) -> call("reject_call")
        t.matches(re("(?:any |read |check |show )?(?:my )?missed calls?|who (?:called|rang|was calling)(?: me)?(?: today)?|did (?:anyone|anybody|someone) call(?: me)?")) -> call("missed_calls")
        t.matches(re("call (?:them |him |her )?back|call back(?: the last (?:caller|number|missed call))?|return (?:the |that )?(?:last )?call")) -> call("call_back")
        t.matches(re("redial|call (?:the )?last number(?: again)?|call again")) -> call("redial")
        else -> null
    }

    private fun commsApp(app: String) = app.replace(" ", "")

    private val LEAD_OPEN = Regex("^(?:open|launch|go to|start) (?:the )?(whats ?app|messenger)(?: app)?(?:\\s*,\\s*|\\s+)(?:(?:and then|and|then) )?(.+)$")
    private val LEAD_IN = Regex("^(?:in|on|using|via|through) (?:the )?(whats ?app|messenger|sms)(?: app)?\\s*,?\\s+(.+)$")
    private val TRAIL_APP = Regex("\\s+(?:in|on|using|via|through) (?:the )?(whats ?app|messenger|sms)(?: app)?$")
    private val NOT_A_CONTACT = setOf("you", "he", "she", "they", "i", "we", "it", "me", "us", "him", "her", "them")
    private val COUNT_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /**
     * Messaging in any word order and reading one person's chat (ROADMAP §H.1): the app may come first
     * ("open messenger and …", "in whatsapp …") or last ("… on messenger"). Sending needs the app named.
     */
    private fun anyOrderChat(raw: String): ToolCall? {
        var s = raw.trimEnd('.', '!', '?').trim()
        var app: String? = null
        (LEAD_OPEN.find(s) ?: LEAD_IN.find(s))?.let { app = commsApp(it.groupValues[1]); s = it.groupValues[2] }
        TRAIL_APP.find(s)?.let {
            val tail = commsApp(it.groupValues[1])
            if (app != null && app != tail) return null
            app = tail
            s = s.substring(0, it.range.first)
        }
        readChat(s, app)?.let { return it }
        val sendApp = app ?: return null
        Regex("^(?:send|write)(?: an?)? (?:message|text|msg|sms)(?: (.+))?$").find(s)?.let { return message(it.groupValues[1], sendApp) }
        Regex("^(?:say|send|write) (.+) to (.+)$").find(s)?.let {
            if (it.groupValues[2].trim() in NOT_A_CONTACT) return null
            return call(if (sendApp == "whatsapp") "send_whatsapp" else "send_sms", "contact" to it.groupValues[2],
                "text" to it.groupValues[1], "app" to sendApp.takeIf { a -> a == "messenger" })
        }
        Regex("^tell (.+)$").find(s)?.takeUnless { it.groupValues[1].substringBefore(' ') in NOT_A_CONTACT }
            ?.let { return message(it.groupValues[1], sendApp) }
        Regex("^(?:message|text|msg) (.+)$").find(s)?.let { return message(it.groupValues[1], sendApp) }
        return null
    }

    private fun readChat(s: String, app: String?): ToolCall? {
        val last = "(?:the |my )?(?:last|latest|newest|recent|new)(?: (\\w+))? (messages?|texts?|sms|chats?)"
        val (who, n, noun) = Regex("^(?:read|show|tell)(?: me)? $last (?:of|from|by) (.+)$").find(s)
            ?.let { Triple(it.groupValues[3], it.groupValues[1], it.groupValues[2]) }
            ?: Regex("^(?:read|show|tell)(?: me)? (.+?)(?:'s|') (?:last|latest|newest|recent|new)(?: (\\w+))? (messages?|texts?|chats?)$").find(s)
                ?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            ?: Regex("^what did (.+?) (?:say|send|write|text|message)(?: me)?$").find(s)?.let { Triple(it.groupValues[1], "", "message") }
            ?: Regex("^(?:read|show|check)(?: me)? (?:my |the )?(?:chat|chats|messages|texts|conversation) (?:with|from) (.+)$").find(s)
                ?.let { Triple(it.groupValues[1], "", "messages") }
            ?: return null
        val contact = who.trim()
        if (contact.isEmpty() || contact in NOT_A_CONTACT) return null
        val count = n.toIntOrNull() ?: COUNT_WORDS.indexOf(n).takeIf { it >= 0 }?.plus(1)
            ?: if (n.isNotEmpty()) return null else if (noun.endsWith("s") && noun != "sms") 3 else 1
        return call("read_chat", "contact" to contact, "app" to app, "count" to count.toString())
    }
    private val SPOKEN_LETTER = Regex("[a-z]|ji|jee|gee|bee|tee|dee|pee|ef|eff|kay|jay|em|en|es|vee|zed|zee")

    /**
     * [addressed]: the words came after "to", so with no "saying …" the whole rest is the contact.
     * Missing contact or text is returned empty; the Assistant then asks for it (MessageDraft).
     */
    private fun message(raw: String, requestedApp: String? = null, addressed: Boolean = false): ToolCall? {
        var rest = raw
        var to = addressed
        // "my whatsapp" / "to my messenger" → app only, ask who.
        Regex("^(?:to )?my (whats ?app|messenger)$").find(rest)?.let {
            val app = commsApp(it.groupValues[1])
            if (requestedApp != null && requestedApp != app) return null
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "app" to app.takeIf { a -> a == "messenger" })
        }
        if (rest.startsWith("to ")) { rest = rest.removePrefix("to "); to = true }
        val delimiter = Regex("\\s+(?:saying|that says|say|that)(?:\\s+|$)|\\s*:\\s*").find(rest)
        var app = requestedApp
        val recipient: String
        val text: String
        if (delimiter != null) {
            var head = rest.substring(0, delimiter.range.first).trim()
            text = rest.substring(delimiter.range.last + 1).trim()
            Regex("^(.+?) (?:on|via|using) (whats ?app|messenger|sms)$").find(head)?.let {
                if (app != null && app != commsApp(it.groupValues[2])) return null
                app = commsApp(it.groupValues[2])
                head = it.groupValues[1]
            }
            recipient = head
        } else {
            val routed = if (requestedApp == null) Regex("^(.+?) (?:on|via|using) (whats ?app|messenger|sms)(?: (.+))?$").find(rest) else null
            if (routed != null) {
                if (app != null && app != commsApp(routed.groupValues[2])) return null
                app = commsApp(routed.groupValues[2])
                recipient = routed.groupValues[1]
                text = routed.groupValues[3]
            } else {
                val split = rest.indexOf(' ')
                val tail = if (split > 0) rest.substring(split + 1) else ""
                // "to madar ji": a trailing spoken letter is part of the name (contacts saved as "Name G").
                if (split < 1 || (to && SPOKEN_LETTER.matches(tail))) {
                    recipient = rest
                    text = ""
                } else {
                    recipient = rest.substring(0, split)
                    text = tail
                }
            }
        }
        if (recipient.isBlank() || text in setOf("saying", "that says", "say", "that")) return null
        return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "contact" to recipient,
            "text" to text.ifBlank { null }, "app" to app?.takeIf { it == "messenger" })
    }

    private fun media(t: String): ToolCall? {
        when {
            t.matches(re("(?:pause|stop)(?: the)?(?: music| song| video| playback| media)?|pause it")) -> return call("media_control", "action" to "pause")
            t.matches(re("(?:resume|continue|unpause|play)(?: the)?(?: music| song| video| playback)?|play it")) -> return call("media_control", "action" to "play")
            t.matches(re("(?:next|skip)(?: the)?(?: song| track| video)?|play (?:the )?next(?: song| track)?|skip (?:this|it)")) -> return call("media_control", "action" to "next")
            t.matches(re("(?:previous|last|go back)(?: song| track)|play (?:the )?previous(?: song| track)?|previous")) -> return call("media_control", "action" to "previous")
        }
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

    private fun mediaApp(app: String) = app.replace("you tube", "youtube").replace(' ', '_')

    private fun noisyTool(t: String, now: LocalDateTime): ToolCall? {
        if (Regex("^(?:search|google|find|look up|wiki|wikipedia|tell me about|explain|define|why|how|what does|what do|what are|i |im |remember|forget|say|repeat)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(?:news|headlines|online|internet|technology|buy|price|prices)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(?:dont|do not|never|not|cancel|stop|delete|remove|dismiss|check|list|show)\\b").containsMatchIn(t)) {
            return if (Regex("\\bbattery\\b").containsMatchIn(t) && !Regex("\\b(?:saver|saving|dont|do not|never|not)\\b").containsMatchIn(t)) {
                call("device_status", "what" to "battery")
            } else null
        }
        if (Regex("\\bbattery\\b").containsMatchIn(t) && !Regex("\\b(?:saver|saving)\\b").containsMatchIn(t)) {
            return call("device_status", "what" to "battery")
        }
        if (Regex("\\b(?:timer|countdown)\\b").containsMatchIn(t)) {
            TimeParser.duration(t)?.let { return call("set_timer", "seconds" to it.toString()) }
        }
        Regex("\\balarm\\b(?: for| at| to)? (.+)$").find(t)?.let {
            if (TimeParser.parse("at ${it.groupValues[1]}", now)?.hasTime == true) {
                return time("alarm ${it.groupValues[1]}", now)
            }
        }
        Regex("\\b(?:volume|sound)(?: to| at)? (\\d+)(?: percent)?\\b").find(t)?.let {
            return call("set_volume", "level" to it.groupValues[1])
        }
        Regex("\\b(?:volume|sound) (up|down|off|max|maximum|full)\\b").find(t)?.let {
            return call("set_volume", "level" to when (val level = it.groupValues[1]) {
                "off" -> "0"
                "max", "maximum", "full" -> "100"
                else -> level
            })
        }
        for ((target, names) in TOGGLE_TARGETS) {
            val directions = Regex("\\b(?:${names.joinToString("|")}) (on|off)\\b").findAll(t)
                .map { it.groupValues[1] }.toSet()
            if (directions.size == 1 && !Regex("\\b(?:on|off)\\b").findAll(t).any { it.value !in directions }) {
                return call("toggle", "target" to target, "state" to directions.single())
            }
        }
        return null
    }

    /** Any setting (D-056): "open nfc settings", "turn on dark mode", "disable battery saver mode". */
    private fun setting(t: String): ToolCall? {
        Regex("^(?:open |show |go to |take me to )(?:the |my )?(.+?) settings?$").find(t)?.let {
            return call("setting", "name" to it.groupValues[1], "state" to "open")
        }
        val m = Regex("^(turn on|switch on|enable|turn off|switch off|disable) (?:the |my )?(.+)$").find(t) ?: return null
        val name = m.groupValues[2]
        if (!Regex("\\b(?:mode|saver|sync|nfc|rotation|vibration|vibrate|haptics?|feedback|night light|eye comfort|dark theme|always on display|settings?)$").containsMatchIn(name)) return null
        val on = m.groupValues[1].let { it == "turn on" || it == "switch on" || it == "enable" }
        return call("setting", "name" to name.replace(Regex(" settings?$"), ""), "state" to if (on) "on" else "off")
    }

    private fun system(t: String): ToolCall? {
        setting(t)?.let { return it }
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
            t.matches(re("(?:whats |what is |check |how much )?(?:the |my )?(?:battery|charge)(?: level| percentage| status| left| is left)?|how much battery(?: is left| do i have)?(?: left)?|battery|how(?:s| is) (?:the |my )?(?:battery|charge)(?: doing| level| health| now)?")) -> return call("device_status", "what" to "battery")
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
        WeatherRequest.match(t)?.let { return it }
        if (t.matches(re("(?:re ?)?scan (?:my|this|the) (?:phone|device)(?: again)?"))) return call("phone_profile", "scan" to "true")
        if (t.matches(re("what do you know about (?:my|this) (?:phone|device)|(?:tell me )?about (?:my|this) phone|what apps do i have|which apps do i have"))) return call("phone_profile")
        if (t.matches(re("how many apps(?: do i have| are (?:there|installed)(?: on (?:my|this) phone)?| (?:are )?on (?:my|this) phone| have i (?:got|installed))?|(?:number|count) of (?:my )?apps|count (?:my )?apps"))) return call("phone_profile", "ask" to "apps")
        if (t.matches(re("(?:so )?what (?:all |else )?(?:(?:can|could) you )?do(?: for me)?|what (?:all |else )?help(?: me)? with|what do you do|what are (?:your|you) (?:features|abilities|skills|powers)|(?:your|list (?:your|of)) (?:features|abilities|skills)|what (?:can|could) you help (?:me )?with|how can you help(?: me)?|help(?: me)?"))) return call("phone_profile", "ask" to "abilities")
        if (t.matches(re("where am i(?: right)?(?: now)?|(?:whats|what is|tell me|show me) my (?:current )?location|my (?:current )?location|where are we(?: now)?"))) return call("my_location")
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
        if (t.matches(re("what do you (?:remember|know)(?: about me)?|what (?:have|did) you (?:remembered|remember|learned)(?: about me)?|(?:list|show|tell me)(?: my| your)? (?:facts|memories)"))) return call("recall_facts")
        Regex("^(?:remember|dont forget|keep in mind|note that i)(?: that)? (.+)$").find(t)?.let {
            if (it.groupValues[1].startsWith("to ")) return null   // "remember to buy milk" = reminder, handled by LLM/chat
            return call("remember_fact", "text" to it.groupValues[1])
        }
        Regex("^forget(?: that| about)? (.+)$").find(t)?.let { return call("forget_fact", "text" to it.groupValues[1]) }
        return null
    }

    /** App map (ROADMAP §J.4): "open privacy settings in whatsapp", "what can you do in youtube". */
    private fun appMap(t: String): ToolCall? {
        // Explorer (§J.5): "learn this app", "learn mi remote".
        if (t.matches(re("(?:learn|explore) (?:this|the|the current|current) app"))) return call("learn_app")
        Regex("^(?:learn|explore) (?:the )?(.+?)(?: app)?$").find(t)?.let {
            val app = it.groupValues[1]
            if (appNameMatches?.invoke(app) == true) return call("learn_app", "app" to app)
        }
        // polite() already dropped "can you": "what can you do in x" arrives as "what do in x"
        Regex("^what (?:(?:can|could) you )?do (?:in|on|with|inside) (?:the )?(.+?)(?: app)?$").find(t)?.let {
            val app = it.groupValues[1]
            return if (appNameMatches?.invoke(app) == true) call("app_abilities", "app" to app) else null
        }
        Regex("^(?:open|go to|show(?: me)?|take me to|navigate to) (?:the |my )?(.+?) (?:in|on|inside) (?:the )?(.+?)(?: app)?$").find(t)?.let {
            val app = it.groupValues[2]
            if (appNameMatches?.invoke(app) == true) return call("app_go", "app" to app, "target" to it.groupValues[1])
        }
        return null
    }

    /** General skills for any app (ROADMAP §H.4). Plain "tap X" stays on the old tap tool. */
    private fun skills(t: String): ToolCall? {
        Regex("^(?:search|find|look up)(?: for)? (.+?) (?:in|on|inside) (?:the )?(.+?)(?: app)?$").find(t)?.let {
            val app = it.groupValues[2]
            val web = app.matches(re("google|(?:the )?(?:web|internet)|online"))
            if (!web && appNameMatches?.invoke(app) == true) return call("search_in_app", "query" to it.groupValues[1], "app" to app)
        }
        if (t.matches(re("open (?:the )?(?:first|top) (?:result|one|item)"))) return call("open_result")
        Regex("^open (?:the )?(?:first |top )?result (?:for |about |called |named )?(.+)$").find(t)
            ?.let { return call("open_result", "query" to it.groupValues[1]) }
        Regex("^(?:go to|open|switch to|show(?: me)?) (?:the )?(.+?) tab$").find(t)?.let { return call("go_tab", "name" to it.groupValues[1]) }
        if (t.matches(re("read (?:the |this |me the )?(?:list|rows|items|results|search results)(?: on (?:the )?screen)?|what (?:is|are) in (?:the |this )?list"))) {
            return call("read_list")
        }
        Regex("^(?:turn|switch) (on|off) (?:the )?(.+?) (?:switch|toggle|option|checkbox)$").find(t)
            ?.let { return call("toggle_named", "name" to it.groupValues[2], "state" to it.groupValues[1]) }
        Regex("^(?:turn|switch) (?:the )?(.+?) (?:switch|toggle|option|checkbox) (on|off)$").find(t)
            ?.let { return call("toggle_named", "name" to it.groupValues[1], "state" to it.groupValues[2]) }
        Regex("^(?:flip|toggle) (?:the )?(.+?)(?: switch| toggle| option| checkbox)?$").find(t)
            ?.takeIf { t.matches(re(".+ (?:switch|toggle|option|checkbox)")) }
            ?.let { return call("toggle_named", "name" to it.groupValues[1]) }
        return null
    }

    private fun apps(t: String): ToolCall? {
        Regex("^(?:close|kill|exit|quit|force stop|stop) (?:the )?(.+?)(?: app| application)?$").find(t)
            ?.let { return call("close_app", "app" to it.groupValues[1]) }
        Regex("^(?:open|launch|start|run|show)(?: the| my)? (.+?)(?: app| application)?$").find(t)
            ?.let {
                val app = it.groupValues[1]
                if (!IntentRouter.isAppLaunchQuestion(t) && appNameMatches?.invoke(app) != false) {
                    return call("open_app", "app" to app)
                }
            }
        return null
    }
}
