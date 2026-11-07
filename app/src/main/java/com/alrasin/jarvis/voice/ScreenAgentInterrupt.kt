package com.alrasin.jarvis.voice

import com.alrasin.jarvis.core.TextNorm

class ScreenAgentInterrupt {
    private var candidate: String? = null
    private var candidateSince = 0L
    private var speechWasBusy = false
    private var echoUntil = Long.MIN_VALUE

    fun canListen(agentRunning: Boolean, speechBusy: Boolean, nowMs: Long): Boolean {
        if (speechWasBusy && !speechBusy) echoUntil = nowMs + 300L
        speechWasBusy = speechBusy
        val allowed = agentRunning && !speechBusy && nowMs >= echoUntil
        if (!allowed) reset()
        return allowed
    }

    fun accept(text: String, endpoint: Boolean, nowMs: Long): String? {
        val normalized = normalize(text)
        val command = phrase(normalized)
        if (command == null) {
            reset()
            return null
        }
        if (candidate != normalized) {
            candidate = normalized
            candidateSince = nowMs
        }
        if (!endpoint && nowMs - candidateSince < 240L) return null
        reset()
        return command
    }

    fun reset() {
        candidate = null
        candidateSince = 0L
    }

    companion object {
        private val command = Regex("(?:(?:hey )?(?:jimi|jimmy) |hey )?(stop|cancel|panic)")
        private val punctuation = Regex("[\\p{Punct}\\p{P}]+")

        fun phrase(text: String): String? = command.matchEntire(normalize(text))?.groupValues?.get(1)

        private fun normalize(text: String): String = TextNorm.squash(text.replace(punctuation, " "))
    }
}
