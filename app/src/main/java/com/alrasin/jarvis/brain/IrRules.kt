package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.IrCodes
import com.alrasin.jarvis.core.ToolCall

/** RuleMatcher TV / AC rules (ROADMAP #39): "tv off", "volume up on the tv", "turn on the ac". */
internal object IrRules {
    private val TV_RE = Regex("\\b(?:tv|television|telly)\\b")
    private val AC_RE = Regex("\\b(?:ac|aircon|air con|air conditioner)\\b")
    private val QUESTION_RE = Regex("^(?:is|are|what|whats|which|how|why|when|where|who|does|did|can you tell)\\b")
    private val MEDIA_RE = Regex("\\b(?:play|watch|cast|show me|stream|youtube|netflix)\\b")
    private val TIMES_RE = Regex("\\b(?:by )?(\\d{1,2})(?: times| steps)?\\b")

    private val BUTTON_RES = listOf(
        IrCodes.VOL_UP to Regex("\\b(?:volume up|louder|turn (?:it |the tv |the volume )?up|increase (?:the )?volume|raise (?:the )?volume)\\b"),
        IrCodes.VOL_DOWN to Regex("\\b(?:volume down|quieter|softer|turn (?:it |the tv |the volume )?down|decrease (?:the )?volume|lower (?:the )?volume)\\b"),
        IrCodes.MUTE to Regex("\\b(?:mute|unmute)\\b"),
        IrCodes.CH_UP to Regex("\\b(?:channel up|next channel)\\b"),
        IrCodes.CH_DOWN to Regex("\\b(?:channel down|previous channel|last channel)\\b"),
        IrCodes.INPUT to Regex("\\b(?:input|source|hdmi)\\b"),
        IrCodes.POWER to Regex("\\b(?:on|off|power)\\b"),
    )

    fun ir(t: String): ToolCall? {
        if (QUESTION_RE.containsMatchIn(t) || MEDIA_RE.containsMatchIn(t)) return null
        val device = when {
            TV_RE.containsMatchIn(t) -> "tv"
            AC_RE.containsMatchIn(t) -> "ac"
            else -> return null
        }
        val button = BUTTON_RES.firstOrNull { it.second.containsMatchIn(t) }?.first
        if (device == "tv" && button == null) return null
        val times = if (button == IrCodes.VOL_UP || button == IrCodes.VOL_DOWN) TIMES_RE.find(t)?.groupValues?.get(1) else null
        return RuleMatcher.call("ir_remote", "device" to device, "button" to (button ?: IrCodes.POWER), "times" to times)
    }
}
