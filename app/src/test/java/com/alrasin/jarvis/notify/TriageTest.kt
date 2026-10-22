package com.alrasin.jarvis.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageTest {
    private fun keep(pkg: String, title: String, text: String = "", category: String? = null, rule: String? = null) =
        Triage.classify(pkg, title, text, category, rule).important

    @Test fun otpIsImportantEvenFromNoisyApp() = assertTrue(keep("com.facebook.katana", "Your OTP is 123456"))

    @Test fun bkashPaymentIsImportant() = assertTrue(keep("com.bKash.customerapp", "Payment", "Tk 500 debited"))

    @Test fun chatAppIsImportant() = assertTrue(keep("com.whatsapp", "Rahim", "Where are you?"))

    @Test fun promoIsNoise() = assertFalse(keep("com.daraz.android", "Flash sale", "50% off today", category = "promo"))

    @Test fun socialAppWithoutMessageMetadataIsKept() = assertTrue(keep("com.instagram.android", "New post", "See what's new"))

    @Test fun callCategoryKept() = assertTrue(keep("com.unknown.app", "Incoming", category = "call"))

    @Test fun keepRuleWinsAndClearRuleCannotOverrideOtp() {
        assertTrue(keep("com.instagram.android", "New post", rule = "keep"))
        assertTrue(keep("com.whatsapp", "Rahim", "OTP 1234", rule = "clear"))
    }

    @Test fun emptyUnknownNotificationIsKeptByDefault() {
        assertEquals(Triage.Verdict(true, "default keep"), Triage.classify("", "", "", null, null))
    }

    @Test fun importantWordsIgnoreCaseAndPunctuationInTitleOrBody() {
        for (message in listOf("[oTp]: 123456!", "VeRiFiCaTiOn CoDe: 1234", "One-time code", "SIGN-IN detected", "URGENT!")) {
            for ((title, body) in listOf(message to "", "Update" to message)) {
                assertEquals(message, Triage.Verdict(true, "important words"),
                    Triage.classify("com.instagram.android", title, body, null, null))
            }
        }
    }

    @Test fun importantWordsWinOverPromotions() {
        assertEquals(Triage.Verdict(true, "important words"),
            Triage.classify("com.daraz.android", "SALE!", "Your order shipped", null, null))
    }

    @Test fun protectedCategoriesWinOverNoisyAppsAndPromotions() {
        for (category in listOf("call", "msg", "email", "alarm", "reminder", "event", "alert")) {
            assertEquals(category, Triage.Verdict(true, "category $category"),
                Triage.classify("com.instagram.android", "SALE!", "", category, null))
        }
    }

    @Test fun chatAppsAreKeptEvenWhenMessageContainsPromotionalWords() {
        for (pkg in listOf("com.whatsapp.w4b", "org.telegram.messenger", "com.Slack", "com.google.android.gm")) {
            assertEquals(pkg, Triage.Verdict(true, "message app"),
                Triage.classify(pkg, "Are you free?", "", null, null))
        }
    }

    @Test fun promotionMatchingIgnoresCaseAndPunctuation() {
        for (message in listOf("50% OFF!", "[CoUpOn]", "Limited Time!", "You might like this", "Discount: fifty percent")) {
            assertEquals(message, Triage.Verdict(false, "promo words"),
                Triage.classify("com.daraz.android", "News", message, "promo", null))
        }
    }

    @Test fun wordsInsideLongerWordsDoNotTriggerRules() {
        assertEquals(Triage.Verdict(true, "possible personal message"),
            Triage.classify("com.instagram.android", "Spinning rockets", "", null, null))
        assertEquals(Triage.Verdict(true, "default keep"),
            Triage.classify("com.unknown.app", "Window freezer", "", null, null))
    }

    @Test fun explicitClearRuleCannotOverrideProtectedSignals() {
        assertEquals(Triage.Verdict(true, "important words"),
            Triage.classify("com.whatsapp", "URGENT OTP", "", "call", "clear"))
        assertEquals(Triage.Verdict(true, "your rule"),
            Triage.classify("com.instagram.android", "Flash sale", "", null, "keep"))
    }

    @Test fun unknownRuleAndCategoryFallThroughToNormalClassification() {
        assertEquals(Triage.Verdict(true, "possible personal message"),
            Triage.classify("com.instagram.android", "A new photo", "", "status", "automatic"))
    }

    @Test fun multiDigitTakaAmountsAreKeptEvenFromNoisyApps() {
        for (amount in listOf("Tk 5", "Tk 50", "Tk 500", "TK5000", "tk 500.25")) {
            assertEquals(amount, Triage.Verdict(true, "important words"),
                Triage.classify("com.instagram.android", "Balance", amount, null, null))
        }
    }
}
