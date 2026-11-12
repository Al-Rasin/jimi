package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.SelfAccount
import com.alrasin.jarvis.core.ToolCall

/** RuleMatcher calls, messages and reading chats (ROADMAP §H.1, §I.1, §M.6). */
internal object CommsRules {
    private fun call(tool: String, vararg args: Pair<String, String?>) = RuleMatcher.call(tool, *args)

    private fun normalize(text: String) = RuleMatcher.normalize(text)

    fun comms(t: String): ToolCall? {
        val calling = t.trimEnd('.', '!', '?').replace(COMMS_RE, "")
        callControl(calling)?.let { return it }
        COMMS_RE_2.find(calling)?.let {
            return call("call", "contact" to it.groupValues[3], "app" to commsApp(it.groupValues[1]),
                "video" to it.groupValues[2].takeIf { mode -> mode.isNotEmpty() }?.let { mode -> (mode == "video").toString() })
        }
        // "call using grameenphone" names a SIM, not a person: ask who.
        if (calling.matches(COMMS_RE_3)) return call("call")
        COMMS_RE_4.find(calling)?.let {
            return call("call", "contact" to it.groupValues[2], "app" to it.groupValues[3].takeUnless { app -> app.isEmpty() || app == "speaker" }?.let(::commsApp),
                "video" to it.groupValues[1].takeIf { mode -> mode.isNotEmpty() }?.let { mode -> (mode == "video").toString() })
        }
        COMMS_RE_5.find(t)?.let {
            return message(it.groupValues[3], commsApp(it.groupValues[1]), addressed = it.groupValues[2].isNotEmpty())
        }
        COMMS_RE_6.find(t)?.let {
            val app = it.groupValues[2].takeIf(String::isNotEmpty)?.let(::commsApp)
            // Only a plain "message" leaves the app open, so "message rahim saying hi on whatsapp" picks WhatsApp.
            return message(it.groupValues[4], app, addressed = it.groupValues[3].isNotEmpty(),
                appAfterText = app == null && it.groupValues[1] in setOf("message", "msg"))
        }
        // "send a message", "send a whatsapp message", "message on messenger" → ask who and what.
        COMMS_RE_7.find(t)?.let {
            val app = (it.groupValues[1].ifEmpty { it.groupValues[2] }).takeIf(String::isNotEmpty)?.let(::commsApp)
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "app" to app?.takeIf { a -> a == "messenger" })
        }
        appInMiddle(t)?.let { return it }
        anyOrderChat(t)?.let { return it }
        if (normalize(t).matches(COMMS_WHOLE_RE)) return call("read_messages")
        COMMS_RE_8.find(t)
            ?.let { return call("compose_email", "to" to it.groupValues[1], "subject" to it.groupValues[2]) }
        return null
    }

    /** Ringing call and call log (ROADMAP B §I.1). Before "call <name>" so "call back" is not a contact. */
    private fun callControl(t: String): ToolCall? = when {
        t.matches(CALL_CONTROL_WHOLE_RE) -> call("answer_call")
        t.matches(CALL_CONTROL_WHOLE_RE_2) -> call("reject_call")
        t.matches(CALL_CONTROL_WHOLE_RE_3) -> call("missed_calls")
        t.matches(CALL_CONTROL_WHOLE_RE_4) -> call("call_back")
        t.matches(CALL_CONTROL_WHOLE_RE_5) -> call("redial")
        else -> null
    }

    private fun commsApp(app: String) = app.replace(" ", "")

    private val LEAD_OPEN = Regex("^(?:open|launch|go to|start) (?:the )?(whats ?app|messenger)(?: app)?(?:\\s*,\\s*|\\s+)(?:(?:and then|and|then) )?(.+)$")
    private val LEAD_IN = Regex("^(?:in|on|using|via|through) (?:the )?(whats ?app|messenger|sms)(?: app)?\\s*,?\\s+(.+)$")
    private val TRAIL_APP = Regex("\\s+(?:in|on|using|via|through) (?:the )?(whats ?app|messenger|sms)(?: app)?$")
    private val NOT_A_CONTACT = setOf("you", "he", "she", "they", "i", "we", "it", "me", "us", "him", "her", "them")
    private val COUNT_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /**
     * Messaging in any word order and reading one person's chat (ROADMAP §H.1): the app may come first
     * ("open messenger and …", "in whatsapp …") or last ("… on messenger"). Sending needs the app named.
     */
    private fun anyOrderChat(raw: String): ToolCall? {
        var s = raw.trimEnd('.', '!', '?').trim()
        var app: String? = null
        (LEAD_OPEN.find(s) ?: LEAD_IN.find(s))?.let { app = commsApp(it.groupValues[1]); s = it.groupValues[2] }
        TRAIL_APP.find(s)?.let {
            val tail = commsApp(it.groupValues[1])
            if (app != null && app != tail) return null
            app = tail
            s = s.substring(0, it.range.first)
        }
        readChat(s, app)?.let { return it }
        val sendApp = app ?: return null
        ANY_ORDER_CHAT_RE.find(s)?.let { return message(it.groupValues[1], sendApp) }
        ANY_ORDER_CHAT_RE_2.find(s)?.let {
            if (it.groupValues[2].trim() in NOT_A_CONTACT && !SelfAccount.isSelf(it.groupValues[2])) return null
            return call(if (sendApp == "whatsapp") "send_whatsapp" else "send_sms", "contact" to it.groupValues[2],
                "text" to it.groupValues[1], "app" to sendApp.takeIf { a -> a == "messenger" })
        }
        ANY_ORDER_CHAT_RE_3.find(s)?.takeUnless { it.groupValues[1].substringBefore(' ') in NOT_A_CONTACT }
            ?.let { return message(it.groupValues[1], sendApp) }
        ANY_ORDER_CHAT_RE_4.find(s)?.let { return message(it.groupValues[1], sendApp) }
        return null
    }

    /**
     * "send hello on whatsapp to rahim", "send hi to my whatsapp" (owner's own chat), "send hi to 017 1234 5678"
     * (a number said alone is SMS to that number, no contact search). ROADMAP §M.6.
     */
    private fun appInMiddle(raw: String): ToolCall? {
        val s = raw.trimEnd('.', '!', '?').trim()
        fun send(app: String, who: String, text: String) = call(if (app == "whatsapp") "send_whatsapp" else "send_sms",
            "contact" to who.trim(), "text" to text.trim(), "app" to app.takeIf { it == "messenger" })
        APP_IN_MIDDLE_RE.find(s)?.let {
            if (it.groupValues[3].trim() in NOT_A_CONTACT && !SelfAccount.isSelf(it.groupValues[3])) return null
            return send(commsApp(it.groupValues[2]), it.groupValues[3], it.groupValues[1])
        }
        APP_IN_MIDDLE_RE_2.find(s)?.let {
            return send(commsApp(it.groupValues[2]), "myself", it.groupValues[1])
        }
        APP_IN_MIDDLE_RE_3.find(s)?.takeIf { it.groupValues[2].count(Char::isDigit) >= 5 }?.let {
            return send("sms", it.groupValues[2], it.groupValues[1])
        }
        return null
    }

    private fun readChat(s: String, app: String?): ToolCall? {
        val last = "(?:the |my )?(?:last|latest|newest|recent|new)(?: (\\w+))? (messages?|texts?|sms|chats?)"
        val (who, n, noun) = Regex("^(?:read|show|tell)(?: me)? $last (?:of|from|by) (.+)$").find(s)
            ?.let { Triple(it.groupValues[3], it.groupValues[1], it.groupValues[2]) }
            ?: READ_CHAT_RE.find(s)
                ?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            ?: READ_CHAT_RE_2.find(s)?.let { Triple(it.groupValues[1], "", "message") }
            ?: READ_CHAT_RE_3.find(s)
                ?.let { Triple(it.groupValues[1], "", "messages") }
            ?: return null
        val contact = who.trim()
        if (contact.isEmpty() || contact in NOT_A_CONTACT) return null
        val count = n.toIntOrNull() ?: COUNT_WORDS.indexOf(n).takeIf { it >= 0 }?.plus(1)
            ?: if (n.isNotEmpty()) return null else if (noun.endsWith("s") && noun != "sms") 3 else 1
        return call("read_chat", "contact" to contact, "app" to app, "count" to count.toString())
    }
    private val SPOKEN_LETTER = Regex("[a-z]|ji|jee|gee|bee|tee|dee|pee|ef|eff|kay|jay|em|en|es|vee|zed|zee")

    /**
     * [addressed]: the words came after "to", so with no "saying …" the whole rest is the contact.
     * Missing contact or text is returned empty; the Assistant then asks for it (MessageDraft).
     */
    private fun message(raw: String, requestedApp: String? = null, addressed: Boolean = false, appAfterText: Boolean = false): ToolCall? {
        var rest = raw
        var to = addressed
        // "my whatsapp" / "to my messenger" → app only, ask who.
        MESSAGE_RE.find(rest)?.let {
            val app = commsApp(it.groupValues[1])
            if (requestedApp != null && requestedApp != app) return null
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "app" to app.takeIf { a -> a == "messenger" })
        }
        // "to my whatsapp hello" → the owner's own chat.
        MESSAGE_RE_2.find(rest)?.let {
            val app = commsApp(it.groupValues[1])
            if (requestedApp != null && requestedApp != app) return null
            return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "contact" to "myself",
                "text" to it.groupValues[2], "app" to app.takeIf { a -> a == "messenger" })
        }
        if (rest.startsWith("to ")) { rest = rest.removePrefix("to "); to = true }
        // "017 1234 5678 hello": the whole spoken number is the recipient.
        MESSAGE_RE_3.find(rest)?.takeIf { it.groupValues[1].count(Char::isDigit) >= 5 }?.let {
            return call("send_sms".takeIf { requestedApp != "whatsapp" } ?: "send_whatsapp", "contact" to it.groupValues[1],
                "text" to it.groupValues[2].trim().ifBlank { null }, "app" to requestedApp?.takeIf { a -> a == "messenger" })
        }
        val delimiter = MESSAGE_RE_4.find(rest)
        var app = requestedApp
        val recipient: String
        val text: String
        if (delimiter != null) {
            var head = rest.substring(0, delimiter.range.first).trim()
            var said = rest.substring(delimiter.range.last + 1).trim()
            // "message rahim saying hi on whatsapp": with no app named yet, a trailing app word picks the app.
            if (appAfterText) TRAIL_APP.find(said)?.let {
                app = commsApp(it.groupValues[1])
                said = said.substring(0, it.range.first).trim()
            }
            text = said
            MESSAGE_RE_5.find(head)?.let {
                if (app != null && app != commsApp(it.groupValues[2])) return null
                app = commsApp(it.groupValues[2])
                head = it.groupValues[1]
            }
            recipient = head
        } else {
            val routed = if (requestedApp == null) MESSAGE_RE_6.find(rest) else null
            if (routed != null) {
                if (app != null && app != commsApp(routed.groupValues[2])) return null
                app = commsApp(routed.groupValues[2])
                recipient = routed.groupValues[1]
                text = routed.groupValues[3]
            } else {
                val split = rest.indexOf(' ')
                val tail = if (split > 0) rest.substring(split + 1) else ""
                // "to madar ji": a trailing spoken letter is part of the name (contacts saved as "Name G").
                if (split < 1 || (to && SPOKEN_LETTER.matches(tail))) {
                    recipient = rest
                    text = ""
                } else {
                    recipient = rest.substring(0, split)
                    text = tail
                }
            }
        }
        if (recipient.isBlank() || text in setOf("saying", "that says", "say", "that")) return null
        return call(if (app == "whatsapp") "send_whatsapp" else "send_sms", "contact" to recipient,
            "text" to text.ifBlank { null }, "app" to app?.takeIf { it == "messenger" })
    }
}
