package com.alrasin.jarvis.brain

import com.alrasin.jarvis.brain.ContextModes.Kind
import com.alrasin.jarvis.brain.ContextModes.Output
import com.alrasin.jarvis.brain.ContextModes.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextModesTest {
    private val noon = 12 * 60

    @Test fun carByNameOrClass() {
        assertTrue(ContextModes.decide(listOf(Output(Kind.BLUETOOTH, "Toyota Corolla")), noon, Prefs()).car)
        assertTrue(ContextModes.decide(listOf(Output(Kind.BLUETOOTH, "XYZ-123", carClass = true)), noon, Prefs()).car)
        assertFalse(ContextModes.decide(listOf(Output(Kind.BLUETOOTH, "Galaxy Buds")), noon, Prefs()).car)
    }

    @Test fun carWinsOverHeadphones() {
        val m = ContextModes.decide(listOf(Output(Kind.BLUETOOTH, "Honda Car Kit")), noon, Prefs())
        assertTrue(m.car)
        assertFalse(m.headphones)
        assertTrue(m.noScreen)
        assertTrue(m.readMessages)
    }

    @Test fun headphonesWiredAndBluetooth() {
        assertTrue(ContextModes.decide(listOf(Output(Kind.WIRED, "Headset")), noon, Prefs()).headphones)
        assertTrue(ContextModes.decide(listOf(Output(Kind.BLUETOOTH, "Redmi Buds 4")), noon, Prefs()).headphones)
        assertFalse(ContextModes.decide(listOf(Output(Kind.OTHER, "HDMI")), noon, Prefs()).headphones)
        assertFalse(ContextModes.decide(emptyList(), noon, Prefs()).headphones)
    }

    @Test fun switchesOff() {
        val p = Prefs(car = false, headphones = false)
        val m = ContextModes.decide(listOf(Output(Kind.BLUETOOTH, "Toyota")), noon, p)
        assertFalse(m.car)
        assertFalse(m.headphones)
    }

    @Test fun bedtimeWindow() {
        val p = Prefs(bedtime = true, hours = "23-6")
        assertTrue(ContextModes.decide(emptyList(), 23 * 60 + 30, p).bedtime)
        assertTrue(ContextModes.decide(emptyList(), 2 * 60, p).bedtime)
        assertFalse(ContextModes.decide(emptyList(), 6 * 60, p).bedtime)
        assertFalse(ContextModes.decide(emptyList(), noon, p).bedtime)
        assertFalse(ContextModes.decide(emptyList(), 2 * 60, Prefs(bedtime = false)).bedtime)
    }

    @Test fun hoursParse() {
        assertEquals(23 to 6, ContextModes.hours("23-6"))
        assertEquals(0 to 7, ContextModes.hours(" 0 - 7 "))
        assertNull(ContextModes.hours("5-5"))
        assertNull(ContextModes.hours("25-6"))
        assertNull(ContextModes.hours("abc"))
        assertNull(ContextModes.hours("1-2-3"))
    }

    @Test fun windowNoWrap() {
        assertTrue(ContextModes.inWindow(60, 0, 420))
        assertFalse(ContextModes.inWindow(420, 0, 420))
    }

    @Test fun shortenKeepsTwoSentences() {
        assertEquals("One. Two!", ContextModes.shorten("One. Two! Three? Four."))
        assertEquals("Only one.", ContextModes.shorten("  Only one.  "))
    }

    @Test fun messageLineNewestShort() {
        assertEquals("Rahim says: on my way", ContextModes.messageLine("WhatsApp", "Rahim", "hi\nRahim: on my way\n"))
        // Real WhatsApp shape: plain text, then the same message with the sender (case can differ).
        assertEquals("Chaity skitto says: Ohh", ContextModes.messageLine("WhatsApp", "Chaity skitto", "Ohh\nchaity Skitto: Ohh"))
        assertEquals("Messages: hello", ContextModes.messageLine("Messages", "", "hello"))
        assertNull(ContextModes.messageLine("X", "Y", "  \n "))
        val long = ContextModes.messageLine("A", "B", "x".repeat(300))!!
        assertTrue(long.endsWith("…"))
        assertEquals("B says: ".length + 158, long.length)
    }
}
