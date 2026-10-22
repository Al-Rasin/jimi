package com.alrasin.jarvis.notify

import com.alrasin.jarvis.core.Json
import java.io.File
import kotlin.math.exp

/** Offline logistic regression; feature version 1 matches training/notifications/features.py. */
class NotificationModel private constructor(
    private val weights: Map<String, Double>?,
    private val bias: Double = 0.0,
    private val threshold: Double = 0.5,
) {
    data class Input(
        val pkg: String,
        val title: String,
        val text: String,
        val timeMs: Long,
        val isConversation: Boolean = false,
        val isGroupConversation: Boolean = false,
    )

    val isLoaded: Boolean get() = weights != null

    /** Null means no usable model, not a measured probability of zero. */
    fun predictKeepProbability(input: Input): Double? {
        val activeWeights = weights ?: return null
        val score = features(input).fold(bias) { sum, feature -> sum + (activeWeights[feature] ?: 0.0) }
        if (score.isNaN()) return null
        return if (score >= 0) 1.0 / (1.0 + exp(-score)) else exp(score) / (1.0 + exp(score))
    }

    fun classify(input: Input, category: String? = null, rule: String? = null): Triage.Verdict {
        if (rule == "keep" || rule == "clear") {
            return Triage.classify(input.pkg, input.title, input.text, category, rule)
        }
        val probability = predictKeepProbability(input)
            ?: return Triage.classify(input.pkg, input.title, input.text, category, rule)
        return Triage.Verdict(probability >= threshold, "learned notification preference")
    }

    /** Until real-label evaluation, learned preferences may keep more notifications but never clear more. */
    fun classifyConservatively(
        input: Input,
        category: String? = null,
        rule: String? = null,
        fullText: String = input.text,
    ): Triage.Verdict {
        val baseline = Triage.classify(input.pkg, input.title, fullText, category, rule)
        if (!isLoaded || baseline.important || rule == "clear") return baseline
        val learned = classify(input, category, rule)
        return if (learned.important) learned else baseline
    }

    companion object {
        private val tokenPattern = Regex("[a-z0-9]+")
        private val otpWords = setOf("otp", "passcode", "pin")
        private const val MAX_MODEL_BYTES = 2 * 1024 * 1024

        fun load(file: File?): NotificationModel = runCatching {
            require(file != null && file.isFile && file.length() <= MAX_MODEL_BYTES)
            fromJson(file.readText(Charsets.UTF_8))
        }.getOrElse { NotificationModel(null) }

        fun fromJson(json: String?): NotificationModel = runCatching {
            require(json != null && json.length <= MAX_MODEL_BYTES)
            val root = Json.parse(json) as? Map<*, *> ?: error("Expected model object")
            require((root["version"] as? Number)?.toDouble() == 1.0)
            require((root["feature_version"] as? Number)?.toDouble() == 1.0)
            val bias = finiteNumber(root["bias"])
            val threshold = finiteNumber(root["threshold"])
            require(threshold > 0.0 && threshold < 1.0)
            val rawWeights = root["weights"] as? Map<*, *> ?: error("Expected weights object")
            val weights = rawWeights.entries.associate { (key, value) ->
                require(key is String && key.isNotEmpty())
                key to finiteNumber(value)
            }
            NotificationModel(weights, bias, threshold)
        }.getOrElse { NotificationModel(null) }

        /** Binary ASCII tokens and UTC hour; supply the same text stored in notification_log. */
        fun features(input: Input): Set<String> {
            val titleTokens = tokens(input.title)
            val textTokens = tokens(input.text)
            val allTokens = titleTokens + textTokens
            return sortedSetOf<String>().apply {
                add("pkg:${asciiLower(input.pkg)}")
                add("hour:${Math.floorMod(Math.floorDiv(input.timeMs, 3_600_000L), 24L)}")
                titleTokens.forEach { add("title:$it") }
                textTokens.forEach { add("text:$it") }
                if ((input.title + input.text).any { it in '0'..'9' }) add("has_digits")
                if (allTokens.any { it in otpWords } || allTokens.zipWithNext().any { (a, b) ->
                        (a == "verification" && b == "code") || (a == "one" && b == "time")
                    }) add("has_otp")
                if (input.isConversation) add("is_conversation")
                if (input.isGroupConversation) add("is_group_conversation")
            }
        }

        private fun tokens(text: String): List<String> = tokenPattern.findAll(asciiLower(text)).map { it.value }.toList()

        private fun asciiLower(text: String): String = buildString(text.length) {
            text.forEach { append(if (it in 'A'..'Z') it + 32 else it) }
        }

        private fun finiteNumber(value: Any?): Double {
            val number = (value as? Number)?.toDouble() ?: error("Expected number")
            require(number.isFinite())
            return number
        }
    }
}
