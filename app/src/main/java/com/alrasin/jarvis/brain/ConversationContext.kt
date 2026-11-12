package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import java.util.Locale

// Regexes built once, not on every call (ROADMAP §L.4).
private val RESOLVE_RE = Regex("\\s+")
private val NOTE_LOCATION_RE = Regex("Saved in (.+?)\\.?$")
private val NORMALIZE_RE = Regex("(?:please )?(?:call|phone|dial) (?:him|her|them|that person|the same person)(?: on (whatsapp|phone))?(?: again)?")
private val NORMALIZE_RE_2 = Regex("(?:please )?(?:send|text|message) (?:it|that|the same message) to (.+?)(?: (?:too|as well|also))?")
private val NORMALIZE_RE_3 = Regex("(?:please )?(?:turn|put) it (up|down)(?: (?:more|again))?")
private val NORMALIZE_RE_4 = Regex("(?:please )?(?:now )?(?:turn|switch|put) (?:it|this|that)(?: back)? (on|off)(?: (?:again|now|please))?|(?:please )?(?:turn|switch) (on|off) (?:it|this|that)(?: again)?")
private val NORMALIZE_RE_5 = Regex("(?:did|have) you (?:turn|switch)(?:ed)? (on|off) ?(.*)|(?:did|have) you (?:turn|switch)(?:ed)? (it|this|that|.+?) (?:on|off)|is (it|this|that) (?:on|off)(?: now)?")
private val NORMALIZE_RE_6 = Regex("(?:only|just) (?:for )?today|(?:only|just) for today|for today only")
private val NORMALIZE_RE_7 = Regex("where (?:did|do|have) you (?:save|saved|put|keep|kept|store|stored|write|wrote|note|noted)(?: it| that| this| the note| my note| the notes| my notes)?|where (?:is|are) (?:it|that|the note|my note|the notes|my notes)(?: saved)?|where (?:was|is) (?:it|that|the note|my note) (?:saved|stored|kept)")
private val NORMALIZE_RE_8 = Regex("what do you (?:know|remember)(?: about (?:it|that|them|this|my notes?|the notes?))?|what (?:is|was) in (?:it|that|the note|my notes?)|what did i (?:note|save)")
private val NORMALIZE_RE_9 = Regex("(?:make|change|set) (?:it|that|the timer)(?: to)? .+ (?:seconds?|minutes?|hours?)")

