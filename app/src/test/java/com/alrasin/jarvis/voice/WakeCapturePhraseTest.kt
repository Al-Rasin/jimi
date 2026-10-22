package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeCapturePhraseTest {
    @Test fun stripsObservedMishearingsAtStart() {
        for (wake in listOf("izumi", "hayzumi", "zooming", "hey jimmy", "hay gymmy", "hazy me")) {
            assertEquals(wake, "turn on the flashlight", WakePhrase.strip("$wake turn on the flashlight"))
        }
    }

    @Test fun supportsCaseAndPunctuationWithoutChangingCommand() {
        assertEquals("Call Jimmy, please!", WakePhrase.strip("  Hey, Jimi! Call Jimmy, please!  "))
        assertEquals("turn on the flashlight", WakePhrase.strip("Hey Jimi—turn on the flashlight"))
    }

    @Test fun wakeOnlyLeavesNoCommand() {
        for (wake in listOf("HEY, JIMI!", "izumi.", "hayzumi", "zooming", "hey jimmy")) {
            assertEquals(wake, "", WakePhrase.strip(wake))
        }
    }

    @Test fun keepsFirstCommandWordEvenWhenItWasInOldWakeWordList() {
        assertEquals("me first", WakePhrase.strip("hey jimmy me first"))
        assertEquals("a song please", WakePhrase.strip("hey jimi a song please"))
        assertEquals("Jimmy called me", WakePhrase.strip("hey jimi Jimmy called me"))
        assertEquals("gym hours", WakePhrase.strip("hey jimi gym hours"))
    }

    @Test fun preservesWakeWordsInsideCommandAndContactNames() {
        for (command in listOf("call Izumi", "message Jimmy hello", "search for hey jimi", "call Hayzumi")) {
            assertEquals(command, command, WakePhrase.strip(command))
            assertEquals(command, command, WakePhrase.strip("hey jimi $command"))
        }
    }

    @Test fun doesNotStripNamesByPrefixOrPossessives() {
        for (command in listOf("Jimothy called", "gymnastics today", "Izumiko called", "Jimmy's number", "Jimmy’s number")) {
            assertEquals(command, command, WakePhrase.strip(command))
        }
    }

    @Test fun preservesLoneGreetingAndAmbiguousNames() {
        for (command in listOf("hey turn on the flashlight", "hi there", "a song please", "me first", "Jim called", "Jamie called", "gym hours")) {
            assertEquals(command, command, WakePhrase.strip(command))
        }
    }

    @Test fun handlesEmptyText() {
        assertEquals("", WakePhrase.strip(""))
        assertEquals("", WakePhrase.strip(" \n "))
    }
}
