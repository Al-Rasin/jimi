package com.alrasin.jimi.brain

import com.alrasin.jimi.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class NoisyToolRuleMatcherTest {
    @Test fun batteryKeywordSurvivesUnrecognizedWords() {
        val expected = ToolCall("device_status", mapOf("what" to "battery"))
        assertEquals(expected, RuleMatcher.match("james the battery for centers"))
        assertEquals(expected, RuleMatcher.match("check the battery for centers"))
        assertEquals(expected, RuleMatcher.match("what's my battery for centers"))
    }

    @Test fun timerAndAlarmKeepRecognizableTimeDespiteJunkPrefix() {
        assertEquals(
            ToolCall("set_timer", mapOf("seconds" to "300")),
            RuleMatcher.match("james a timer for five minutes"),
        )
        val alarm = RuleMatcher.match("james alarm for seven am", LocalDateTime.parse("2026-10-22T06:00"))!!
        assertEquals("set_alarm", alarm.tool)
        assertEquals("07:00", alarm.args["time"])
    }

    @Test fun volumeAndToggleDirectionSurviveJunkAroundArguments() {
        assertEquals(ToolCall("set_volume", mapOf("level" to "50")), RuleMatcher.match("james volume to fifty for centers"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), RuleMatcher.match("james volume down for centers"))
        assertEquals(ToolCall("toggle", mapOf("target" to "wifi", "state" to "off")), RuleMatcher.match("james wifi off for centers"))
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on")), RuleMatcher.match("james flashlight on for centers"))
    }

    @Test fun missingDirectionsAndExplanationsDoNotBecomeActions() {
        listOf("james wifi for centers", "james flashlight for centers", "why is my battery draining", "explain turn on wifi", "dont turn on wifi", "latest battery news", "battery technology").forEach {
            assertNull(it, RuleMatcher.match(it))
        }
    }

    @Test fun explicitSearchKeepsItsRequestedTopic() {
        listOf("search battery replacement", "search how to turn on wifi", "google timer for five minutes").forEach {
            assertEquals(it, "web_search", RuleMatcher.match(it)?.tool)
        }
        assertEquals("wiki_lookup", RuleMatcher.match("tell me about turning on flashlight")?.tool)
    }

    @Test fun dictationKeepsItsContents() {
        assertEquals(ToolCall("type_text", mapOf("text" to "james battery for centers")), RuleMatcher.match("type james battery for centers"))
        assertEquals(ToolCall("create_note", mapOf("text" to "james battery for centers")), RuleMatcher.match("take a note james battery for centers"))
    }
}

class MisheardWakeLeadTest {
    @Test fun misheardHeyJimiLeadIsDropped() {
        assertEquals(ToolCall("device_status", mapOf("what" to "time")), RuleMatcher.match("as you me what time is it"))
        assertEquals(ToolCall("phone_profile", mapOf("ask" to "abilities")), RuleMatcher.match("asium me what can you do"))
    }

    @Test fun wirelessDeviceMeansDebugging() {
        val on = ToolCall("toggle", mapOf("target" to "wireless_debugging", "state" to "on"))
        assertEquals(on, RuleMatcher.match("on wireless device"))
        assertEquals(on, RuleMatcher.match("turn on wireless device"))
    }
}

class AlarmIsSetQuestionTest {
    @Test fun askingWhichAlarmsAreSetLists() {
        val list = ToolCall("list_alarms", emptyMap())
        assertEquals(list, RuleMatcher.match("which alarms are set"))
        assertEquals(list, RuleMatcher.match("any alarm set"))
        assertEquals(list, RuleMatcher.match("is any alarm set"))
        assertEquals("set_alarm", RuleMatcher.match("set an alarm for 6")?.tool)
    }
}

