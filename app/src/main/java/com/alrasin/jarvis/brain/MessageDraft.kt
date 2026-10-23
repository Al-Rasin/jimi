package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/**
 * A message that is missing its contact or its text ("send a WhatsApp message to Mother G").
 * Jimi asks for the missing part, one question at a time; the full call then goes to the normal confirm step.
 */
class MessageDraft(private val timeoutMs: Long = 60_000) {
    sealed interface Answer {
        data class Ready(val call: ToolCall) : Answer
        data class Ask(val question: String) : Answer
        data object Cancelled : Answer
        data object None : Answer
    }

    private var call: ToolCall? = null
    private var at = 0L

    val active get() = call != null

    /** Starts a draft if [c] is a message with a missing part; returns the question to ask, or null. */
    fun start(c: ToolCall, now: Long): String? {
        val q = question(c) ?: return null
        call = c
        at = now
        return q
    }

    fun clear() { call = null }

    /** [newCommand]: the reply is another tool command; only a missing contact gives way to it. */
    fun answer(reply: String, now: Long, newCommand: Boolean): Answer {
        val c = call ?: return Answer.None
        if (now - at !in 0..timeoutMs) { clear(); return Answer.None }
        if (Persona.isNo(reply) || CANCEL.matches(reply.trim().lowercase())) { clear(); return Answer.Cancelled }
        val words = reply.trim().trimEnd('.', '!', '?')
        val next = when {
            c.arg("contact") == null -> {
                if (newCommand) { clear(); return Answer.None }
                c.copy(args = c.args + ("contact" to words.replace(TO, "")))
            }
            else -> c.copy(args = c.args + ("text" to words.replace(SAY, "").ifBlank { words }))
        }
        at = now
        val q = question(next)
        return if (q == null) { clear(); Answer.Ready(next) } else { call = next; Answer.Ask(q) }
    }

    companion object {
        private val CANCEL = Regex("^(?:cancel|stop|never ?mind|forget it|leave it)[.!]?$")
        private val TO = Regex("^(?:(?:send )?(?:it )?to |i said |i mean )", RegexOption.IGNORE_CASE)
        private val SAY = Regex("^(?:say(?:ing)?|tell (?:him|her|them)|write)(?: that)?[,:]?\\s+", RegexOption.IGNORE_CASE)

        fun question(c: ToolCall): String? {
            if (c.tool !in setOf("send_sms", "send_whatsapp")) return null
            val app = when {
                c.tool == "send_whatsapp" -> " on WhatsApp"
                c.arg("app") == "messenger" -> " on Messenger"
                else -> ""
            }
            val who = c.arg("contact") ?: return "Who should I message$app?"
            if (c.arg("text") == null) return "What should I say to $who?"
            return null
        }

        private val CORRECTION = Regex(
            "^(?:no,? )?(?:i said|i mean|i meant|i told you)(?: to)? (.+)$|^no,? (?:send (?:it )?)?to (.+)$",
            RegexOption.IGNORE_CASE,
        )

        /** New contact from a correction during confirmation ("no, I said Mother G"), else null. */
        fun correctedContact(reply: String): String? {
            val m = CORRECTION.find(reply.trim().trimEnd('.', '!', '?')) ?: return null
            return m.groupValues[1].ifEmpty { m.groupValues[2] }.trim().ifEmpty { null }
        }
    }
}
