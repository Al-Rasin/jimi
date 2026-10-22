package com.alrasin.jarvis.voice

import kotlin.math.max
import kotlin.math.sqrt

/** Endpoint timing uses captured audio time, independent of decoder or UI scheduling. */
class CaptureEndpoint(
    waitStartMillis: Long = 7_000,
    private val maxMillis: Long = 20_000,
    private val sampleRate: Int = 16_000,
) {
    enum class FinishReason { SILENCE, LISTENING_TIMEOUT, MAX_LENGTH }

    private val waitSamples = waitStartMillis * sampleRate / 1_000
    private var samples = 0L
    private var quietSamples = 0L
    private var lastVoiceSample = Long.MIN_VALUE
    private var previousText = ""
    private var awaitingDecoder = false
    var finishReason: FinishReason? = null
        private set

    fun accept(count: Int, text: String, speechActive: Boolean): FinishReason? {
        require(count >= 0)
        if (finishReason != null) return finishReason
        samples += count
        if (speechActive) lastVoiceSample = samples
        val changed = text != previousText
        previousText = text
        quietSamples = if (speechActive || changed) 0 else quietSamples + count
        finishReason = when {
            samples >= maxMillis * sampleRate / 1_000 -> FinishReason.MAX_LENGTH
            text.isBlank() -> {
                // Give speech starting at the deadline one bounded decoder window.
                if (samples >= waitSamples && lastVoiceSample >= waitSamples - sampleRate / 2) {
                    awaitingDecoder = true
                }
                val deadline = waitSamples + if (awaitingDecoder) 1_200L * sampleRate / 1_000 else 0
                if (samples >= deadline) FinishReason.LISTENING_TIMEOUT else null
            }
            quietSamples >= silenceMillis(text) * sampleRate / 1_000 -> FinishReason.SILENCE
            else -> null
        }
        return finishReason
    }

    companion object {
        private val INCOMPLETE_ENDINGS = setOf(
            "and", "or", "but", "because", "then", "to", "the", "a", "an", "for", "with",
            "from", "of", "at", "in", "my", "your", "that", "which", "is", "are", "was",
            "call", "open", "play", "send", "set", "remind", "turn", "text", "message",
            "search", "find", "tell", "what", "how",
        )

        fun silenceMillis(text: String): Long {
            val words = text.lowercase().trim().split(Regex("\\s+"))
            val last = words.last().trimEnd('.', ',', '?', '!')
            return when {
                last in INCOMPLETE_ENDINGS || text.trim().lowercase() in setOf("turn on", "turn off") -> 1_600
                words.size <= 8 -> 700
                else -> 1_200
            }
        }
    }
}

/** Small energy VAD; quiet or stationary frames adapt the floor without another speech model. */
class CaptureVoiceActivity {
    private var noiseFloor = 0.002
    private val recentEnergy = DoubleArray(50)
    private var energyFrames = 0
    private var energyPosition = 0

    fun hasSpeech(chunk: ShortArray, count: Int = chunk.size): Boolean {
        require(count in 0..chunk.size)
        var active = false
        var offset = 0
        while (offset < count) {
            val end = minOf(offset + 320, count)
            var sum = 0.0
            var squareSum = 0.0
            for (i in offset until end) {
                val value = chunk[i] / 32768.0
                sum += value
                squareSum += value * value
            }
            val mean = sum / (end - offset)
            val rms = sqrt(max(0.0, squareSum / (end - offset) - mean * mean))
            recentEnergy[energyPosition] = rms
            energyPosition = (energyPosition + 1) % recentEnergy.size
            energyFrames = minOf(energyFrames + 1, recentEnergy.size)
            if (energyFrames == recentEnergy.size) {
                val low = recentEnergy.minOrNull()!!
                val high = recentEnergy.maxOrNull()!!
                // A stationary fan can start above the initial floor. Speech normally has pauses
                // or a changing energy envelope; require a full stable second before adapting up.
                if (low > noiseFloor && high <= low * 1.4) noiseFloor = low
            }
            val speech = rms > max(0.004, noiseFloor * 3)
            if (!speech) noiseFloor = noiseFloor * 0.95 + rms * 0.05
            active = active || speech
            offset = end
        }
        return active
    }
}
