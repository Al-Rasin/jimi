package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.Tool

// Regexes built once, not on every call (ROADMAP §L.4).
private val WORDS_RE = Regex("[^a-z0-9]+")

/**
 * Picks the few tools a command is likely about, so the small local tool model reads ~8 tool descriptions
 * instead of all of them (all of them overflowed its 4096-token context and took ~55 s on the phone).
 * Order follows the registry, so the same shortlist gives the same prompt and reuses the KV cache.
 * Pure Kotlin: unit tested.
 */
object ToolShortlist {
    const val SIZE = 8

    /** Spoken words → tools they usually mean, for words that are not in the tool descriptions. */
    private val HINTS: Map<String, List<String>> = mapOf(
        "text" to listOf("send_sms", "send_whatsapp"), "message" to listOf("send_sms", "send_whatsapp", "read_chat"),
        "messages" to listOf("read_chat", "read_messages"), "chat" to listOf("read_chat"), "said" to listOf("read_chat"),
        "msg" to listOf("send_sms", "send_whatsapp"), "sms" to listOf("send_sms"), "whatsapp" to listOf("send_whatsapp", "call"),
        "messenger" to listOf("send_sms", "call"), "tell" to listOf("send_sms", "send_whatsapp"), "ping" to listOf("send_sms", "send_whatsapp"),
        "calendar" to listOf("calendar_read", "calendar_add", "calendar_move", "calendar_delete"),
        "meeting" to listOf("calendar_add", "calendar_read", "calendar_move", "calendar_delete"),
        "appointment" to listOf("calendar_add", "calendar_read", "calendar_move", "calendar_delete"),
        "event" to listOf("calendar_add", "calendar_read", "calendar_delete"), "schedule" to listOf("calendar_read", "calendar_add"),
        "reschedule" to listOf("calendar_move"),
        "ring" to listOf("call"), "dial" to listOf("call"), "phone" to listOf("call"), "video" to listOf("call"),
        "wake" to listOf("set_alarm"), "morning" to listOf("set_alarm"), "alarm" to listOf("set_alarm", "cancel_alarm"),
        "remind" to listOf("create_reminder", "list_reminders"), "reminder" to listOf("create_reminder", "list_reminders", "delete_reminder"),
        "minutes" to listOf("set_timer", "create_reminder"), "countdown" to listOf("set_timer"),
        "bright" to listOf("set_brightness"), "brightness" to listOf("set_brightness"), "dark" to listOf("set_brightness"),
        "darker" to listOf("set_brightness"), "dim" to listOf("set_brightness"), "eyes" to listOf("set_brightness"),
        "loud" to listOf("set_volume"), "louder" to listOf("set_volume"), "quiet" to listOf("set_volume", "toggle"),
        "quieter" to listOf("set_volume"), "sound" to listOf("set_volume"), "mute" to listOf("set_volume", "toggle"),
        "volume" to listOf("set_volume"), "hear" to listOf("set_volume"),
        "song" to listOf("play_media", "media_control"), "music" to listOf("play_media", "media_control"),
        "play" to listOf("play_media", "media_control"), "listen" to listOf("play_media"), "watch" to listOf("play_media"),
        "youtube" to listOf("play_media", "open_app"), "spotify" to listOf("play_media", "open_app"),
        "pause" to listOf("media_control"), "skip" to listOf("media_control"), "stop" to listOf("media_control", "cancel_alarm"),
        "light" to listOf("toggle"), "torch" to listOf("toggle"), "flashlight" to listOf("toggle"), "wifi" to listOf("toggle"),
        "bluetooth" to listOf("toggle"), "internet" to listOf("toggle"), "data" to listOf("toggle"), "hotspot" to listOf("toggle"),
        "silent" to listOf("toggle", "set_volume"), "disturb" to listOf("toggle"), "airplane" to listOf("toggle"),
        "flight" to listOf("toggle"), "gps" to listOf("toggle"), "rotate" to listOf("toggle"), "debugging" to listOf("toggle"),
        "time" to listOf("device_status"), "date" to listOf("device_status"), "day" to listOf("device_status"),
        "battery" to listOf("device_status"), "charge" to listOf("device_status"), "storage" to listOf("device_status"),
        "space" to listOf("device_status"),
        "rain" to listOf("weather"), "umbrella" to listOf("weather"), "hot" to listOf("weather"), "cold" to listOf("weather"),
        "temperature" to listOf("weather"), "outside" to listOf("weather"),
        "where" to listOf("my_location"), "location" to listOf("my_location", "toggle"),
        "note" to listOf("create_note", "read_notes"), "notes" to listOf("read_notes", "create_note"),
        "write" to listOf("create_note", "type_text", "compose_email"), "jot" to listOf("create_note"),
        "email" to listOf("compose_email", "add_contact_email"), "mail" to listOf("compose_email"),
        "notification" to listOf("read_notifications", "notification_summary", "clear_notifications"),
        "notifications" to listOf("read_notifications", "notification_summary", "clear_notifications"),
        "missed" to listOf("daily_summary", "missed_calls", "read_messages"),
        "summary" to listOf("daily_summary", "notification_summary"),
        "copy" to listOf("copy_text"), "clipboard" to listOf("read_clipboard", "copy_text"), "copied" to listOf("read_clipboard"),
        "contact" to listOf("save_contact", "share_contact", "add_contact_email"), "contacts" to listOf("save_contact"),
        "open" to listOf("open_app", "open_url", "open_result"), "launch" to listOf("open_app"), "start" to listOf("open_app"),
        "app" to listOf("open_app", "close_app"), "close" to listOf("close_app"), "kill" to listOf("close_app"),
        "website" to listOf("open_url"), "site" to listOf("open_url"), "search" to listOf("web_search", "search_in_app"), "find" to listOf("find_in_app", "search_in_app"), "google" to listOf("web_search"),
        "who" to listOf("wiki_lookup", "web_search"), "what" to listOf("wiki_lookup", "web_search"), "news" to listOf("news"), "dollar" to listOf("currency"), "taka" to listOf("currency"), "currency" to listOf("currency"), "exchange" to listOf("currency"),
        "prayer" to listOf("prayer_times"), "namaz" to listOf("prayer_times"), "azan" to listOf("prayer_times"), "maghrib" to listOf("prayer_times"), "fajr" to listOf("prayer_times"),
        "headlines" to listOf("news"), "screenshot" to listOf("take_screenshot"), "capture" to listOf("take_screenshot"),
        "screen" to listOf("read_screen", "take_screenshot", "set_brightness"), "lock" to listOf("lock_phone"),
        "unlock" to listOf("unlock_phone"), "home" to listOf("go_home"), "back" to listOf("go_back"), "recent" to listOf("open_recents"),
        "remember" to listOf("remember_fact", "recall_facts"), "forget" to listOf("forget_fact"),
        "know" to listOf("recall_facts"), "scroll" to listOf("scroll"), "tap" to listOf("tap", "tap_named"), "click" to listOf("tap", "tap_named"),
        "press" to listOf("tap", "tap_named"), "type" to listOf("type_text"),
        "result" to listOf("open_result"), "results" to listOf("open_result", "read_list"), "tab" to listOf("go_tab"),
        "list" to listOf("read_list"), "switch" to listOf("toggle_named"), "toggle" to listOf("toggle_named"),
        "learn" to listOf("learn_app"), "explore" to listOf("learn_app"),
        "option" to listOf("toggle_named"), "recipe" to listOf("run_recipe"),
        "answer" to listOf("answer_call"), "reject" to listOf("reject_call"), "decline" to listOf("reject_call"),
        "hang" to listOf("reject_call"), "redial" to listOf("redial"), "caller" to listOf("call_back", "missed_calls"),
    )

