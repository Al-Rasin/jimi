package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTextTest {
    @Test fun ringingText() {
        assertEquals("Rahim is calling.", CallText.ringing("Rahim"))
        assertEquals("Unknown number is calling.", CallText.ringing(" "))
        assertEquals("Mom is calling on WhatsApp.", CallText.ringing("Mom", "WhatsApp"))
    }

    @Test fun picksNotificationButton() {
        val buttons = listOf("Decline", "Answer")
        assertEquals(1, CallText.actionIndex(buttons, answer = true))
        assertEquals(0, CallText.actionIndex(buttons, answer = false))
        assertEquals(-1, CallText.actionIndex(listOf("Reply", null), answer = true))
        assertTrue(CallText.isIncoming(listOf("DECLINE", "ACCEPT")))
        assertFalse(CallText.isIncoming(listOf("Hang up", "Speaker")))
    }

    @Test fun missedSummaryGroupsCallers() {
        assertEquals("No missed calls.", CallText.missedSummary(emptyList()))
        assertEquals("1 missed call: Karim.", CallText.missedSummary(listOf("Karim")))
        assertEquals("4 missed calls: Rahim twice, Karim, Mom.", CallText.missedSummary(listOf("Rahim", "Karim", "Rahim", "Mom")))
        assertEquals("3 missed calls: Rahim 3 times.", CallText.missedSummary(listOf("Rahim", "Rahim", "Rahim")))
    }
}
