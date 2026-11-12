package com.alrasin.jarvis.notify

/** Notification classifier (PLAN 7.2). Pure Kotlin so it is unit tested. */
object Triage {
    data class Verdict(val important: Boolean, val reason: String)

    private val CHAT_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.android.mms", "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.viber.voip",
        "com.imo.android.imoim", "com.google.android.gm", "com.microsoft.teams", "com.slack", "com.discord",
        "org.thoughtcrime.securesms", "com.samsung.android.messaging", "com.android.contacts",
    )
    private val SOCIAL_APPS = setOf(
        "com.facebook.katana", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.twitter.android", "com.linkedin.android", "com.snapchat.android",
    )
    private val COMMERCIAL_APPS = setOf(
        "com.daraz.android", "com.alibaba.aliexpresshd", "com.amazon.mshop.android.shopping",
        "com.amazon.mshopandroidshopping", "com.android.vending", "com.foodpanda.android", "com.pathao.user",
    )
    private val BANK_APP = Regex(
        "bank|bkash|nagad|rocket|dbbl|upay|paypal|venmo|revolut|wise|chase|capitalone|citytouch|cellfin|surecash",
        RegexOption.IGNORE_CASE,
    )
    private val POSSIBLE_CODE = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val KEEP_CATEGORIES = setOf("call", "msg", "email", "alarm", "reminder", "event", "alert")
    private val IMPORTANT_WORDS = Regex(
        "\\b(otp|verification[ -]+code|one.time|passcode|pin|authentication|verification|code|bank|balance|account|" +
            "tk ?\\d+|bdt|debited|credited|transaction|payment|transfer|withdrawal|deposit|bkash|nagad|rocket|" +
            "call|calling|voicemail|urgent|security|sign.in|login attempt|delivery|delivered|order shipped|flight|appointment|interview)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PROMO_WORDS = Regex(
        "\\b(offer|sale|discount|\\d+% off|cashback|deal|coupon|voucher|promo|win|free|limited time|subscribe|recommended|" +
            "trending|you might like|new video|live now|suggested|don't miss|flash)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Chat or mail app: its notification title is usually the sender. */
    fun isChatApp(pkg: String) = pkg.lowercase(java.util.Locale.ROOT) in CHAT_APPS

    /** Bank or payment app (money features are out of scope, D-072). */
    fun isMoneyApp(pkg: String) = BANK_APP.containsMatchIn(pkg)

    /** Clear rules cannot override protected notifications or possible personal messages. */
    fun classify(
        pkg: String,
        title: String,
        text: String,
        category: String?,
        rule: String?,
        hasPeople: Boolean = false,
        isMessagingStyle: Boolean = false,
        isConversation: Boolean = false,
    ): Verdict {
        if (rule == "keep") return Verdict(true, "your rule")
        val all = "$title $text"
        if (IMPORTANT_WORDS.containsMatchIn(all)) return Verdict(true, "important words")
        if (POSSIBLE_CODE.containsMatchIn(all)) return Verdict(true, "possible verification code")
        if (category in KEEP_CATEGORIES) return Verdict(true, "category $category")
        if (hasPeople || isMessagingStyle || isConversation) return Verdict(true, "personal message metadata")
        val normalizedPkg = pkg.lowercase(java.util.Locale.ROOT)
        if (BANK_APP.containsMatchIn(normalizedPkg)) return Verdict(true, "bank or payment app")
        if (normalizedPkg in CHAT_APPS) return Verdict(true, "message app")
        if (normalizedPkg in SOCIAL_APPS) return Verdict(true, "possible personal message")
        if (category == "promo" && normalizedPkg in COMMERCIAL_APPS && PROMO_WORDS.containsMatchIn(all)) {
            return Verdict(false, if (rule == "clear") "your rule" else "promo words")
        }
        return Verdict(true, "default keep")
    }
}
