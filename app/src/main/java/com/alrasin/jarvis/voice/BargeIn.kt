package com.alrasin.jarvis.voice

object BargeIn {
    private const val QUIET_PLAYBACK_RMS = 0.02f
    private val interruption = Regex("^[\\p{P}\\s]*(stop|wait)[\\p{P}\\s]*$", RegexOption.IGNORE_CASE)
    private val leadingInterruption = Regex("^(stop|wait)(?=$|[\\s,.!?:;])[\\s,.!?:;]*", RegexOption.IGNORE_CASE)
    private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

    fun wakeThreshold(normal: Float): Float {
        if (!normal.isFinite()) return 1f
        return maxOf(normal, (normal + 0.15f).coerceIn(0.85f, 0.98f)).coerceAtMost(1f)
    }

    fun isInterruption(text: String): Boolean = interruption.matches(text)

    fun isPlaybackEcho(candidate: String, playbackText: String): Boolean {
        val phrase = wordSeparator.replace(candidate.lowercase(), " ").trim()
        if (phrase.isEmpty()) return false
        val playback = wordSeparator.replace(playbackText.lowercase(), " ").trim()
        return " $playback ".contains(" $phrase ")
    }

    // Energy is playback PCM RMS in [0, 1], not microphone energy (which includes the user).
    fun canRecognizeStop(echoCancelerEnabled: Boolean, playbackEnergy: Float): Boolean =
        echoCancelerEnabled || (playbackEnergy.isFinite() && playbackEnergy in 0f..QUIET_PLAYBACK_RMS)

    fun stripInterruption(text: String): String = leadingInterruption.replaceFirst(text.trim(), "")
}