class ScreenAndPrayerPhrasesTest {
    @Test fun seeScreenAsksScreen() {
        val what = ToolCall("ask_screen", mapOf("mode" to "what"))
        assertEquals(what, RuleMatcher.match("can you see the current screen what is opened"))
        assertEquals(what, RuleMatcher.match("what app is open"))
        assertEquals(what, RuleMatcher.match("can you see my screen"))
    }
    @Test fun tellMeFajrTime() {
        assertEquals(ToolCall("prayer_times", mapOf("prayer" to "fajr")), RuleMatcher.match("tell me the fajr time"))
    }
}

class PhoneThingsAreNotContactsTest {
    @Test fun phoneWordsDoNotCallFakeContacts() {
        for (text in listOf("ring mode", "phone settings", "call log", "phone number")) {
            assertNotEquals(text, "call", RuleMatcher.match(text)?.tool)
        }
        assertEquals(ToolCall("device_status", mapOf("what" to "battery")), RuleMatcher.match("phone battery"))
        assertEquals("call", RuleMatcher.match("call my mom")?.tool)
        assertEquals("call", RuleMatcher.match("ring karim")?.tool)
    }
    @Test fun searchGoogleForKeepsOnlyQuery() {
        assertEquals(ToolCall("web_search", mapOf("query" to "best phones")), RuleMatcher.match("search google for best phones"))
        assertEquals(ToolCall("web_search", mapOf("query" to "cheap flights")), RuleMatcher.match("search the web for cheap flights"))
    }
    @Test fun volumeToMax() {
        val max = ToolCall("set_volume", mapOf("level" to "100"))
        assertEquals(max, RuleMatcher.match("set volume to max"))
        assertEquals(max, RuleMatcher.match("volume to max"))
        assertEquals(max, RuleMatcher.match("turn the volume up to max"))
    }
}

class RingerAndStatusTest {
    private fun status(what: String) = ToolCall("device_status", mapOf("what" to what))
    private fun toggle(target: String, state: String) = ToolCall("toggle", mapOf("target" to target, "state" to state))

    @Test fun vibrateAndRingModes() {
        assertEquals(toggle("vibrate", "on"), RuleMatcher.match("put phone on vibrate"))
        assertEquals(toggle("vibrate", "on"), RuleMatcher.match("turn on vibrate mode"))
        assertEquals(toggle("vibrate", "off"), RuleMatcher.match("put my phone on ring mode"))
        assertEquals(toggle("vibrate", "off"), RuleMatcher.match("unmute my phone"))
        assertEquals(toggle("dnd", "on"), RuleMatcher.match("put my phone on silent"))
        assertEquals("setting", RuleMatcher.match("turn on vibration")?.tool)
    }
    @Test fun tomorrowAndRam() {
        assertEquals(status("tomorrow"), RuleMatcher.match("what day is tomorrow"))
        assertEquals(status("memory"), RuleMatcher.match("how much ram is free"))
        assertEquals(status("memory"), RuleMatcher.match("is my memory full"))
        assertEquals("recall_facts", RuleMatcher.match("what do you remember about me")?.tool)
        for (q in listOf("where did i park", "where is my car parked", "where did I park my car?", "where's my bike")) {
            assertEquals(q, mapOf("about" to "where parked car"), RuleMatcher.match(q)?.takeIf { it.tool == "recall_facts" }?.args)
        }
        assertEquals(status("battery"), RuleMatcher.match("is my phone charging"))
    }
}

class EverydayGapsTest {
    @Test fun alarmPrayerVolume() {
        assertEquals("list_alarms", RuleMatcher.match("what's my next alarm")?.tool)
        assertEquals("prayer_times", RuleMatcher.match("what is the prayer time")?.tool)
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), RuleMatcher.match("turn it down"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "up")), RuleMatcher.match("turn it up"))
    }
    @Test fun chanceAndPersonalSmallTalk() {
        assert(Persona.smallTalk("flip a coin", 10) in setOf("Heads.", "Tails."))
        assert(Persona.smallTalk("roll a dice", 10)!!.startsWith("You rolled a "))
        assert(Persona.smallTalk("how old are you", 10)!!.startsWith("I'm new"))
        assert(Persona.smallTalk("i love you", 10) != null)
    }
}

