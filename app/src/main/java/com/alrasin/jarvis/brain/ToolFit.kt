package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/**
 * A tool picked by the small local model must have its own words in the sentence (ROADMAP §M.2):
 * "i love you" is not read_messages, "fun fact about the moon" is not read_notes. Tools not listed here
 * have no word check. Pure Kotlin: unit tested.
 */
object ToolFit {
    private fun w(p: String) = Regex("\\b(?:$p)\\b")

    private val clockTime = Regex("\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a m|p m)\\b|\\b\\d{1,2}:\\d{2}\\b|\\bo ?clock\\b")
    private val messageWords = "messages?|text|texts|sms|msg|send|tell|ping|write|whats ?app|messenger"

    private val NEEDS: Map<String, Regex> = mapOf(
        "read_messages" to w("messages?|sms|texts?|inbox"),
        "read_chat" to w("messages?|chats?|said|says|texted|wrote|whats ?app|messenger|inbox"),
        "read_notes" to w("notes?|notebook|wrote down|jotted"),
        "create_note" to w("notes?|jot|write down|save this"),
        "set_alarm" to w("alarms?|wake|morning"),
        "cancel_alarm" to w("alarms?|timers?"),
        "set_timer" to w("timers?|countdown|minutes?|seconds?|hours?"),
        "media_control" to w("music|songs?|tracks?|play|playing|pause|resume|skip|next|previous|video|audio"),
        "play_media" to w("play|listen|watch|music|songs?|video|youtube|spotify"),
        "device_status" to w("battery|charge|charging|time|date|day|today|storage|space|ram"),
        "weather" to w("weather|rain|raining|umbrella|hot|cold|temperature|forecast|outside|sunny|wind|windy|humid|humidity"),
        "close_app" to w("close|kill|exit|quit|stop"),
        "open_app" to w("open|launch|start|run|go to|show"),
        "go_home" to w("home"),
        "send_sms" to w(messageWords),
        "send_whatsapp" to w(messageWords),
        "call" to w("call|dial|ring|phone"),
    )

    fun fits(text: String, call: ToolCall): Boolean {
        val need = NEEDS[call.tool] ?: return true
        val t = text.lowercase().replace(Regex("[^a-z0-9: ]"), " ")
        if (need.containsMatchIn(t)) return true
        return call.tool == "set_alarm" && clockTime.containsMatchIn(t)
    }
}
