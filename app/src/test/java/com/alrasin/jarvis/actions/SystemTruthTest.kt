package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTruthTest {
    @Test fun unknownStateIsNeverGuessedForToggle() {
        assertNull(requestedToggleState("toggle", null))
        assertNull(requestedToggleState(null, null))
        assertEquals(true, requestedToggleState("on", null))
        assertEquals(false, requestedToggleState("off", null))
        assertEquals(false, requestedToggleState("toggle", true))
        assertEquals(true, requestedToggleState("toggle", false))
    }

    @Test fun onlyMatchingReadbackReportsSuccess() {
        for (wanted in listOf(true, false)) {
            val verified = toggleTruth("Wi-Fi", wanted, wanted)
            assertTrue(verified.ok)
            assertEquals("Boss, Wi-Fi is ${if (wanted) "on" else "off"}.", verified.say)

            val unchanged = toggleTruth("Wi-Fi", wanted, !wanted)
            assertFalse(unchanged.ok)
            assertEquals("Boss, Wi-Fi is still ${if (!wanted) "on" else "off"}.", unchanged.say)

            val unknown = toggleTruth("Wi-Fi", wanted, null)
            assertFalse(unknown.ok)
            assertTrue(unknown.say.contains("couldn't verify"))
        }
    }
}
