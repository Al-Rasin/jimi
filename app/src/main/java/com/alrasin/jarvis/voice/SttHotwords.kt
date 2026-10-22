package com.alrasin.jarvis.voice

import java.util.Locale

/** Validates tokenizer metadata before passing user vocabulary to the native recognizer. */
object SttHotwords {
    data class Prepared(val phrases: String = "", val reason: String) {
        val enabled: Boolean get() = phrases.isNotEmpty()
    }

    fun prepare(tokensText: String, bpeVocabText: String?, hotwords: Collection<String>): Prepared {
        if (hotwords.isEmpty()) return Prepared(reason = "no vocabulary")
        if (bpeVocabText.isNullOrBlank()) return Prepared(reason = "matching bpe.vocab missing")
        if (tokensText.length > MAX_METADATA || bpeVocabText.length > MAX_METADATA) {
            return Prepared(reason = "tokenizer metadata too large")
        }
        val tokens = linkedSetOf<String>()
        for (line in tokensText.lineSequence().filter { it.isNotBlank() }) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 2 || fields[1].toIntOrNull() == null || !tokens.add(fields[0])) {
                return Prepared(reason = "invalid token table")
            }
        }
        val pieces = linkedSetOf<String>()
        for (line in bpeVocabText.removeSuffix("\n").splitToSequence('\n')) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 2 || !fields[1].matches(SCORE) || fields[1].toFloatOrNull()?.isFinite() != true || !pieces.add(fields[0])) {
                return Prepared(reason = "invalid bpe.vocab")
            }
        }
        fun lexical(piece: String) = !piece.startsWith("<") && !piece.startsWith("#")
        if (pieces.filter(::lexical).toSet() != tokens.filter(::lexical).toSet() || "▁" !in pieces) {
            return Prepared(reason = "bpe.vocab does not match tokens.txt")
        }
        val upperCase = pieces.none { piece -> piece.any { it in 'a'..'z' } }
        val phrases = linkedSetOf<String>()
        for (hotword in hotwords) {
            if (hotword.length > MAX_PHRASE_LENGTH || hotword.any { it == '/' || it == ':' || it == '@' || it == '#' || it == '\n' || it == '\r' }) continue
            var phrase = hotword.trim().replace(Regex("\\s+"), " ")
            phrase = if (upperCase) phrase.uppercase(Locale.ROOT) else phrase.lowercase(Locale.ROOT)
            if (phrase.isEmpty() || phrase.any { it != ' ' && it.toString() !in pieces }) continue
            phrases.add(phrase)
            if (phrases.size == MAX_PHRASES) break
        }
        return if (phrases.isEmpty()) Prepared(reason = "no encodable hotwords")
        else Prepared(phrases.joinToString("/"), "${phrases.size} phrases")
    }

    const val MAX_PHRASES = 256
    private val SCORE = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private const val MAX_PHRASE_LENGTH = 80
    internal const val MAX_METADATA = 4 * 1024 * 1024
}
