package com.alrasin.jarvis.voice

/**
 * The command audio starts a bit before the wake (so the first command word is not lost), so STT text
 * often begins with the wake phrase ("hey jimmy turn on …"). This removes it.
 */
object WakePhrase {
    private const val SEPARATOR = "[\\s\\p{P}]+"
    private const val NAMES = "jimi|jimmy|jimmie|jimmi|gymmy|zimmy|zumi|zoomy|azumi|hazumi|yumi|izumi|hayzumi|zooming|hizumi|hazyme|asuming"
    private val prefix = Regex(
        "^[\\s\\p{P}]*(?:(?:hey|hay|hi|hello|okay|ok|a)$SEPARATOR(?:$NAMES|jim|jamie|jenny|genie|gimme|gimmie|gym|mi|yarn|see${SEPARATOR}me|jee${SEPARATOR}mee)|" +
            "(?:hazy|gym)$SEPARATOR(?:me|mi)|" +
            "(?:as${SEPARATOR}you${SEPARATOR}(?:me|may)|as${SEPARATOR}well|asium${SEPARATOR}me|his${SEPARATOR}re)(?=$SEPARATOR(?:what|whats|set|wake|alarm|turn|down|open|go|tell|show|play)\\b)|$NAMES)(?![\\p{L}\\p{N}'’])[\\s\\p{P}]*",
        RegexOption.IGNORE_CASE,
    )

    fun strip(text: String): String {
        // Consume one exact prefix: later words may be a contact name or part of the command.
        return text.replaceFirst(prefix, "").trim()
    }

    /** True when [text] is only the wake phrase ("hey jimmy"): the user wants a new command, not a reply. */
    fun isOnlyWake(text: String): Boolean = text.isNotBlank() && strip(text).isEmpty()

    private val wordSplit = Regex(SEPARATOR)
    private val greetings = setOf("hey", "hay", "hi", "hello", "ok", "okay", "a")

    /**
     * True while the decoder has only the start of the wake ("hey", "hey ji"): not a command yet, so the capture
     * must not end on silence here.
     */
    fun mayBeWakeStart(text: String): Boolean {
        val words = text.trim().split(wordSplit).filter { it.isNotEmpty() }
        return words.size in 1..2 && words.first().lowercase() in greetings
    }
}
