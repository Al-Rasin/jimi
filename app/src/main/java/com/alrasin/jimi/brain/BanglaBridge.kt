package com.alrasin.jimi.brain

import com.alrasin.jimi.core.TextNorm

/**
 * Bangla in → Bangla out (D-093). Pure helpers; ML Kit itself is called by the service.
 * Short answers to a yes/no question are mapped here, not by ML Kit: a one-word "না" can come back as "Not"
 * (or nothing when the pack is missing), and then a confirmation would not see "no".
 */
object BanglaBridge {
    private val QUICK: Map<String, String> = buildMap {
        listOf("হ্যাঁ", "হ্যা", "হাঁ", "হা", "জি", "জ্বি", "জী", "জি হ্যাঁ", "হ্যাঁ হ্যাঁ", "হুম", "ঠিক আছে", "আচ্ছা",
            "অবশ্যই", "হ্যাঁ করো", "হ্যাঁ পাঠাও").forEach { put(it, "yes") }
        listOf("না", "নাহ", "না না", "না থাক", "থাক", "লাগবে না", "দরকার নেই", "বাতিল", "বাতিল করো", "বাদ দাও")
            .forEach { put(it, "no") }
        listOf("থামো", "থাম", "থামাও", "চুপ", "চুপ করো").forEach { put(it, "stop") }
    }
    private val PUNCT = Regex("[\\u0964\\u0965.,!?;:'\"\\u200c\\u200d]")
    private val WAKE = Regex("^(জিমি|jimi)\\s+|\\s+(জিমি|jimi)$")

    /** "হ্যাঁ।" → "yes", "না জিমি" → "no"; null when it is not one of the short answers. */
    fun quickEnglish(text: String): String? {
        val t = text.lowercase().replace(PUNCT, " ").replace(TextNorm.SPACES, " ").trim().replace(WAKE, "").trim()
        return QUICK[t]
    }
}