class StatusAndSelfTalkTest {
    @Test fun askingIfAToggleIsOnOnlyReadsIt() {
        for ((q, target) in listOf(
            "is wifi on" to "wifi", "is bluetooth turned off" to "bluetooth", "is the flashlight on" to "flashlight",
            "what wifi am i connected to" to "wifi", "am i connected to wifi" to "wifi",
        )) {
            assertEquals(q, ToolCall("toggle", mapOf("target" to target, "state" to "status")), RuleMatcher.match(q))
        }
        assertEquals("on", RuleMatcher.match("turn wifi on")?.args?.get("state"))
    }
    @Test fun selfAndMakerQuestionsStayInSmallTalk() {
        for (q in listOf("tell me about yourself", "who are you")) {
            assertEquals(q, "I'm Jimi, your phone assistant.", Persona.smallTalk(q, 10))
            assertEquals(q, null, RuleMatcher.match(q)?.takeIf { it.tool == "wiki_lookup" })
        }
        assert(Persona.smallTalk("who made you", 10)!!.startsWith("I was built just for you"))
        assertEquals("wiki_lookup", RuleMatcher.match("tell me about the moon")?.tool)
    }
    @Test fun stopwatchOpensClock() {
        assertEquals(ToolCall("open_app", mapOf("app" to "clock")), RuleMatcher.match("start a stopwatch"))
    }
}

class StateLastAndRecallAboutTest {
    @Test fun settingWithStateSaidLast() {
        assertEquals(ToolCall("setting", mapOf("name" to "dark mode", "state" to "on")), RuleMatcher.match("dark mode on"))
        assertEquals(ToolCall("toggle", mapOf("target" to "wifi", "state" to "on")), RuleMatcher.match("wifi on"))
    }
    @Test fun recallOneFact() {
        assertEquals(mapOf("about" to "my locker"), RuleMatcher.match("what did i tell you about my locker")?.args)
        assertEquals(ToolCall("recall_facts", mapOf("about" to "where i kept my keys")), RuleMatcher.match("do you remember where i kept my keys"))
    }
}

class FillerAndAmountTest {
    @Test fun fillersAndAmountWordsAreIgnored() {
        assertEquals(ToolCall("open_app", mapOf("app" to "whatsapp")), RuleMatcher.match("um open uh whatsapp"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "up")), RuleMatcher.match("turn up the volume a bit"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), RuleMatcher.match("turn it down a little"))
        assertEquals("read_messages", RuleMatcher.match("unread messages")?.tool)
    }
}

class AlarmNoteRotateHeatTest {
    @Test fun phrasesMapToTheRightTool() {
        assertEquals("list_alarms", RuleMatcher.match("what time is my alarm")?.tool)
        assertEquals(ToolCall("create_note", mapOf("text" to "milk")), RuleMatcher.match("add milk to my notes"))
        assertEquals(ToolCall("set_brightness", mapOf("level" to "100")), RuleMatcher.match("set brightness to max"))
        assertEquals(ToolCall("toggle", mapOf("target" to "auto_rotate", "state" to "on")), RuleMatcher.match("rotate the screen"))
        assertEquals(ToolCall("toggle", mapOf("target" to "auto_rotate", "state" to "off")), RuleMatcher.match("lock rotation"))
        assertEquals(ToolCall("device_status", mapOf("what" to "temperature")), RuleMatcher.match("is my phone hot"))
        assertEquals(ToolCall("device_status", mapOf("what" to "temperature")), RuleMatcher.match("phone temperature"))
    }
}

class UnmuteRecorderPhotosTest {
    @Test fun phrasesMapToTheRightTool() {
        assertEquals(ToolCall("set_volume", mapOf("level" to "50")), RuleMatcher.match("unmute the phone"))
        assertEquals(ToolCall("open_app", mapOf("app" to "recorder")), RuleMatcher.match("record my voice"))
        assertEquals(ToolCall("open_app", mapOf("app" to "gallery")), RuleMatcher.match("show me my photos"))
    }
}

