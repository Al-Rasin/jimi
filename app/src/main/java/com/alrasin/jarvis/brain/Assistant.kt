package com.alrasin.jarvis.brain

import android.content.Context
import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.ToolExecutor
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.ConversationTurn
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.Routine
import com.alrasin.jarvis.data.RoutineDao
import com.alrasin.jarvis.data.ToolCallLog
import com.alrasin.jarvis.data.ToolLogDao
import com.alrasin.jarvis.data.TurnDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation (PLAN 5.2–5.5, 8.1, 9.4): text in → reply out. Order:
 * pending yes/no → small talk → rules → local tool caller → chat model → "can't do that yet".
 * Every turn goes to conversation_turn; every tool call to tool_call_log (training data).
 */
@Singleton
class Assistant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: ToolExecutor,
    private val turns: TurnDao,
    private val toolLog: ToolLogDao,
    private val routines: RoutineDao,
    private val memory: MemoryDao,
    private val models: ModelBrain,
    private val cloud: CloudBrain,
) {
    data class Input(
        val heard: String,
        val raw: String = heard,
        val source: String = "voice",          // voice | typed | follow_up | fix
        val locked: Boolean = false,
        val voiceOk: Boolean? = null,
        val wakeId: Long? = null,
        val audioPath: String? = null,
    )

    /** [endConversation]: no follow-up listening. [expectAnswer]: listen for a confirmation or clarification. */
    data class Reply(
        val text: String,
        val data: String = "",
        val tool: String? = null,
        val turnId: Long = 0,
        val endConversation: Boolean = false,
        val expectAnswer: Boolean = false,
        val spokenAlready: Boolean = false,
    )

    private val lock = Mutex()
    private var pending: Pair<ToolCall, Long>? = null
    private val contactChoice = PendingContactChoice()
    private val conversation = ConversationContext()
    private val contacts = Contacts(context)
    private var lastReply = ""
    private val history = ArrayDeque<Exchange>()

    /** [speak] is called for chat sentences while the model is still writing. */
    suspend fun handle(input: Input, speak: (String) -> Unit = {}): Reply = lock.withLock {
        executor.observeLockState(input.locked)
        val text = input.heard.trim()
        val r = if (text.isEmpty()) Reply(Persona.DIDNT_GET) else runCatching { route(text, input, speak) }
            .onFailure { JarvisLog.e("assistant failed", it) }
            .getOrElse { Reply("Sorry ${Persona.ADDRESS}, something went wrong.") }
        val id = turns.insert(
            ConversationTurn(
                timeMs = System.currentTimeMillis(), source = input.source, heard = text, rawText = input.raw,
                audioPath = input.audioPath, reply = r.text, tool = r.tool, wakeId = input.wakeId,
            ),
        )
        if (r.text.isNotBlank()) {
            if (r.endConversation) clearConversation()
            else if (r.tool in setOf("chat", "cloud")) conversation.remember(text, null, r.text)
            lastReply = r.text
            history.addLast(Exchange(text, r.text))
            while (history.size > 6) history.removeFirst()
        }
        r.copy(turnId = id)
    }

    private suspend fun route(text: String, input: Input, speak: (String) -> Unit): Reply {
        val rule = RuleMatcher.match(text)
        if (rule?.tool in setOf("panic", "cancel")) return runTool(rule!!, input, "rule")
        val smallTalk = Persona.smallTalk(text, LocalDateTime.now().hour)
        if (smallTalk?.startsWith("Bye") == true) return Reply(smallTalk, endConversation = true)
        val followUp = conversation.resolve(text)
        when (val choice = contactChoice.answer(text, newRequest = rule != null || smallTalk != null || followUp != null || cloud.asked(text))) {
            is PendingContactChoice.Reply.Selection -> return runTool(choice.call, input, "contact_choice")
            is PendingContactChoice.Reply.Prompt -> return Reply(choice.question, expectAnswer = true)
            PendingContactChoice.Reply.Cancelled -> {
                clearConversation()
                return Reply("Okay, cancelled.", endConversation = true)
            }
            PendingContactChoice.Reply.Expired -> return Reply("That contact choice expired. Who should I contact?", expectAnswer = true)
            PendingContactChoice.Reply.NoPending -> Unit
        }
        pending?.let { (call, at) ->
            if (followUp != null && monotonicMillis() - at in 0 until PENDING_MS) {
                return Reply("Please confirm the pending request with yes, or cancel it with no first.", expectAnswer = true)
            }
            pending = null
            if (monotonicMillis() - at in 0 until PENDING_MS) {
                if (Persona.isYes(text)) return runTool(call, input, "confirm", confirmed = true)
                if (Persona.isNo(text)) {
                    clearConversation()
                    return Reply("Okay, cancelled.", tool = call.tool, endConversation = true)
                }
            } else if (Persona.isYes(text)) {
                return Reply("That confirmation expired. Please repeat the request.", expectAnswer = true)
            }
        }
        smallTalk?.let { return Reply(it) }
        var query = text
        var contextualQuestion = false
        when (followUp) {
            is ConversationContext.Resolution.Tool -> return runTool(followUp.call, input, "follow_up")
            is ConversationContext.Resolution.Clarify -> return Reply(followUp.question, expectAnswer = true)
            is ConversationContext.Resolution.Query -> {
                query = followUp.text
                contextualQuestion = true
            }
            null -> Unit
        }
        val forced = cloud.asked(text)   // "think hard …" skips the tools
        if (!forced && !contextualQuestion) {
            rule?.let { return runTool(it, input, "rule") }
            models.toolCall(text, contextLine())?.let { return runTool(it, input, "llm") }
        }
        val facts = memory.all().map { it.text }
        cloud.chat(query, history.toList(), facts, forced)?.let { return Reply(it, tool = "cloud", expectAnswer = it.trimEnd().endsWith('?')) }
        models.chat(query, history.toList(), facts, speak)?.let { return Reply(it.text, tool = "chat", spokenAlready = it.streamed, expectAnswer = it.text.trimEnd().endsWith('?')) }
        return Reply(Persona.CANT)
    }

    private fun clearConversation() {
        pending = null
        contactChoice.clear()
        conversation.clear()
    }

    private fun monotonicMillis() = System.nanoTime() / 1_000_000

    private fun contextLine(): String {
        val now = LocalDateTime.now()
        val app = JarvisAccessibilityService.instance?.appPackage()
        return "time ${now.toLocalDate()} ${now.toLocalTime().withNano(0)}" + (app?.let { ", app $it" } ?: "")
    }

    private suspend fun runTool(request: ToolCall, input: Input, source: String, confirmed: Boolean = false): Reply {
        var call = request
        when (call.tool) {
            "cancel" -> {
                clearConversation()
                return Reply("Okay.", tool = "cancel", endConversation = true)
            }
            "repeat_last" -> return Reply(lastReply.ifEmpty { "I haven't said anything yet." }, tool = "repeat_last")
            "run_routine" -> return runRoutine(call.arg("name") ?: "", input)
            "panic" -> return panic(input)
        }
        if (!confirmed && call.tool in setOf("call", "send_sms", "send_whatsapp")) {
            val who = call.arg("contact") ?: return Reply("Who should I contact?", expectAnswer = true)
            when (val resolved = contacts.resolve(who)) {
                is ContactResolver.Resolution.Ambiguous -> {
                    pending = null
                    conversation.clear()
                    return Reply(contactChoice.begin(call, resolved).question, tool = call.tool, expectAnswer = true)
                }
                is ContactResolver.Resolution.Found -> {
                    val name = if (source in setOf("contact_choice", "follow_up")) call.arg("contact_name") ?: resolved.contact.name
                        else resolved.contact.name
                    call = call.copy(args = call.args + mapOf("contact" to resolved.contact.number, "contact_name" to name))
                }
                ContactResolver.Resolution.NotFound -> return Reply("I can't find $who in your contacts. Who did you mean?", expectAnswer = true)
            }
        }
        val ctx = ToolContext(context, input.heard, input.locked)
        return when (val o = executor.execute(call, ctx, confirmed, input.voiceOk)) {
            is Outcome.NeedConfirm -> {
                pending = call to monotonicMillis()
                val recipient = call.arg("contact_name")?.let { "$it (${call.arg("contact")})" }
                val question = when {
                    recipient == null -> o.question
                    call.tool == "call" -> "Call $recipient?"
                    call.tool in setOf("send_sms", "send_whatsapp") -> "Send \"${call.arg("text")}\" to $recipient?"
                    else -> o.question
                }
                Reply(question, tool = call.tool, expectAnswer = true)
            }
            is Outcome.Denied -> {
                log(input.heard, call, source, ToolResult.fail(o.why))
                Reply(o.why, tool = call.tool)
            }
            is Outcome.Done -> {
                log(input.heard, call, source, o.result)
                if (o.result.ok) conversation.remember(input.heard, call, o.result.say)
                Reply(o.result.say, o.result.data, call.tool)
            }
        }
    }

    /** Panic word (PLAN 12.4): forget the pending question, lock the screen, refuse risky tools until unlocked. */
    private suspend fun panic(input: Input): Reply {
        clearConversation()
        executor.enterLockdown()
        executor.observeLockState(input.locked)
        val call = ToolCall("lock_phone", emptyMap())
        val r = executor.execute(call, ToolContext(context, input.heard, input.locked), confirmed = true, voiceOk = null)
        log(input.heard, call, "panic", (r as? Outcome.Done)?.result ?: ToolResult.fail("not run"))
        JarvisLog.i("panic: locked down")
        return Reply("Locked down.", tool = "panic", endConversation = true)
    }

    private suspend fun log(heard: String, call: ToolCall, source: String, r: ToolResult) {
        runCatching {
            toolLog.insert(
                ToolCallLog(
                    timeMs = System.currentTimeMillis(), heard = heard, tool = call.tool, argsJson = Json.write(call.args),
                    source = source, ok = r.ok, result = r.say,
                ),
            )
        }
    }

    /** Runs each step; speaks only info results and failures (PLAN 8.1–8.3). */
    private suspend fun runRoutine(name: String, input: Input): Reply {
        val steps = stepsFor(name) ?: return Reply("I don't have a routine called ${name.replace('_', ' ')}.", tool = "run_routine")
        val says = ArrayList<String>()
        val ctx = ToolContext(context, input.heard, input.locked)
        val stopped = RoutineRunner.run(
            steps,
            execute = { step, confirmed -> executor.execute(step, ctx, confirmed, input.voiceOk) },
            record = { step, result ->
                log(input.heard, step, "routine", result)
                if (!result.ok || step.tool in INFO_TOOLS) says.add(result.say)
            },
        )
        if (stopped != null) return Reply(stopped, tool = "run_routine")
        val hello = when (name) {
            "good_night" -> "Good night, ${Persona.ADDRESS}. Do not disturb is on."
            "good_morning" -> "Good morning, ${Persona.ADDRESS}."
            else -> "Done."
        }
        return Reply((listOf(hello) + says).joinToString(" "), tool = "run_routine", endConversation = name == "good_night")
    }

    suspend fun stepsFor(name: String): List<ToolCall>? {
        val json = routines.get(name)?.takeIf { it.enabled }?.stepsJson ?: DEFAULT_ROUTINES[name]?.let { stepsJson(it) } ?: return null
        return (Json.parse(json) as List<*>).mapNotNull { (it as? Map<*, *>)?.let { m -> ToolCall.fromJson(Json.write(m)) } }
    }

    suspend fun saveRoutine(name: String, steps: List<ToolCall>) = routines.put(Routine(name, stepsJson(steps)))

    companion object {
        private const val PENDING_MS = 30_000L
        private val INFO_TOOLS = setOf("device_status", "list_reminders", "weather", "notification_summary", "read_notes", "news")

        fun stepsJson(steps: List<ToolCall>) = Json.write(steps.map { mapOf("tool" to it.tool, "args" to it.args) })

        val DEFAULT_ROUTINES = mapOf(
            "good_night" to listOf(
                ToolCall("toggle", mapOf("target" to "dnd", "state" to "on")),
                ToolCall("set_brightness", mapOf("level" to "5")),
                ToolCall("list_reminders"),
            ),
            "good_morning" to listOf(
                ToolCall("toggle", mapOf("target" to "dnd", "state" to "off")),
                ToolCall("set_brightness", mapOf("level" to "auto")),
                ToolCall("device_status", mapOf("what" to "date")),
                ToolCall("weather"),
                ToolCall("list_reminders"),
                ToolCall("notification_summary"),
            ),
        )
    }
}
