package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RuleMatcherTest {
    // Thursday 22 Oct 2026, 10:00
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)

    private fun m(text: String) = RuleMatcher.match(text, now)
    private fun c(tool: String, vararg args: Pair<String, String>) = ToolCall(tool, args.toMap())

    @Test fun anySetting() {
        assertEquals(c("setting", "name" to "nfc", "state" to "open"), m("open nfc settings"))
        assertEquals(c("setting", "name" to "dark mode", "state" to "on"), m("turn on dark mode"))
        assertEquals(c("setting", "name" to "reading mode", "state" to "off"), m("disable reading mode"))
        assertEquals(c("setting", "name" to "haptic feedback", "state" to "on"), m("turn on haptic feedback"))
        // fixed toggles still win
        assertEquals(c("toggle", "target" to "airplane", "state" to "on"), m("turn on airplane mode"))
        assertEquals(null, m("turn on the tv")?.takeIf { it.tool == "setting" })
    }

    @Test fun flashlight() {
        assertEquals(c("toggle", "target" to "flashlight", "state" to "on"), m("turn on flashlight"))
        assertEquals(c("toggle", "target" to "flashlight", "state" to "on"), m("Turn on the flash light."))
        assertEquals(c("toggle", "target" to "flashlight", "state" to "off"), m("torch off"))
        assertEquals(c("toggle", "target" to "flashlight", "state" to "on"), m("can you please switch on the torch"))
    }

    /** Real speech-to-text mistakes from the phone (files/commands, 2026-10-22). */
    @Test fun misheardCommands() {
        assertEquals(c("toggle", "target" to "flashlight", "state" to "on"), m("down on flushlight"))
        assertEquals(c("toggle", "target" to "flashlight", "state" to "on"), m("new down on flashlight"))
        assertEquals(c("toggle", "target" to "flashlight", "state" to "on"), m("do you mean on flashlight"))
        assertEquals(c("toggle", "target" to "flashlight", "state" to "off"), m("turn of the flashlight"))
        assertEquals(c("set_volume", "level" to "up"), m("sound up"))
        assertEquals(c("set_volume", "level" to "down"), m("sound down"))
        assertEquals(c("set_volume", "level" to "0"), m("sound of"))
    }

    @Test fun toggles() {
        assertEquals(c("toggle", "target" to "wifi", "state" to "off"), m("turn off wi-fi"))
        assertEquals(c("toggle", "target" to "bluetooth", "state" to "on"), m("enable bluetooth"))
        assertEquals(c("toggle", "target" to "data", "state" to "on"), m("turn on mobile data"))
        assertEquals(c("toggle", "target" to "dnd", "state" to "on"), m("turn on do not disturb"))
        assertEquals(c("toggle", "target" to "airplane", "state" to "off"), m("turn off airplane mode"))
        assertEquals(c("toggle", "target" to "location", "state" to "on"), m("turn on location"))
        assertEquals(c("toggle", "target" to "hotspot", "state" to "on"), m("turn on hotspot"))
        assertEquals(c("toggle", "target" to "battery_saver", "state" to "on"), m("turn on battery saver"))
    }

    @Test fun apps() {
        assertEquals(c("open_app", "app" to "youtube"), m("open youtube"))
        assertEquals(c("open_app", "app" to "camera"), m("Open the camera app"))
        assertEquals(c("open_app", "app" to "whatsapp"), m("launch whatsapp"))
        assertEquals(c("close_app", "app" to "youtube"), m("close youtube"))
        assertEquals(c("open_url", "url" to "google.com"), m("open google dot com"))
    }

    @Test fun alarmsAndTimers() {
        assertEquals(c("set_alarm", "time" to "07:00", "date" to "2026-10-23"), m("set an alarm for 7 am"))
        assertEquals(c("set_alarm", "time" to "19:30"), m("set alarm for seven thirty pm"))
        assertEquals(c("set_alarm", "time" to "06:15", "date" to "2026-10-23"), m("wake me up at 6 15 tomorrow"))
        assertEquals(c("set_timer", "seconds" to "300"), m("set a timer for five minutes"))
        assertEquals(c("set_timer", "seconds" to "5400"), m("timer for 1 hour and 30 minutes"))
        assertEquals(c("set_timer", "seconds" to "600"), m("start a 10 minute timer"))
        assertEquals(c("cancel_alarm"), m("cancel my alarms"))
    }

    @Test fun notesAndReminders() {
        assertEquals(c("create_note", "text" to "buy milk"), m("take a note buy milk"))
        assertEquals(c("create_note", "text" to "the wifi password is 1234"), m("note that the wifi password is 1234"))
        assertEquals(c("read_notes"), m("read my notes"))
        assertEquals(
            c("create_reminder", "text" to "call mom", "time" to "2026-10-22T17:00"),
            m("remind me to call mom at 5 pm"),
        )
        assertEquals(
            c("create_reminder", "text" to "drink water", "time" to "2026-10-22T10:20"),
            m("remind me in 20 minutes to drink water"),
        )
        assertEquals(
            c("create_reminder", "text" to "go to the gym", "time" to "2026-10-23T18:00", "repeat" to "weekly"),
            m("remind me every friday at 6 pm to go to the gym"),
        )
        assertEquals(c("list_reminders"), m("what are my reminders"))
        assertEquals(c("delete_reminder", "text" to "all"), m("delete all reminders"))
    }

    @Test fun comms() {
        assertEquals(c("call", "contact" to "mom"), m("call mom"))
        assertEquals(c("send_sms", "contact" to "rahim", "text" to "i am coming"), m("send a message to Rahim saying I am coming"))
        assertEquals(c("send_whatsapp", "contact" to "rahim", "text" to "hello"), m("send whatsapp message to rahim saying hello"))
        assertEquals(c("read_messages"), m("read my messages"))
    }

    @Test fun media() {
        assertEquals(c("play_media", "query" to "lofi music", "app" to "youtube"), m("play lofi music on youtube"))
        assertEquals(c("media_control", "action" to "pause"), m("pause the music"))
        assertEquals(c("media_control", "action" to "next"), m("next song"))
        assertEquals(c("set_volume", "level" to "50"), m("set volume to 50%"))
        assertEquals(c("set_volume", "level" to "up"), m("volume up"))
        assertEquals(c("set_volume", "level" to "0"), m("mute"))
    }

    @Test fun system() {
        assertEquals(c("device_status", "what" to "time"), m("what time is it"))
        assertEquals(c("device_status", "what" to "battery"), m("what's the battery level"))
        assertEquals(c("set_brightness", "level" to "80"), m("set brightness to eighty percent"))
        assertEquals(c("take_screenshot"), m("take a screenshot"))
        assertEquals(c("lock_phone"), m("lock the phone"))
        assertEquals(c("run_routine", "name" to "good_night"), m("good night"))
    }

    @Test fun screenAndTalk() {
        assertEquals(c("read_screen"), m("read the screen"))
        assertEquals(c("go_back"), m("go back"))
        assertEquals(c("go_home"), m("go home"))
        assertEquals(c("scroll", "direction" to "down"), m("scroll down"))
        assertEquals(c("tap", "text" to "send"), m("tap on send button"))
        assertEquals(c("cancel"), m("never mind"))
        assertEquals(c("repeat_last"), m("say that again"))
        assertEquals(c("panic"), m("code red"))
        assertEquals(c("panic"), m("Panic!"))
        assertEquals(c("cancel"), m("stop"))
    }

    @Test fun notificationsKnowledgeMemory() {
        assertEquals(c("read_notifications"), m("read my notifications"))
        assertEquals(c("clear_notifications"), m("clear all notifications"))
        assertEquals(c("notification_summary"), m("anything important"))
        assertEquals(c("daily_summary"), m("what did I miss"))
        assertEquals(c("daily_summary"), m("did I miss anything?"))
        assertEquals(c("daily_summary"), m("catch me up"))
        assertEquals(c("daily_summary"), m("give me my daily summary"))
        assertEquals(c("weather"), m("what's the weather"))
        assertEquals(c("weather", "place" to "dhaka"), m("what's the weather in Dhaka"))
        assertEquals(c("news"), m("news"))
        assertEquals(c("web_search", "query" to "best flutter state management"), m("search for best flutter state management"))
        assertEquals(c("wiki_lookup", "topic" to "albert einstein"), m("who is albert einstein"))
        assertEquals(c("remember_fact", "text" to "my car is blue"), m("remember that my car is blue"))
    }

    @Test fun unknown() {
        assertNull(m("why is the sky blue"))
        assertNull(m(""))
    }

    @Test fun rememberKeepsWholeSentence() {
        assertEquals(c("remember_fact", "text" to "jimi means my assistant"), m("remember that jimi means my assistant."))
        assertEquals(c("remember_fact", "text" to "i just moved to dhaka"), m("Remember I just moved to Dhaka"))
    }
}
