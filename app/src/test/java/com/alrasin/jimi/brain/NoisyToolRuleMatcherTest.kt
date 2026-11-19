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
