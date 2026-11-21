package com.alrasin.jimi.voice

object BargeIn {
    private const val QUIET_PLAYBACK_RMS = 0.02f
    // Without the echo canceler two words are needed: a lone "stop"/"wait" was often Jimi's own voice (§M.11).
    // With it on, a lone word is safe enough (D-098); echo of the playing text is still rejected.
    private const val NAME = "(?:hey[\\s,]+)?(?:jimi|jimmy|jarvis)"
    private const val WORD = "(?:stop|wait)"
    private const val TAIL = "(?:stop|wait|jimi|jimmy|jarvis|please|now|talking|it)"
    private const val GAP = "[\\p{P}\\s]+"
    private val interruption = Regex(
        "^[\\p{P}\\s]*(?:$NAME$GAP$WORD|(?:ok|okay|please)$GAP$WORD|$WORD$GAP$TAIL)[\\p{P}\\s]*$",
        RegexOption.IGNORE_CASE,
    )
    private val soloInterruption = Regex(
        "^[\\p{P}\\s]*(?:$WORD|cancel|enough|quiet|be${GAP}quiet|shut${GAP}up)[\\p{P}\\s]*$",
        RegexOption.IGNORE_CASE,
    )
    private val leadingInterruption = Regex(
        "^(?:(?:$NAME|ok|okay|please)[\\s,.!?:;]+)?$WORD(?:[\\s,.!?:;]+$TAIL)?(?=$|[\\s,.!?:;])[\\s,.!?:;]*",
        RegexOption.IGNORE_CASE,
    )
    private val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

    // Was up to 0.98 (D-098): the user's real "Hey Jimi" scores ~0.9, so barge-in wake never fired.
    fun wakeThreshold(normal: Float): Float {
        if (!normal.isFinite()) return 1f
        return maxOf(normal, (normal + 0.05f).coerceIn(0.9f, 0.95f)).coerceAtMost(1f)
    }

    fun isInterruption(text: String, echoCanceled: Boolean = false): Boolean =
        interruption.matches(text) || (echoCanceled && soloInterruption.matches(text))

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
