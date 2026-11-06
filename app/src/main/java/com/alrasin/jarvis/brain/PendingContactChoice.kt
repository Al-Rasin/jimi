package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.core.ToolCall

class PendingContactChoice(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    sealed interface Reply {
        data object NoPending : Reply
        data object Cancelled : Reply
        data object Expired : Reply
        data class Prompt(val question: String) : Reply
        data class Selection(val call: ToolCall, val contact: Contacts.Contact) : Reply
        /** Two answers missed: the list goes on screen and Jimi stops asking. */
        data class GaveUp(val list: String) : Reply
    }

    private data class Pending(
        val call: ToolCall,
        val query: String,
        val candidates: List<Contacts.Contact>,
        val startedAt: Long,
        val misses: Int = 0,
    )

    private var pending: Pending? = null

    fun begin(call: ToolCall, choice: ContactResolver.Resolution.Ambiguous): Reply.Prompt {
        require(choice.candidates.size > 1)
        pending = Pending(call.copy(args = call.args.toMap()), choice.query, choice.candidates.toList(), nowMillis())
        return Reply.Prompt(ContactResolver.question(choice.query, choice.candidates))
    }

    fun answer(text: String, newRequest: Boolean = false): Reply {
        val choice = pending ?: return Reply.NoPending
        val age = nowMillis() - choice.startedAt
        if (newRequest) {
            clear()
            return Reply.NoPending
        }
        if (age < 0 || age >= TIMEOUT_MS) {
            clear()
            return Reply.Expired
        }
        if (Persona.isNo(text)) {
            clear()
            return Reply.Cancelled
        }
        val selected = ContactResolver.choose(text, choice.candidates)
        if (selected == null) {
            if (choice.misses + 1 >= MAX_MISSES) {
                clear()
                return Reply.GaveUp(ContactResolver.list(choice.candidates))
            }
            pending = choice.copy(misses = choice.misses + 1)
            return Reply.Prompt(ContactResolver.question(choice.query, choice.candidates))
        }
        clear()
        return Reply.Selection(
            choice.call.copy(args = choice.call.args + mapOf("contact" to selected.number, "contact_name" to selected.name)),
            selected,
        )
    }

    fun clear() {
        pending = null
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val MAX_MISSES = 2
    }
}
