package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class Fix8CaptureTest {
    @Test fun observedWakeLeaksReachFastRules() {
        listOf("as you me", "as you may", "hay mi", "hey jimmy").forEach { wake ->
            assertEquals("device_status", com.alrasin.jarvis.brain.RuleMatcher.match(
                WakePhrase.strip("$wake what time is it"))?.tool)
            assertEquals("toggle", com.alrasin.jarvis.brain.RuleMatcher.match(
                WakePhrase.strip("$wake down on flashlight"))?.tool)
        }
    }

    @Test fun onlinePhoneNeverAlternatesToSherpaAfterFailure() {
        val policy = SpeechCapturePolicy()
        repeat(4) {
            assertEquals(SpeechEngine.PHONE_ON_DEVICE,
                policy.select(SpeechEngine.PHONE_ON_DEVICE, online = true))
            policy.phoneFailed()
        }
        assertEquals(SpeechEngine.JIMI_OFFLINE,
            policy.select(SpeechEngine.JIMI_OFFLINE, online = true))
    }

    @Test fun offlineFailureStillHasOneCaptureFallback() {
        val policy = SpeechCapturePolicy()
        policy.select(SpeechEngine.PHONE_ON_DEVICE)
        policy.phoneFailed()
        assertEquals(SpeechEngine.JIMI_OFFLINE, policy.select(SpeechEngine.PHONE_ON_DEVICE))
        assertEquals(SpeechEngine.PHONE_ON_DEVICE, policy.select(SpeechEngine.PHONE_ON_DEVICE))
    }

    @Test fun greetingMishearsAreRemovedOnlyAtTheStart() {
        listOf("Hey, Jenny!", "Hey genie", "Hay gimme", "Hey jee mee", "Hey Jamie", "Hi Jim", "hay mi", "as you me", "as you may", "as well").forEach {
            assertEquals(it, "set alarm for 7 am", WakePhrase.strip("$it set alarm for 7 am"))
        }
        listOf("call Jenny", "tell Jim hey Jimmy", "play Hey Jude", "genie in a bottle", "as you may know", "as well as tomorrow").forEach {
            assertEquals(it, WakePhrase.strip(it))
        }
    }

    @Test fun clockColonSurvivesSpeechNormalization() {
        assertEquals("wake me at 6:30", SpeechToText.normalize("Wake me at 6:30."))
        assertEquals("alarm 7 am", SpeechToText.normalize("Alarm: 7 AM!"))
    }
}