class AlarmCountdownTipTest {
    @Test fun howLongUntilAlarmListsAlarms() {
        assertEquals(ToolCall("list_alarms", emptyMap()), RuleMatcher.match("how long until my alarm"))
    }

    @Test fun tipIsAPercent() {
        assertEquals("20 percent of 50 is 10.", QuickMath.answer("what's 20 percent tip on 50"))
    }
}

class MathWordsTest {
    @Test fun fractionsAndDiscounts() {
        assertEquals("Half of 80 is 40.", QuickMath.answer("what is half of 80"))
        assertEquals("A third of 90 is 30.", QuickMath.answer("what's a third of 90"))
        assertEquals("Double 45 is 90.", QuickMath.answer("double 45"))
        assertEquals("200 with 10 percent off is 180.", QuickMath.answer("what's 10 percent off 200"))
    }

    @Test fun roundFactorialPrimeAverage() {
        assertEquals("3.7 rounded is 4.", QuickMath.answer("round 3.7"))
        assertEquals("7 factorial is 5040.", QuickMath.answer("what's 7 factorial"))
        assertEquals("Yes, 17 is a prime number.", QuickMath.answer("is 17 a prime number"))
        assertEquals("No, 21 is not prime. It divides by 3.", QuickMath.answer("is 21 prime"))
        assertEquals("The average is 6.", QuickMath.answer("average of 4, 6 and 8"))
        assertEquals("2 to the power of 10 is 1024.", QuickMath.answer("what is 2 to the 10th"))
        assertEquals(null, QuickMath.answer("21 factorial"))
    }
}

class ComplaintPhrasesTest {
    private fun m(s: String) = RuleMatcher.match(s)

