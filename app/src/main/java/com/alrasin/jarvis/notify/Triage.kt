package com.alrasin.jarvis.notify

/** Notification classifier (PLAN 7.2). Pure Kotlin so it is unit tested. */
object Triage {
    data class Verdict(val important: Boolean, val reason: String)

    private val CHAT_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.android.mms", "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.viber.voip",
        "com.imo.android.imoim", "com.google.android.gm", "com.microsoft.teams", "com.Slack", "com.discord",
    )
    private val NOISY_APPS = setOf(
        "com.facebook.katana", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.daraz.android", "com.alibaba.aliexpresshd", "com.amazon.mShopAndroidShopping", "com.miui.securitycenter",
        "com.miui.cleanmaster", "com.mi.globalbrowser", "com.android.vending", "com.google.android.youtube", "com.twitter.android",
        "com.linkedin.android", "com.foodpanda.android", "com.pathao.user", "com.snapchat.android",
    )
    private val KEEP_CATEGORIES = setOf("call", "msg", "email", "alarm", "reminder", "event", "alert")
    private val IMPORTANT_WORDS = Regex(
        "\\b(otp|verification code|one.time|passcode|pin|tk ?\\d|bdt|debited|credited|transaction|payment|bkash|nagad|rocket|" +
            "missed call|calling|urgent|security|sign.in|login attempt|delivery|delivered|order shipped|flight|appointment|interview)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PROMO_WORDS = Regex(
        "\\b(offer|sale|discount|\\d+% off|cashback|deal|coupon|voucher|promo|win|free|limited time|subscribe|recommended|" +
            "trending|you might like|new video|live now|suggested|don't miss|flash)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** [rule]: user rule for this app ("keep" / "clear") — always wins. */
    fun classify(pkg: String, title: String, text: String, category: String?, rule: String?): Verdict {
        when (rule) {
            "keep" -> return Verdict(true, "your rule")
            "clear" -> return Verdict(false, "your rule")
        }
        val all = "$title $text"
        if (IMPORTANT_WORDS.containsMatchIn(all)) return Verdict(true, "important words")
        if (category in KEEP_CATEGORIES) return Verdict(true, "category $category")
        if (pkg in CHAT_APPS) return Verdict(true, "message app")
        if (PROMO_WORDS.containsMatchIn(all)) return Verdict(false, "promo words")
        if (pkg in NOISY_APPS) return Verdict(false, "noisy app")
        return Verdict(true, "default keep")
    }
}
