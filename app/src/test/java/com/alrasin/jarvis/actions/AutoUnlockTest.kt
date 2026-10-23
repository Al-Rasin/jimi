package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoUnlockTest {
    private val whatsapp = ToolCall("send_whatsapp", mapOf("contact" to "Mother G", "text" to "hi"))

    @Test fun unlocksOnlyWhenLockedScreenToolAndVoiceMatched() {
        assertEquals(Unlock.UNLOCK, unlockNeed(whatsapp, locked = true, voiceOk = true))
        assertEquals(Unlock.NONE, unlockNeed(whatsapp, locked = false, voiceOk = true))
        assertEquals(Unlock.NONE, unlockNeed(whatsapp, locked = true, voiceOk = false))
        assertEquals(Unlock.VOICE_ID_OFF, unlockNeed(whatsapp, locked = true, voiceOk = null))
    }

    @Test fun plainSmsAndPhoneCallsDoNotNeedTheScreen() {
        assertEquals(Unlock.NONE, unlockNeed(ToolCall("send_sms", mapOf("contact" to "a", "text" to "b")), true, true))
        assertEquals(Unlock.NONE, unlockNeed(ToolCall("call", mapOf("contact" to "a")), true, true))
        assertEquals(Unlock.UNLOCK, unlockNeed(ToolCall("send_sms", mapOf("app" to "messenger")), true, true))
        assertEquals(Unlock.UNLOCK, unlockNeed(ToolCall("call", mapOf("app" to "whatsapp")), true, true))
    }

    @Test fun openAppWithoutVoiceIdRunsAsBefore() {
        assertEquals(Unlock.NONE, unlockNeed(ToolCall("open_app", mapOf("app" to "youtube")), true, null))
        assertEquals(Unlock.UNLOCK, unlockNeed(ToolCall("open_app", mapOf("app" to "youtube")), true, true))
    }

    @Test fun wirelessDebuggingIsNotWifi() {
        for (t in listOf("turn on wireless debugging", "enable wireless debugging", "wireless debugging on", "turn on adb wifi")) {
            assertEquals(t, ToolCall("toggle", mapOf("target" to "wireless_debugging", "state" to "on")), RuleMatcher.match(t))
        }
        assertEquals(ToolCall("toggle", mapOf("target" to "usb_debugging", "state" to "off")), RuleMatcher.match("turn off usb debugging"))
        assertEquals(ToolCall("toggle", mapOf("target" to "wifi", "state" to "on")), RuleMatcher.match("turn on wifi"))
    }
}