    @Test fun loudQuietBrightDark() {
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), m("it's too loud"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "up")), m("i can't hear you"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "up")), m("too quiet"))
        assertEquals(ToolCall("set_brightness", mapOf("level" to "down")), m("screen is too bright"))
        assertEquals(ToolCall("set_brightness", mapOf("level" to "up")), m("its too dark"))
        assertNull(m("is it too loud"))
    }

    @Test fun skipUnreadNotificationWeatherBrowser() {
        assertEquals(ToolCall("media_control", mapOf("action" to "next")), m("skip this song"))
        assertEquals(ToolCall("read_messages", emptyMap()), m("how many unread messages"))
        assertEquals(ToolCall("read_notifications", emptyMap()), m("read my last notification"))
        assertEquals(ToolCall("weather", mapOf("place" to "dhaka")), m("look up the weather in dhaka"))
        assertEquals(ToolCall("web_search", mapOf("query" to "cats")), m("open chrome and search for cats"))
    }

    @Test fun dndAndSilence() {
        val dnd = ToolCall("toggle", mapOf("target" to "dnd", "state" to "on"))
        assertEquals(dnd, m("do not disturb on"))
        assertEquals(dnd, m("silence my phone"))
        assertNull(m("do not turn on wifi"))
    }

    @Test fun switchToModeOrApp() {
        assertEquals(ToolCall("open_app", mapOf("app" to "whatsapp")), m("switch to whatsapp"))
        assertEquals(ToolCall("toggle", mapOf("target" to "dnd", "state" to "on")), m("switch to silent"))
        assertEquals(ToolCall("toggle", mapOf("target" to "vibrate", "state" to "on")), m("switch to vibrate"))
        assertEquals(ToolCall("toggle", mapOf("target" to "airplane", "state" to "on")), m("switch to airplane mode"))
        assertNotEquals("open_app", m("switch to english")?.tool)
        assertEquals(ToolCall("media_control", mapOf("action" to "previous")), m("go back to the last song"))
        assertEquals("go_back", m("go back")?.tool)
    }
    @Test fun leadWordRewrites() {
        assertEquals(ToolCall("set_timer", mapOf("seconds" to "600")), m("i need a timer for 10 minutes"))
        assertEquals("take_photo", m("let me take a photo")?.tool)
        assertEquals(ToolCall("setting", mapOf("name" to "font size", "state" to "open")), m("make the text bigger"))
        assertEquals(ToolCall("set_brightness", mapOf("level" to "manual")), m("turn off auto brightness"))
        assertNotEquals("set_alarm", m("alarm volume 50")?.tool)
        assertEquals("remember_fact", m("i want you to remember my wife is sara")?.tool)
        assertEquals(ToolCall("set_alarm", mapOf("time" to "06:00")), m("i need to wake up at 6"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), m("media volume low"))
        assertNull(m("lets play a game"))
        assertEquals("play_media", m("listen to quran")?.tool)
    }
    @Test fun lightsTorchLockMyPhone() {
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "off")), m("lights off"))
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "toggle")), m("torch"))
        assertEquals(ToolCall("lock_phone", emptyMap()), m("lock my phone"))
        assertEquals(ToolCall("unlock_phone", emptyMap()), m("unlock my phone"))
        assertNull(m("wifi"))
        assertEquals("weather", m("is it cold today")?.tool)
    }

    @Test fun translatedEnglishPhrases() {
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on")), m("Turn on the light."))
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "off")), m("Close the light."))
        assertEquals(ToolCall("call", mapOf("contact" to "father")), m("Give a call to father."))
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), m("Decrease the sound."))
        assertEquals(ToolCall("set_brightness", mapOf("level" to "up")), m("Make the screen bright."))
        assertEquals(ToolCall("send_sms", mapOf("contact" to "brother")), m("Send a message to brother."))
        assertEquals("setting", m("turn on light mode")?.tool)
        assertEquals("I'm Jimi, your phone assistant.", com.alrasin.jimi.brain.Persona.smallTalk("What is your name?", 10))
    }

    @Test fun moreTranslatedPhrases() {
        assertEquals(ToolCall("call", mapOf("contact" to "rahim")), m("Make a call to Rahim."))
        assertEquals(ToolCall("open_app", mapOf("app" to "settings")), m("Open the settings."))
        assertEquals(ToolCall("open_app", mapOf("app" to "settings")), m("open settings"))
        assertEquals("read_messages", m("Any new message?")?.tool)
        assertEquals(ToolCall("set_volume", mapOf("level" to "0")), m("Turn off the sound."))
        assertEquals(ToolCall("set_volume", mapOf("level" to "up")), m("turn up the sound"))
        assertEquals(ToolCall("create_note", mapOf("text" to "buy milk")), m("Note down buy milk."))
        assertEquals("cancel", m("Cancel it.")?.tool)
    }

    @Test fun halfSongOfTakeMeHome() {
        assertEquals(ToolCall("play_media", mapOf("query" to "arijit singh songs")), m("Play a song of Arijit Singh."))
        assertEquals(ToolCall("set_volume", mapOf("level" to "50")), m("Set volume to half."))
        assertEquals(ToolCall("set_brightness", mapOf("level" to "50")), m("Make brightness 50 percent."))
        assertEquals(ToolCall("open_map", mapOf("place" to "home", "mode" to "navigate")), m("Take me home."))
        assertEquals("go_home", m("go home")?.tool)
    }

    @Test fun anyoneMessagedNormalMode() {
        assertEquals(ToolCall("read_messages", emptyMap()), m("Did anyone message me?"))
        assertEquals(ToolCall("toggle", mapOf("target" to "vibrate", "state" to "off")), m("Normal mode."))
        assertEquals(ToolCall("toggle", mapOf("target" to "vibrate", "state" to "off")), m("Put phone in normal mode."))
    }

    @Test fun lastPhotoSelfieShoppingDay() {
        assertEquals(ToolCall("open_app", mapOf("app" to "gallery")), m("Open the last photo."))
        assertEquals(ToolCall("open_app", mapOf("app" to "camera")), m("Open camera in selfie mode."))
        assertEquals(ToolCall("create_note", mapOf("text" to "shopping list milk")), m("Add milk to my shopping list."))
        assertEquals(ToolCall("device_status", mapOf("what" to "tomorrow")), m("What day is it tomorrow?"))
    }
}
