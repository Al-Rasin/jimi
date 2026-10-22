package com.alrasin.jarvis.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTrustTriageTest {
    private val commerce = "com.daraz.android"

    private fun classify(
        text: String,
        pkg: String = commerce,
        category: String? = "promo",
        rule: String? = "clear",
    ) = Triage.classify(pkg, "Flash sale", text, category, rule)

    @Test fun codesOverrideEveryClearRule() {
        for (rule in listOf(null, "clear", "keep", "unknown")) {
            for (text in listOf(
                "OTP 123456", "Verification-code: 1234", "One time password", "PIN required",
                "Passcode", "Authentication required", "Your code is ready", "Your number: 987654",
            )) assertTrue("$rule: $text", classify(text, rule = rule).important)
        }
    }

    @Test fun bankContentAlwaysWinsOverPromotions() {
        for (text in listOf(
            "Your bank offer", "Your balance changed", "Account update", "Tk 500 debited",
            "BDT credited", "Payment received", "Transaction complete", "Transfer failed",
            "Withdrawal complete", "Deposit received", "bKash update", "Nagad update", "Rocket update",
        )) assertTrue(text, classify(text).important)
    }

    @Test fun bankAppWithGenericTextIsProtected() {
        for (pkg in listOf(
            "com.bKash.customerapp", "com.konasl.nagad", "com.dbbl.mobile", "com.example.bank",
            "com.chase.sig.android", "com.paypal.android.p2pmobile", "com.revolut.revolut", "com.cellfin.app",
        )) assertTrue(pkg, classify("Discount available", pkg).important)
    }

    @Test fun callContentAlwaysWinsOverPromotions() {
        for (text in listOf("Incoming call", "Missed call", "Call from friend", "Calling", "New voicemail")) {
            assertTrue(text, classify(text).important)
        }
    }

    @Test fun protectedCategoriesOverrideClearRules() {
        for (category in listOf("call", "msg", "email", "alarm", "reminder", "event", "alert")) {
            assertTrue(category, classify("Coupon", category = category).important)
        }
    }

    @Test fun messagingAppsOverrideClearRules() {
        for (pkg in listOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
            "com.google.android.apps.messaging", "com.android.mms", "com.android.dialer",
            "com.google.android.dialer", "com.android.incallui", "com.viber.voip", "com.imo.android.imoim",
            "com.google.android.gm", "com.microsoft.teams", "com.Slack", "com.discord",
            "org.thoughtcrime.securesms", "com.samsung.android.messaging", "com.android.contacts",
        )) assertTrue(pkg, classify("Are you free?", pkg).important)
    }

    @Test fun socialAppsWithMissingMessageMetadataStay() {
        for (pkg in listOf(
            "com.instagram.android", "com.facebook.katana", "com.snapchat.android", "com.twitter.android",
            "com.linkedin.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        )) assertTrue(pkg, classify("Great deal, are you free?", pkg).important)
    }

    @Test fun peopleMetadataProtectsEvenCommercialPromoText() {
        assertTrue(Triage.classify(commerce, "Sale", "Offer", "promo", "clear", hasPeople = true).important)
    }

    @Test fun messagingStyleProtectsEvenCommercialPromoText() {
        assertTrue(Triage.classify(commerce, "Sale", "Offer", "promo", "clear", isMessagingStyle = true).important)
    }

    @Test fun conversationMetadataProtectsEvenCommercialPromoText() {
        assertTrue(Triage.classify(commerce, "Sale", "Offer", "promo", "clear", isConversation = true).important)
    }

    @Test fun unknownAppsAreUncertainEvenWithPromoWordsAndClearRule() {
        for (rule in listOf(null, "clear", "keep")) {
            for (text in listOf("I am free", "Here is the discount", "Sale", "", "Offer")) {
                assertTrue("$rule: $text", classify(text, "com.unknown.chat", rule = rule).important)
            }
        }
    }

    @Test fun emptyAndUnrecognizedCommercialAlertsStay() {
        for (rule in listOf(null, "clear")) {
            for (text in listOf("", "Update", "A person replied", "Something happened")) {
                assertTrue(Triage.classify(commerce, "", text, null, rule).important)
            }
        }
    }

    @Test fun recognizedCommercialPromotionCanBeCleared() {
        for (pkg in listOf(
            commerce, "com.alibaba.aliexpresshd", "com.amazon.mShopAndroidShopping",
            "com.android.vending", "com.foodpanda.android", "com.pathao.user",
        )) assertFalse(pkg, classify("50% off today", pkg, rule = null).important)
    }

    @Test fun commercialHumanMessagesWithoutPromoCategoryStay() {
        for (text in listOf("Free tonight?", "Deal?", "Discount as we discussed", "I have an offer")) {
            for (category in listOf(null, "msg", "status", "unknown")) {
                for (rule in listOf(null, "clear")) {
                    assertTrue("$category $rule $text", classify(text, category = category, rule = rule).important)
                }
            }
        }
    }

    @Test fun explicitKeepRuleProtectsCommercialPromotion() {
        assertEquals(Triage.Verdict(true, "your rule"), classify("50% off today", rule = "keep"))
    }

    @Test fun explicitClearRuleReasonIsPreservedForEligiblePromo() {
        assertEquals(Triage.Verdict(false, "your rule"), classify("50% off today"))
    }

    @Test fun everyVerdictHasExplanation() {
        for (verdict in listOf(
            classify("OTP"), classify("Offer"), classify("Offer", rule = "keep"),
            classify("Hello", "com.unknown"), classify("Hello", "com.whatsapp"),
        )) assertTrue(verdict.reason.isNotBlank())
    }

    @Test fun packageCaseDoesNotWeakenProtection() {
        assertTrue(classify("Offer", "COM.WHATSAPP").important)
        assertTrue(classify("Offer", "COM.BKASH.CUSTOMERAPP").important)
        assertTrue(classify("Offer", "COM.INSTAGRAM.ANDROID").important)
    }

    @Test fun learnedModelCannotClearProtectedOrUnknownNotification() {
        val model = discardEverythingModel()
        for ((pkg, text) in listOf(
            commerce to "OTP 1234", commerce to "Bank alert", commerce to "Call from friend",
            "com.whatsapp" to "Free today?", "com.instagram.android" to "Sale",
            "com.unknown" to "Offer", "com.bKash.customerapp" to "Offer",
        )) {
            for (rule in listOf(null, "clear")) {
                assertTrue("$pkg $text", model.classify(input(pkg, text), category = "promo", rule = rule).important)
            }
        }
    }

    @Test fun learnedModelCannotClearConversationOrGroupConversation() {
        val model = discardEverythingModel()
        assertTrue(model.classify(input(commerce, "Offer").copy(isConversation = true), category = "promo").important)
        assertTrue(model.classify(input(commerce, "Offer").copy(isGroupConversation = true), category = "promo").important)
    }

    @Test fun learnedModelCannotClearProtectedCategory() {
        val model = discardEverythingModel()
        for (category in listOf("call", "msg", "email", "alarm")) {
            assertTrue(category, model.classify(input(commerce, "Offer"), category, "clear").important)
        }
    }

    @Test fun missingModelStillHonorsConversationMetadata() {
        assertTrue(NotificationModel.load(null).classify(input(commerce, "Offer").copy(isConversation = true), category = "promo").important)
    }

    @Test fun learnedModelCanKeepAnOtherwiseClearablePromo() {
        val model = NotificationModel.fromJson(
            """{"version":1,"feature_version":1,"bias":1000,"threshold":0.5,"weights":{}}""",
        )
        assertTrue(model.classify(input(commerce, "Offer"), category = "promo").important)
        assertFalse(model.classify(input(commerce, "Offer"), category = "promo", rule = "clear").important)
    }

    private fun input(pkg: String, text: String) = NotificationModel.Input(pkg, "Update", text, 0)

    private fun discardEverythingModel() = NotificationModel.fromJson(
        """{"version":1,"feature_version":1,"bias":-1000,"threshold":0.5,"weights":{}}""",
    )
}
