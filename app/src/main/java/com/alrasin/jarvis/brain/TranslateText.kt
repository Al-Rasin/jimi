package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/** English ↔ Bangla translate helpers (ROADMAP #37). Pure Kotlin: unit tested. */
object TranslateText {
    const val EN = "en"
    const val BN = "bn"

    private val BENGALI = Regex("[\\u0980-\\u09FF]")

    fun hasBengali(text: String): Boolean = BENGALI.containsMatchIn(text)

    /** Spoken language name → code, or null. */
    fun lang(said: String?): String? = when (said?.trim()?.lowercase()) {
        "bangla", "bengali", "bn", "বাংলা" -> BN
        "english", "en", "ইংরেজি" -> EN
        else -> null
    }

    /** Target language: the one asked for, else the other side of what the text is written in. */
    fun target(text: String, said: String?): String = lang(said) ?: if (hasBengali(text)) EN else BN

    fun source(target: String): String = if (target == BN) EN else BN

    fun name(code: String): String = if (code == BN) "Bangla" else "English"

    /** Strip quotes and a trailing "please" from the spoken text to translate. */
    fun clean(text: String): String = text.trim()
        .removeSuffix(" please").trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trim()
}

/** RuleMatcher translate rules (ROADMAP #37): "translate good morning to bangla", "what does ধন্যবাদ mean in english". */
internal object TranslateRules {
    private const val LANGS = "bangla|bengali|english"
    private val TO_RE = Regex("^(?:please )?translate (.+?) (?:to|into|in) ($LANGS)$")
    private val PLAIN_RE = Regex("^(?:please )?translate (.+)$")
    private val SAY_RE = Regex("^how (?:do|would|can) (?:you|i|we) say (.+?) in ($LANGS)$")
    private val MEAN_RE = Regex("^what (?:does|is) (.+?) mean(?:s)? in ($LANGS)$")
    private val MEANING_RE = Regex("^what(?: is|'s) the (?:meaning of|($LANGS) (?:for|of)) (.+?)(?: in ($LANGS))?$")
    private val IN_RE = Regex("^what(?: is|'s) (.+?) in ($LANGS)$")
    private val SELF_RE = Regex("^(?:this|that|it|the screen|my screen)$")

    fun translate(t: String): ToolCall? {
        TO_RE.find(t)?.let { return call(it.groupValues[1], it.groupValues[2]) }
        SAY_RE.find(t)?.let { return call(it.groupValues[1], it.groupValues[2]) }
        MEAN_RE.find(t)?.let { return call(it.groupValues[1], it.groupValues[2]) }
        MEANING_RE.find(t)?.let { m ->
            val lang = m.groupValues[1].ifEmpty { m.groupValues[3] }.ifEmpty { null }
            if (lang == null && !TranslateText.hasBengali(m.groupValues[2])) return null // "meaning of life" → knowledge
            return call(m.groupValues[2], lang)
        }
        IN_RE.find(t)?.let { return call(it.groupValues[1], it.groupValues[2]) }
        PLAIN_RE.find(t)?.let { return call(it.groupValues[1], null) }
        return null
    }

    private fun call(text: String, lang: String?): ToolCall? {
        val words = TranslateText.clean(text)
        if (words.isEmpty() || words.matches(SELF_RE)) return null
        return RuleMatcher.call("translate", "text" to words, "to" to TranslateText.target(words, lang))
    }
}
