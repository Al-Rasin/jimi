package com.alrasin.jimi.brain

import com.alrasin.jimi.core.ToolCall

/** "stop messenger internet connection", "turn whatsapp internet back on" → app_internet (D-084). */
internal object NetRules {
    private const val OFF = "stop|block|cut(?: off)?|turn off|switch off|disable|kill|shut off|shut down|close|pause|disconnect|remove"
    private const val ON = "allow|unblock|turn on|switch on|enable|start|restore|resume|give back|bring back|reconnect|open"
    private const val NET = "(?:internet|data|mobile data|net|wifi|wi fi|network)(?: and (?:wifi|wi fi|data|mobile data))?(?: connection| access)?"
    private const val APP = "(?:the |my )?([a-z][a-z0-9 ]{1,30}?)(?:'s|s)?"

    private val VERB_NET_APP = Regex("(?:please )?($OFF|$ON) (?:the |all )?$NET (?:for|of|on|to|in) $APP(?: app)?(?: back)?(?: again)?")
    private val VERB_APP_NET = Regex("(?:please )?($OFF|$ON) $APP(?: app)? $NET(?: back)?(?: again)?")
    private val VERB_SPLIT = Regex("(?:please )?(turn|switch) $APP(?: app)? $NET (?:back )?(off|on)(?: again)?")
    private val NO_NET = Regex("(?:no|block) $NET for $APP(?: app)?")

    /** "mobile data", "my wifi" etc. are the phone's own switches, not apps. */
    private val NOT_APP = Regex("^(?:mobile|cellular|phone|all|home|office|roaming|hotspot|wireless|adb|usb|bluetooth|airplane|vpn|private|wifi|wi fi|data|internet|the|my|your|this|that|it|everything|[0-9].*)$")

    fun net(t: String): ToolCall? {
        VERB_SPLIT.matchEntire(t)?.let { return build(it.groupValues[2], it.groupValues[3] == "off") }
        NO_NET.matchEntire(t)?.let { return build(it.groupValues[1], off = true) }
        (VERB_NET_APP.matchEntire(t) ?: VERB_APP_NET.matchEntire(t))?.let {
            return build(it.groupValues[2], off = Regex("^(?:$OFF)$").matches(it.groupValues[1]))
        }
        return null
    }

    private fun build(app: String, off: Boolean): ToolCall? {
        val a = app.trim()
        // "wireless" can come in as "wireles" + plural s.
        if (a.isEmpty() || NOT_APP.matches(a) || NOT_APP.matches("${a}s")) return null
        return RuleMatcher.call("app_internet", "app" to a, "state" to if (off) "off" else "on")
    }
}
