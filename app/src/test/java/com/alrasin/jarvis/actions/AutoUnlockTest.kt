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
        assertEquals(Unlock.VOICE_MISMATCH, unlockNeed(whatsapp, locked = true, voiceOk = false))
        assertEquals(Unlock.VOICE_ID_OFF, unlockNeed(whatsapp, locked = true, voiceOk = null))
    }

    @Test fun screenReadersAndDriversAllNeedUnlock() {
        for (tool in listOf("read_screen", "tap", "type_text", "scroll")) {
            assertEquals(tool, Unlock.UNLOCK, unlockNeed(ToolCall(tool), locked = true, voiceOk = true))
            assertEquals(tool, Unlock.NONE, unlockNeed(ToolCall(tool), locked = false, voiceOk = true))
        }
    }

    @Test fun navigationToolsNeverNeedUnlock() {
        for (tool in listOf("go_back", "go_home", "open_recents")) {
            assertEquals(tool, Unlock.NONE, unlockNeed(ToolCall(tool), locked = true, voiceOk = true))
            assertEquals(tool, Unlock.NONE, unlockNeed(ToolCall(tool), locked = true, voiceOk = null))
        }
    }

    @Test fun paymentAppsNeverAutoUnlockEvenWithGoodVoice() {
        assertEquals(
            Unlock.PAYMENT_APP,
            unlockNeed(ToolCall("open_app", mapOf("app" to "bkash")), locked = true, voiceOk = true, isPaymentApp = true),
        )
    }

    @Test fun plainSmsAndPhoneCallsDoNotNeedTheScreen() {
        assertEquals(Unlock.NONE, unlockNeed(ToolCall("send_sms", mapOf("contact" to "a", "text" to "b")), true, true))
        assertEquals(Unlock.NONE, unlockNeed(ToolCall("call", mapOf("contact" to "a")), true, true))
        assertEquals(Unlock.UNLOCK, unlockNeed(ToolCall("send_sms", mapOf("app" to "messenger")), true, true))
        assertEquals(Unlock.UNLOCK, unlockNeed(ToolCall("call", mapOf("app" to "whatsapp")), true, true))
    }

    @Test fun openAppNowNeedsVoiceIdLikeAnyOtherScreenTool() {
        assertEquals(Unlock.VOICE_ID_OFF, unlockNeed(ToolCall("open_app", mapOf("app" to "youtube")), true, null))
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