/** One completed turn, kept in memory only. Resolved tools still need the normal security gate. */
class ConversationContext(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 5 * 60_000,
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
        /** A plain, true answer; nothing runs. */
        data class Say(val text: String) : Resolution
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
        val text = input.trim().trimEnd('.', '?', '!').lowercase(Locale.ROOT).replace(RESOLVE_RE, " ")
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
            previous.arg("app")?.let { args["app"] = it }
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
        STATE_REFERENCE.matchEntire(text)?.let { match ->
            val previous = current?.lastTool?.takeIf { it.tool in SWITCH_TOOLS }
                ?: return Resolution.Clarify("What should I turn ${match.groupValues[1].ifBlank { match.groupValues[2] }}?")
            return Resolution.Tool(withState(previous, match.groupValues[1].ifBlank { match.groupValues[2] }))
        }
        if (text in AGAIN_REFERENCES) {
            val previous = current?.lastTool?.takeIf { it.tool in SWITCH_TOOLS && it.arg("state") != "status" }
                ?: return Resolution.Clarify("What should I do again?")
            return Resolution.Tool(previous)
        }
        DID_YOU_REFERENCE.matchEntire(text)?.let { match ->
            val named = (2..4).map { match.groupValues[it].trim() }.firstOrNull(String::isNotEmpty).orEmpty()
            if (named.isNotEmpty() && normalize(named) !in PRONOUNS) return Resolution.Tool(statusCall(named))
            val previous = current?.lastTool?.takeIf { it.tool in SWITCH_TOOLS }
                ?: return Resolution.Say("I didn't switch anything just now. Tell me what to check.")
            return Resolution.Tool(withState(previous, "status"))
        }
        if (ONLY_TODAY.matches(text)) {
            val previous = current?.lastTool
            return when {
                previous?.tool == "set_alarm" && previous.arg("repeat") != null ->
                    Resolution.Tool(previous.copy(args = previous.args - "repeat"))
                previous?.tool == "set_alarm" -> Resolution.Say("That alarm is already just for once.")
                previous != null -> Resolution.Say("I can't limit that to today. Tell me when to change it back.")
                else -> Resolution.Clarify("What should be only for today?")
            }
        }
        if (NOTE_WHERE.matches(text) && (text.contains("note") || current?.lastTool?.tool == "create_note")) {
            return Resolution.Say(noteLocation(current))
        }
        if (NOTES_KNOW.matches(text) && current?.lastTool?.tool in NOTE_TOOLS) return Resolution.Tool(ToolCall("read_notes"))
        if (TIMER_REFERENCE.matches(text)) {
            return Resolution.Clarify(if (current?.lastTool?.tool == "set_timer") {
                "I can't change an existing timer yet. Would you like to set a new timer?"
            } else "Which timer would you like to change?")
        }
        return null
    }

    /** The note tool said where it saved ("Saved in Google Keep."); repeat that instead of letting chat make a joke (§M.10). */
    private fun noteLocation(current: Snapshot?): String {
        if (current?.lastTool?.tool != "create_note") return "I save notes in your notes app and keep a copy in Jimi's notes, ${Persona.ADDRESS}."
        val app = NOTE_LOCATION_RE.find(current.lastAnswer.trim())?.groupValues?.get(1)
        return if (app != null) "In $app, ${Persona.ADDRESS}." else "In Jimi's notes, ${Persona.ADDRESS}."
    }

    private fun withState(call: ToolCall, state: String): ToolCall =
        if (call.tool == "toggle") ToolCall("toggle", call.args.filterKeys { it == "target" } + ("state" to state))
        else ToolCall("setting", call.args.filterKeys { it == "name" } + ("state" to state))

    /** "did you turn off wifi" → read the fixed toggle when it is one, else the named setting. */
    private fun statusCall(name: String): ToolCall {
        val target = TOGGLE_NAMES[normalize(name).removePrefix("the ")]
        return if (target != null) ToolCall("toggle", mapOf("target" to target, "state" to "status"))
        else ToolCall("setting", mapOf("name" to name.removePrefix("the "), "state" to "status"))
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
        val CALL_REFERENCE = NORMALIZE_RE
        val SEND_REFERENCE = NORMALIZE_RE_2
        val ADJUST_REFERENCE = NORMALIZE_RE_3
        val SWITCH_TOOLS = setOf("toggle", "setting")
        val TOGGLE_NAMES = mapOf(
            "flashlight" to "flashlight", "torch" to "flashlight", "wifi" to "wifi", "wi-fi" to "wifi", "bluetooth" to "bluetooth",
            "mobile data" to "data", "data" to "data", "dnd" to "dnd", "do not disturb" to "dnd", "airplane mode" to "airplane",
            "flight mode" to "airplane", "location" to "location", "gps" to "location", "battery saver" to "battery_saver",
            "auto rotate" to "auto_rotate", "rotation" to "auto_rotate", "wireless debugging" to "wireless_debugging",
            "usb debugging" to "usb_debugging",
        )
        val STATE_REFERENCE = NORMALIZE_RE_4
        val AGAIN_REFERENCES = setOf("do it", "do it again", "do that", "do that again", "again", "one more time", "same again")
        val DID_YOU_REFERENCE = NORMALIZE_RE_5
        val ONLY_TODAY = NORMALIZE_RE_6
        val NOTE_TOOLS = setOf("create_note", "read_notes")
        val NOTE_WHERE = NORMALIZE_RE_7
        val NOTES_KNOW = NORMALIZE_RE_8
        val TIMER_REFERENCE = NORMALIZE_RE_9
    }
}