    private val STOP = setOf(
        "a", "an", "the", "to", "for", "of", "on", "in", "at", "and", "or", "is", "it", "me", "my", "i", "you", "your",
        "please", "can", "could", "would", "will", "do", "does", "be", "with", "that", "this", "some", "up", "down",
        "now", "jimi", "jarvis", "hey", "boss", "set", "make", "get", "turn", "want", "need", "let", "s", "im",
    )

    /** Distinct words (4+ letters) of app names, e.g. "Mi Remote" → "remote". */
    fun appWords(labels: List<String>): Set<String> = labels.flatMap { words(it) }.filter { it.length >= 4 }.toSet()

    fun words(text: String): List<String> =
        text.lowercase().split(WORDS_RE).filter { it.isNotEmpty() && it !in STOP }

    /** Up to [size] tools, in registry order. Empty when nothing in the command points at any tool. */
    /** [appWords]: words from installed app names (phone profile); saying one hints "open_app". */
    fun pick(text: String, tools: List<Tool>, size: Int = SIZE, appWords: Set<String> = emptySet()): List<Tool> {
        val said = words(text)
        if (said.isEmpty()) return emptyList()
        val score = HashMap<String, Int>()
        for (t in tools) {
            val bag = (words(t.name.replace('_', ' ')) + words(t.description) +
                t.args.flatMap { a -> words(a.description) + a.choices.orEmpty().flatMap(::words) }).toSet()
            val hits = said.count { w -> w in bag || (w.length > 4 && bag.any { it.startsWith(w.dropLast(1)) }) }
            if (hits > 0) score[t.name] = hits
        }
        said.forEach { w -> (HINTS[w] ?: HINTS[w.removeSuffix("s")])?.forEach { score[it] = (score[it] ?: 0) + 2 } }
        if (said.any { it in appWords }) score["open_app"] = (score["open_app"] ?: 0) + 2
        val keep = score.entries.sortedByDescending { it.value }.take(size).map { it.key }.toSet()
        return tools.filter { it.name in keep }
    }
}
