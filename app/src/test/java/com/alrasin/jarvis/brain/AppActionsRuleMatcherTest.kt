package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppActionsRuleMatcherTest(private val input: String, private val expected: ToolCall?) {
    @Test fun parsesAppAction() = assertEquals(input, expected, RuleMatcher.match(input))

    companion object {
        private fun call(contact: String, app: String? = null, video: String? = null) = ToolCall("call",
            mapOf("contact" to contact) + listOfNotNull(app?.let { "app" to it }, video?.let { "video" to it }))

        private fun message(contact: String, text: String, app: String? = null) = ToolCall(
            if (app == "whatsapp") "send_whatsapp" else "send_sms",
            mapOf("contact" to contact, "text" to text) + if (app == "messenger") mapOf("app" to app) else emptyMap(),
        )

        private fun media(query: String, app: String? = null) = ToolCall("play_media",
            mapOf("query" to query) + if (app == null) emptyMap() else mapOf("app" to app))

        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}")
        fun commands(): List<Array<Any?>> = listOf(
            "call mom" to call("mom"),
            "call mother" to call("mother"),
            "call ammu" to call("ammu"),
            "call amma" to call("amma"),
            "call maa" to call("maa"),
            "call mum" to call("mum"),
            "call Mom Robi" to call("mom robi"),
            "call my mother" to call("my mother"),
            "call মাকে" to call("মাকে"),
            "call +8801712345678" to call("+8801712345678"),
            "call mom on WhatsApp" to call("mom", "whatsapp"),
            "Call Mom on WhatsApp." to call("mom", "whatsapp"),
            "call mom on whats app" to call("mom", "whatsapp"),
            "call mom via whatsapp" to call("mom", "whatsapp"),
            "ring Mom Robi using WhatsApp" to call("mom robi", "whatsapp"),
            "WhatsApp call mom" to call("mom", "whatsapp"),
            "WhatsApp video call mom" to call("mom", "whatsapp", "true"),
            "whats app voice call mom" to call("mom", "whatsapp", "false"),
            "video call mom on whatsapp" to call("mom", "whatsapp", "true"),
            "audio call mom via whatsapp" to call("mom", "whatsapp", "false"),
            "call mom on Messenger" to call("mom", "messenger"),
            "Messenger video call mom" to call("mom", "messenger", "true"),
            "video call Rahim Khan on messenger" to call("rahim khan", "messenger", "true"),
            "call mom on phone" to call("mom", "phone"),
            "call mom on speaker" to call("mom"),
            "can you please call mom on whatsapp" to call("mom", "whatsapp"),
            "Hey Jimi, call mom on WhatsApp please" to call("mom", "whatsapp"),
            "message mom on Messenger saying I am coming" to message("mom", "i am coming", "messenger"),
            "message Mom Robi on Messenger saying hi" to message("mom robi", "hi", "messenger"),
            "send a message to mom via messenger saying hi" to message("mom", "hi", "messenger"),
            "write a message to mom using whatsapp that says hello" to message("mom", "hello", "whatsapp"),
            "message mom on messenger hello" to message("mom", "hello", "messenger"),
            "message mom on whatsapp saying hi" to message("mom", "hi", "whatsapp"),
            "send WhatsApp to Rahim hi" to message("rahim", "hi", "whatsapp"),
            "send whats app to Rahim hi" to message("rahim", "hi", "whatsapp"),
            "WhatsApp mom hello there" to message("mom", "hello there", "whatsapp"),
            "send a whatsapp message to Rahim Khan saying hello" to message("rahim khan", "hello", "whatsapp"),
            "send whatsapp to Rahim Khan that says please come now" to message("rahim khan", "please come now", "whatsapp"),
            "send whatsapp to Rahim Khan: hello, how are you?" to message("rahim khan", "hello, how are you?", "whatsapp"),
            "send whatsapp to mom saying meet me on messenger" to message("mom", "meet me on messenger", "whatsapp"),
            "send whatsapp to mom hi on messenger" to message("mom", "hi on messenger", "whatsapp"),
            "send whatsapp to mom saying turn off the flashlight" to message("mom", "turn off the flashlight", "whatsapp"),
            "send whatsapp to mom saying please call now, okay?" to message("mom", "please call now, okay?", "whatsapp"),
            "send whatsapp to mom saying two tickets for One Direction" to message("mom", "two tickets for one direction", "whatsapp"),
            "send whatsapp to mom saying say that again" to message("mom", "say that again", "whatsapp"),
            "send messenger to mom saying hello" to message("mom", "hello", "messenger"),
            "Messenger message to Rahim Khan saying hi" to message("rahim khan", "hi", "messenger"),
            "send a message to Rahim saying I am coming" to message("rahim", "i am coming"),
            "message Rahim Khan saying hello" to message("rahim khan", "hello"),
            "text mom saying meet me on whatsapp" to message("mom", "meet me on whatsapp"),
            "send sms to mom saying hello" to message("mom", "hello"),
            "message mom on sms saying hello" to message("mom", "hello"),
            "please message mom on messenger saying please be here now" to message("mom", "please be here now", "messenger"),
            "send whatsapp to mom" to null,
            "message mom on messenger" to null,
            "message mom on messenger saying" to null,
            "send whatsapp to mom saying" to null,
            "send whatsapp to Rahim Khan saying" to null,
            "send whatsapp to Rahim Khan that says" to null,
            "send whatsapp to Rahim Khan:" to null,
            "message Rahim Khan saying" to null,
            "open YouTube and play perfect" to media("perfect", "youtube"),
            "open YouTube app and play perfect" to media("perfect", "youtube"),
            "launch YouTube and search for lofi" to media("lofi", "youtube"),
            "search lofi on YouTube" to media("lofi", "youtube"),
            "search for lofi on YouTube" to media("lofi", "youtube"),
            "search YouTube for lofi" to media("lofi", "youtube"),
            "search lofi in YouTube" to media("lofi", "youtube"),
            "play perfect" to media("perfect"),
            "play the song perfect" to media("perfect"),
            "play some lofi" to media("lofi"),
            "play perfect on YouTube" to media("perfect", "youtube"),
            "play perfect on YouTube." to media("perfect", "youtube"),
            "play perfect on you tube" to media("perfect", "youtube"),
            "play perfect on YouTube Music" to media("perfect", "youtube_music"),
            "play perfect on you tube music" to media("perfect", "youtube_music"),
            "play perfect on Spotify" to media("perfect", "spotify"),
            "play perfect using Spotify" to media("perfect", "spotify"),
            "search for perfect on YouTube Music" to media("perfect", "youtube_music"),
            "search Spotify for perfect" to media("perfect", "spotify"),
            "open YouTube Music and play perfect" to media("perfect", "youtube_music"),
            "open Spotify and play perfect" to media("perfect", "spotify"),
            "please open YouTube and play turn on the flashlight" to media("turn on the flashlight", "youtube"),
            "play please please please" to media("please please please"),
            "play One Direction on Spotify" to media("one direction", "spotify"),
            "play" to ToolCall("media_control", mapOf("action" to "play")),
            "play music" to ToolCall("media_control", mapOf("action" to "play")),
            "pause the music" to ToolCall("media_control", mapOf("action" to "pause")),
            "next song" to ToolCall("media_control", mapOf("action" to "next")),
            "open youtube" to ToolCall("open_app", mapOf("app" to "youtube")),
            "search for flutter" to ToolCall("web_search", mapOf("query" to "flutter")),
            "turn on flashlight" to ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on")),
        ).map { arrayOf(it.first, it.second) }
    }
}
