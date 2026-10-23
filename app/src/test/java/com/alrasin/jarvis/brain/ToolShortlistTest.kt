package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ArgSpec
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolShortlistTest {
    private fun tool(name: String, description: String, vararg args: ArgSpec) = object : Tool {
        override val name = name
        override val description = description
        override val args = args.toList()
        override val risk = Risk.SAFE
        override suspend fun run(call: ToolCall, ctx: ToolContext) = ToolResult.ok("")
    }

    private val tools = listOf(
        tool("set_alarm", "Set an alarm", ArgSpec("time", "alarm time")),
        tool("set_timer", "Start a countdown timer", ArgSpec("duration", "how long")),
        tool("toggle", "Turn a phone feature on or off", ArgSpec("target", "feature", choices = listOf("flashlight", "wifi", "bluetooth"))),
        tool("set_brightness", "Screen brightness", ArgSpec("level", "0-100, up, down or auto")),
        tool("set_volume", "Set media volume", ArgSpec("level", "0-100, up, down")),
        tool("send_sms", "Send an SMS, or send a Messenger message with app=messenger", ArgSpec("contact", "name"), ArgSpec("text", "message")),
        tool("send_whatsapp", "Open the contact's WhatsApp conversation and send the exact message", ArgSpec("contact", "name"), ArgSpec("text", "message")),
        tool("call", "Call a contact by phone, WhatsApp or Messenger", ArgSpec("contact", "name")),
        tool("play_media", "Search YouTube, YouTube Music or Spotify and play", ArgSpec("query", "what to play")),
        tool("weather", "Weather now or tomorrow", ArgSpec("place", "city", required = false)),
        tool("open_app", "Open an app by name", ArgSpec("app", "app name")),
        tool("device_status", "Time, date, battery, storage or all"),
        tool("create_note", "Save a note", ArgSpec("text", "note")),
        tool("lock_phone", "Lock the phone (screen off)"),
    )

    private fun names(text: String) = ToolShortlist.pick(text, tools).map { it.name }

    @Test fun commandsKeepTheRightTool() {
        assertTrue(names("my eyes hurt the screen is too bright").contains("set_brightness"))
        assertTrue(names("ping mother g on whatsapp i'm coming").contains("send_whatsapp"))
        assertTrue(names("wake me at six thirty").contains("set_alarm"))
        assertTrue(names("i can't hear anything").contains("set_volume"))
        assertTrue(names("put on some arijit songs").contains("play_media"))
        assertTrue(names("will it rain later").contains("weather"))
        assertTrue(names("how much charge is left").contains("device_status"))
        assertTrue(names("switch the torch on").contains("toggle"))
    }

    @Test fun shortlistIsSmallAndInRegistryOrder() {
        val picked = ToolShortlist.pick("text rahim on whatsapp and call him and play music and set alarm", tools, size = 4)
        assertTrue(picked.size <= 4)
        assertEquals(picked.map { tools.indexOf(it) }.sorted(), picked.map { tools.indexOf(it) })
    }

    @Test fun nothingLikelyGivesEmpty() {
        assertEquals(emptyList<String>(), names("hmm okay"))
        assertEquals(emptyList<String>(), names(""))
    }
}
