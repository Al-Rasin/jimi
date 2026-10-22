package com.alrasin.jarvis.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageTest {
    private fun keep(pkg: String, title: String, text: String = "", category: String? = null, rule: String? = null) =
        Triage.classify(pkg, title, text, category, rule).important

    @Test fun otpIsImportantEvenFromNoisyApp() = assertTrue(keep("com.facebook.katana", "Your OTP is 123456"))

    @Test fun bkashPaymentIsImportant() = assertTrue(keep("com.bKash.customerapp", "Payment", "Tk 500 debited"))

    @Test fun chatAppIsImportant() = assertTrue(keep("com.whatsapp", "Rahim", "Where are you?"))

    @Test fun promoIsNoise() = assertFalse(keep("com.daraz.android", "Flash sale", "50% off today"))

    @Test fun noisyAppIsNoise() = assertFalse(keep("com.instagram.android", "New post", "See what's new"))

    @Test fun callCategoryKept() = assertTrue(keep("com.unknown.app", "Incoming", category = "call"))

    @Test fun userRuleWins() {
        assertTrue(keep("com.instagram.android", "New post", rule = "keep"))
        assertFalse(keep("com.whatsapp", "Rahim", "OTP 1234", rule = "clear"))
    }
}
