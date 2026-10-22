package com.alrasin.jarvis.voice

/**
 * The command audio starts a bit before the wake (so the first command word is not lost), so STT text
 * often begins with the wake phrase ("hey jimmy turn on …"). This removes it.
 */
object WakePhrase {
    private const val SEPARATOR = "[\\s\\p{P}]+"
    private const val NAMES = "jimi|jimmy|jimmie|jimmi|gymmy|zimmy|zumi|zoomy|azumi|hazumi|yumi|izumi|hayzumi|zooming"
    private val prefix = Regex(
        "^[\\s\\p{P}]*(?:(?:hey|hay|hi|a)$SEPARATOR(?:$NAMES|jim|jamie|gym)|" +
            "(?:hazy|gym)$SEPARATOR(?:me|mi)|$NAMES)(?![\\p{L}\\p{N}'’])[\\s\\p{P}]*",
        RegexOption.IGNORE_CASE,
    )

    fun strip(text: String): String {
        // Consume one exact prefix: later words may be a contact name or part of the command.
        return text.replaceFirst(prefix, "").trim()
    }
}
