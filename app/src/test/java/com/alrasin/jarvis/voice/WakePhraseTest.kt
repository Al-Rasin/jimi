package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseTest {
    @Test fun stripsHeyJimmy() = assertEquals("turn on the flashlight", WakePhrase.strip("hey jimmy turn on the flashlight"))
    @Test fun keepsLoneMe() = assertEquals("me turn on", WakePhrase.strip("me turn on"))
    @Test fun stripsHayGym() = assertEquals("what time is it", WakePhrase.strip("hay gymmy what time is it"))
    @Test fun stripsJimOnly() = assertEquals("call mom", WakePhrase.strip("jimmy call mom"))
    @Test fun keepsNormalText() = assertEquals("remind me at five", WakePhrase.strip("remind me at five"))
    @Test fun keepsLoneA() = assertEquals("a song please", WakePhrase.strip("a song please"))
    @Test fun stripsIzumi() = assertEquals("open youtube", WakePhrase.strip("izumi open youtube"))
    @Test fun emptyStaysEmpty() = assertEquals("", WakePhrase.strip(""))
    @Test fun onlyWakeGivesEmpty() = assertEquals("", WakePhrase.strip("hey jimmy"))
    @Test fun onlyWakeDetected() = assertEquals(true, WakePhrase.isOnlyWake("hey jimmy"))
    @Test fun wakeWithCommandIsNotOnlyWake() = assertEquals(false, WakePhrase.isOnlyWake("hey jimmy set a timer"))
    @Test fun blankIsNotOnlyWake() = assertEquals(false, WakePhrase.isOnlyWake(""))
    @Test fun replyIsNotOnlyWake() = assertEquals(false, WakePhrase.isOnlyWake("yes please"))
}

class SpeechTextNormalizeTest {
    @Test fun dropsPunctuationAndCase() = assertEquals("hey jimmy", SpeechToText.normalize("Hey, Jimmy."))
    @Test fun keepsApostrophe() = assertEquals("what's the time", SpeechToText.normalize("What's the time?"))
}
