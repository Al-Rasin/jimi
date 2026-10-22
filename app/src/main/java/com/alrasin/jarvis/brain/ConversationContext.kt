package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import java.util.Locale

/** One completed turn, kept in memory only. Resolved tools still need the normal security gate. */
class ConversationContext(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 60_000,
) {
    data class Snapshot(
        val lastTool: ToolCall?,
        val entities: Map<String, String>,
        val lastAnswer: String,
        val lastUserText: String,
    )

    sealed interface Resolution {
        data class Tool(val call: ToolCall) : Resolution
        /** Informational context for chat; must not be sent to a tool-call model. */
        data class Query(val text: String) : Resolution
        data class Clarify(val question: String) : Resolution
    }

    private var recent: Snapshot? = null
    private var rememberedAt = 0L

    init { require(ttlMillis > 0) }

    @Synchronized
    fun remember(userText: String, tool: ToolCall?, answer: String, entities: Map<String, String> = emptyMap()) {
        val safeTool = tool?.copy(args = bounded(tool.args))
        val knownEntities = (safeTool?.args.orEmpty() + bounded(entities))
            .filterKeys { it in ENTITY_KEYS }
        recent = Snapshot(safeTool, knownEntities, answer.take(2048), userText.take(512))
        rememberedAt = clockMillis()
    }

    @Synchronized
    fun snapshot(): Snapshot? {
        val age = clockMillis() - rememberedAt
        if (age < 0 || age >= ttlMillis) clear()
        return recent?.let { it.copy(lastTool = it.lastTool?.copy(args = it.lastTool.args.toMap()), entities = it.entities.toMap()) }
    }

    @Synchronized
    fun clear() { recent = null }

    fun resolve(input: String): Resolution? {
        val text = input.trim().trimEnd('.', '?', '!').lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val current = snapshot()
        CALL_REFERENCE.matchEntire(text)?.let {
            val contact = current?.entities?.get("contact")?.takeUnless { normalize(it) in PRONOUNS }
                ?: return Resolution.Clarify("Who would you like me to call?")
            val args = mutableMapOf("contact" to contact)
            current?.entities?.get("contact_name")?.let { name -> args["contact_name"] = name }
            val app = it.groupValues[1].ifBlank { current?.lastTool?.args?.get("app").orEmpty() }
            if (app.isNotBlank()) args["app"] = app
            return Resolution.Tool(ToolCall("call", args))
        }
        SEND_REFERENCE.matchEntire(text)?.let { match ->
            val previous = current?.lastTool
            val message = previous?.args?.get("text")?.takeIf(String::isNotBlank)
            if (previous == null || previous.tool !in MESSAGE_TOOLS || message == null) {
                return Resolution.Clarify("What message would you like me to send?")
            }
            val namedRecipient = match.groupValues[1].trim().removePrefix("to ")
            val sameRecipient = normalize(namedRecipient) in PRONOUNS
            val recipient = if (sameRecipient) current?.entities?.get("contact") else namedRecipient
            if (recipient.isNullOrBlank() || normalize(recipient) in PRONOUNS) {
                return Resolution.Clarify("Who should I send it to?")
            }
            val args = mutableMapOf("contact" to recipient, "text" to message)
            if (sameRecipient) current?.entities?.get("contact_name")?.let { name -> args["contact_name"] = name }
            return Resolution.Tool(ToolCall(previous.tool, args))
        }
        ADJUST_REFERENCE.matchEntire(text)?.let { match ->
            val previous = current?.lastTool?.tool
            if (previous !in ADJUSTABLE_TOOLS) return Resolution.Clarify("Do you mean the volume or the brightness?")
            return Resolution.Tool(ToolCall(previous!!, mapOf("level" to match.groupValues[1])))
        }
        if (text in TEMPORAL_REFERENCES) {
            val previous = current?.lastTool
            if (previous?.tool == "weather") {
                return Resolution.Tool(ToolCall("weather", previous.args.filterKeys { it == "place" } + ("day" to "tomorrow")))
            }
            if (current != null && (previous == null || previous.tool in INFORMATION_TOOLS) && current.lastUserText.isNotBlank()) {
                return Resolution.Query("Previous question: ${current.lastUserText}\nFollow-up question: What about tomorrow?")
            }
            return Resolution.Clarify("What would you like to know about tomorrow?")
        }
        if (TIMER_REFERENCE.matches(text)) {
            return Resolution.Clarify(if (current?.lastTool?.tool == "set_timer") {
                "I can't change an existing timer yet. Would you like to set a new timer?"
            } else "Which timer would you like to change?")
        }
        return null
    }

    private fun bounded(args: Map<String, String>): Map<String, String> = args.entries.asSequence()
        .filter { it.key.length <= 64 && it.value.length <= 4096 && it.value.isNotBlank() }
        .take(16).associate { it.key to it.value }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)

    private companion object {
        val ENTITY_KEYS = setOf("contact", "contact_name", "place", "text", "app")
        val PRONOUNS = setOf("him", "her", "them", "it", "that person", "the same person")
        val MESSAGE_TOOLS = setOf("send_sms", "send_whatsapp")
        val ADJUSTABLE_TOOLS = setOf("set_volume", "set_brightness")
        val INFORMATION_TOOLS = setOf("web_search", "wiki_lookup", "read_page", "news")
        val TEMPORAL_REFERENCES = setOf("what about tomorrow", "how about tomorrow", "and tomorrow", "tomorrow")
        val CALL_REFERENCE = Regex("(?:please )?(?:call|phone|dial) (?:him|her|them|that person|the same person)(?: on (whatsapp|phone))?(?: again)?")
        val SEND_REFERENCE = Regex("(?:please )?(?:send|text|message) (?:it|that|the same message) to (.+?)(?: (?:too|as well|also))?")
        val ADJUST_REFERENCE = Regex("(?:please )?(?:turn|put) it (up|down)(?: (?:more|again))?")
        val TIMER_REFERENCE = Regex("(?:make|change|set) (?:it|that|the timer)(?: to)? .+ (?:seconds?|minutes?|hours?)")
    }
}
