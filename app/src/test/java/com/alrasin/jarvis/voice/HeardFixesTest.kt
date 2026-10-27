package com.alrasin.jarvis.voice

import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** ROADMAP §M.1 (#55): every phrase heard on the phone is a test. */
class HeardFixesTest {
    private val corrector = SttCorrector()
    private val now = LocalDateTime.of(2026, 10, 28, 10, 0)

    private fun heard(raw: String) = corrector.correct(WakePhrase.strip(SpeechToText.normalize(raw)))

    @Test fun stripsWakeLeftovers() {
        mapOf(
            "hizumi open youtube" to "open youtube",
            "asium me set alarm for 7 am" to "set alarm for 7 am",
            "as you me what time is it" to "what time is it",
            "hazyme open camera" to "open camera",
            "hay mi open camera" to "open camera",
            "hey yarn open camera" to "open camera",
            "his re set alarm for 7 am" to "set alarm for 7 am",
            "asuming open camera" to "open camera",
            "hey see me open camera" to "open camera",
            "as well what time is it" to "what time is it",
        ).forEach { (raw, clean) -> assertEquals(raw, clean, WakePhrase.strip(raw)) }
    }

    @Test fun keepsNormalWordsThatLookLikeLeftovers() {
        listOf("his report is ready", "as well as milk", "see me later").forEach {
            assertEquals(it, WakePhrase.strip(it))
        }
    }

    @Test fun fixesKnownMishears() {
        mapOf(
            "turn on wireless divagging" to "turn on wireless debugging",
            "turn on diwangi" to "turn on wireless debugging",
            "set alum for 7 am" to "set alarm for 7 am",
            "alum at 6" to "alarm at 6",
            "set alarm two more 7 am" to "set alarm tomorrow 7 am",
            "shark for cats" to "search for cats",
            "shark cats in youtube" to "search cats in youtube",
            "unbox" to "unlock",
            "unbox the phone" to "unlock the phone",
            "call m o m" to "call mom",
        ).forEach { (raw, fixed) -> assertEquals(raw, fixed, HeardFixes.apply(raw)) }
    }

    @Test fun leavesDictationAlone() {
        listOf(
            "give me two more minutes",
            "shark facts",
            "unbox my new phone today",
            "i am a b",
            "note the lum is bright",
            "text mom saying set alum two more",
            "search for divagging",
        ).forEach { assertEquals(it, HeardFixes.apply(it)) }
    }

    @Test fun acceptanceTorchOn() {
        val call = RuleMatcher.match(heard("hazyme down on flushlight"), now)
        assertEquals("toggle", call?.tool)
        assertEquals("flashlight", call?.args?.get("target"))
        assertEquals("on", call?.args?.get("state"))
    }

    @Test fun acceptanceAlarmTomorrowSeven() {
        assertEquals(ToolCall("set_alarm", mapOf("time" to "07:00", "date" to "2026-10-29")),
            RuleMatcher.match(heard("his re set alum two more 7 am"), now))
    }
}
