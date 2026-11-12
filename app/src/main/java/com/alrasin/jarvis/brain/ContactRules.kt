package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/** RuleMatcher save / edit / share contacts (ROADMAP #33). */
internal object ContactRules {
    private fun call(tool: String, vararg args: Pair<String, String?>) = RuleMatcher.call(tool, *args)

    /** [t] is lowercase text with ' @ . kept. */
    fun contacts(t: String): ToolCall? {
        val s = t.trimEnd('.', '!', '?').trim()
        SAVE_CONTACT_RE.find(s)?.let {
            val name = it.groupValues[2].trim()
            if (!name.contains(CONTACT_NOT_NAME_RE)) return call("save_contact", "name" to name, "number" to it.groupValues[1])
        }
        CONTACT_EMAIL_2_RE.find(s)?.let { return call("add_contact_email", "contact" to it.groupValues[2], "email" to it.groupValues[1]) }
        CONTACT_EMAIL_RE.find(s)?.let {
            if (!it.groupValues[1].contains(CONTACT_NOT_NAME_RE)) return call("add_contact_email", "contact" to it.groupValues[1], "email" to it.groupValues[2])
        }
        SHARE_CONTACT_RE.find(s)?.let {
            val who = it.groupValues[1]
            val to = it.groupValues[3]
            if (who.contains(CONTACT_NOT_NAME_RE) || to.contains(CONTACT_NOT_NAME_RE)) return null
            val app = (it.groupValues[2].ifEmpty { it.groupValues[4] }).let { a -> if (a == "text") "sms" else a }
            return call("share_contact", "contact" to who, "to" to to, "app" to app)
        }
        return null
    }
}
