package com.alrasin.jarvis.brain

import android.content.Context
import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.SelfAccount
import com.alrasin.jarvis.actions.ToolExecutor
import com.alrasin.jarvis.agent.AgentResult
import com.alrasin.jarvis.agent.AgentRouting
import com.alrasin.jarvis.agent.RecipeStore
import com.alrasin.jarvis.agent.ScreenAgentSession
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.SmokeSession
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation (PLAN 5.2–5.5, 8.1, 9.4): text in → reply out. Order:
 * pending yes/no → small talk → intent router → rules / local tool caller / chat.
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
    private val screenAgent: ScreenAgentSession,
    private val recipes: RecipeStore,
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
    private var contactChoice = PendingContactChoice()
    private val draft = MessageDraft()
    private val timeSlot = TimeSlot()
    private var conversation = ConversationContext()
    private val contacts = Contacts(context)
    private var smokePending: Pair<ToolCall, Long>? = null
    private var smokeLastReply = ""
    private val smokeHistory = ArrayDeque<Exchange>()
    private val smokeContactChoice = PendingContactChoice()
    private val smokeConversation = ConversationContext()
    private var lastReply = ""
    /** Last routing step, so a crash reply can say what failed (ROADMAP §M.3). */
    private var stage = "start"
    private val history = ArrayDeque<Exchange>()
    /** Cloud answers only: safe to send back to the cloud as context (no tool data, D-035). */
    private val cloudHistory = ArrayDeque<Exchange>()

    fun stopScreenAgent() = screenAgent.stop()

    /** [speak] is called for chat sentences while the model is still writing. */
    suspend fun handle(input: Input, speak: (String) -> Unit = {}): Reply {
        val smoke = coroutineContext[SmokeSession]
        if (smoke == null && RuleMatcher.interrupt(input.heard) != null) {
            screenAgent.stop()
        }
        return lock.withLock {
        if (smoke == null) return@withLock handleLocked(input, speak)
        val liveContactChoice = contactChoice
        val liveConversation = conversation
        contactChoice = smokeContactChoice
        conversation = smokeConversation
        val livePending = pending
        val liveReply = lastReply
        val liveHistory = history.toList()
        pending = smokePending
        lastReply = smokeLastReply
        history.clear()
        history.addAll(smokeHistory)
        try {
            handleLocked(input, {})
        } finally {
            smokePending = pending
            smokeLastReply = lastReply
            smokeHistory.clear()
            smokeHistory.addAll(history)
            contactChoice = liveContactChoice
            conversation = liveConversation
            pending = livePending
            lastReply = liveReply
            history.clear()
            history.addAll(liveHistory)
        }
    }

    }

    private suspend fun handleLocked(input: Input, speak: (String) -> Unit): Reply {
        val smoke = coroutineContext[SmokeSession]
        if (smoke == null) {
            executor.observeLockState(input.locked)
            screenAgent.observeLockState(input.locked)
        }
        smoke?.route = "empty"
        smoke?.call = null
        smoke?.requestedCall = null
        smoke?.ok = false
        val text = input.heard.trim()
        stage = "start"
        val r = if (text.isEmpty()) Reply(Persona.DIDNT_GET) else runCatching { route(text, input, speak) }
            .onFailure { if (it is CancellationException) throw it; JarvisLog.e("assistant failed", it) }
            .getOrElse { smoke?.ok = false; smoke?.route = "error"; Reply(ReplyText.routeFailed(stage)) }
        val id = if (smoke != null) 0L else turns.insert(
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
            if (r.tool == "cloud") {
                cloudHistory.addLast(Exchange(text, r.text))
                while (cloudHistory.size > 4) cloudHistory.removeFirst()
            }
        }
        return r.copy(turnId = id)
    }

    private suspend fun route(text: String, input: Input, speak: (String) -> Unit): Reply {
        val smoke = coroutineContext[SmokeSession]
        fun trace(route: String, call: ToolCall? = null, ok: Boolean = true) {
            stage = route
            smoke?.route = route
            smoke?.call = call
            smoke?.ok = ok
        }
        val routingStarted = System.nanoTime()
        val rule = RuleMatcher.match(text)
        if (rule?.tool in setOf("panic", "cancel")) return runTool(rule!!, input, "rule")
        if (smoke == null && screenAgent.hasPendingQuestion) {
            return screenReply(screenAgent.answer(text, input.locked, input.voiceOk))
        }
        if (smoke == null && screenAgent.hasPendingConfirmation) {
            if (Persona.isYes(text)) {
                val voice = input.source in setOf("voice", "follow_up") && input.voiceOk != false
                if (!voice) return Reply("Please confirm this screen action by voice.", tool = "screen_agent", expectAnswer = true)
                return screenReply(screenAgent.confirm(true, input.locked, input.voiceOk))
            }
            screenAgent.stop()
            if (Persona.isNo(text)) return Reply("Okay, cancelled.", tool = "screen_agent")
        }
        if (smoke == null) {
            when (val d = draft.answer(text, monotonicMillis(), newCommand = rule != null && rule.tool !in MESSAGE_TOOLS)) {
                is MessageDraft.Answer.Ready -> return runTool(d.call, input, "draft")
                is MessageDraft.Answer.Ask -> return Reply(d.question, expectAnswer = true)
                MessageDraft.Answer.Cancelled -> {
                    clearConversation()
                    return Reply("Okay, cancelled.", endConversation = true)
                }
                MessageDraft.Answer.None -> Unit
            }
            when (val t = timeSlot.answer(text, monotonicMillis(), newCommand = rule != null)) {
                is TimeSlot.Answer.Ready -> return runTool(t.call, input, "time_slot")
                is TimeSlot.Answer.Ask -> return Reply(t.question, expectAnswer = true)
                TimeSlot.Answer.Cancelled -> {
                    clearConversation()
                    return Reply("Okay, cancelled.", endConversation = true)
                }
                TimeSlot.Answer.GaveUp -> return Reply(TimeSlot.GAVE_UP)
                TimeSlot.Answer.None -> Unit
            }
        }
        trace("smalltalk")
        val smallTalk = Persona.smallTalk(text, LocalDateTime.now().hour)
        if (smallTalk?.startsWith("Bye") == true) return Reply(smallTalk, endConversation = true)
        val followUp = conversation.resolve(text)
        trace("contact_choice")
        when (val choice = contactChoice.answer(text, newRequest = rule != null || smallTalk != null || followUp != null || cloud.asked(text))) {
            is PendingContactChoice.Reply.Selection -> return runTool(choice.call, input, "contact_choice")
            is PendingContactChoice.Reply.Prompt -> return Reply(choice.question, expectAnswer = true)
            PendingContactChoice.Reply.Cancelled -> {
                clearConversation()
                return Reply("Okay, cancelled.", endConversation = true)
            }
            PendingContactChoice.Reply.Expired -> {
                trace("contact_choice", ok = false)
                return Reply("That contact choice expired. Who should I contact?", expectAnswer = true)
            }
            PendingContactChoice.Reply.NoPending -> Unit
        }
        pending?.let { (call, at) ->
            if (followUp != null && monotonicMillis() - at in 0 until PENDING_MS) {
                trace("confirm", call)
                return Reply("Please confirm the pending request with yes, or cancel it with no first.", expectAnswer = true)
            }
            pending = null
            if (monotonicMillis() - at in 0 until PENDING_MS) {
                // "No, I said Mother G" fixes a misheard contact instead of cancelling.
                MessageDraft.correctedContact(text)?.takeIf { call.arg("contact") != null }?.let { who ->
                    return runTool(call.copy(args = call.args - "contact_name" + ("contact" to who)), input, "correction")
                }
                if (Persona.isYes(text)) return runTool(call, input, "confirm", confirmed = true)
                if (Persona.isNo(text)) {
                    trace("confirm", call)
                    clearConversation()
                    return Reply("Okay, cancelled.", tool = call.tool, endConversation = true)
                }
            } else if (Persona.isYes(text)) {
                trace("confirm", call, false)
                return Reply("That confirmation expired. Please repeat the request.", expectAnswer = true)
            }
        }
        if (input.source == "follow_up" && rule == null && smallTalk == null && followUp == null && ReplyText.isNoise(text)) {
            trace("follow_up_noise", ok = false)
            JarvisLog.i("follow-up noise dropped: $text")
            return Reply("", endConversation = true)
        }
        smallTalk?.let { trace("smalltalk"); return Reply(it) }
        var query = text
        var contextualQuestion = false
        trace("follow_up")
        when (followUp) {
            is ConversationContext.Resolution.Tool -> return runTool(followUp.call, input, "follow_up")
            is ConversationContext.Resolution.Clarify -> return Reply(followUp.question, expectAnswer = true)
            is ConversationContext.Resolution.Say -> return Reply(followUp.text)
            is ConversationContext.Resolution.Query -> {
                query = followUp.text
                contextualQuestion = true
            }
            null -> Unit
        }
        val forced = cloud.asked(text)   // "think hard …" skips the tools
        if (!forced && !contextualQuestion) {
            val intent = IntentRouter.classify(text, rule)
            JarvisLog.i("route ${intent.route} ${intent.confidence}: ${intent.reason}")
            webRouteCall(text, rule, intent)?.let { return runTool(it, input, "router") }
            ruleRouteCall(text, rule, intent)?.let {
                if (smoke == null) draft.start(it, monotonicMillis())?.let { q -> return Reply(q, expectAnswer = true) }
                JarvisLog.i("route rule ${it.tool} in ${(System.nanoTime() - routingStarted) / 1_000_000} ms; llm=false")
                return runTool(it, input, "rule")
            }
            if (!intent.isConfident || intent.route != IntentRouter.Route.CHAT) {
                // A saved recipe replays known steps with no AI (ROADMAP #9).
                recipes.find(text)?.let { (r, params) ->
                    JarvisLog.i("route recipe ${r.name} (${r.source})")
                    val call = ToolCall("run_recipe", mapOf("name" to r.name, "app" to r.appName, "pkg" to r.pkg,
                        "risky" to r.risky.toString(), "params" to Json.write(params)))
                    return runTool(call, input, "recipe")
                }
                if (!AgentRouting.needsMultiStep(text)) {
                    JarvisLog.i("route llm fallback: rule=${rule?.tool ?: "none"}, reason=${intent.reason}")
                    // Cloud picks in ~1 s when allowed (D-045); NONE means plain talk, null means ask the local model.
                    val picked = if (smoke == null) cloud.toolCall(text, cloudHistory.toList()) else null
                    val said = picked?.takeIf { it.tool == CloudBrain.NONE.tool }?.arg("say")
                    if (said != null && !Persona.isMemoryQuestion(text)) return Reply(said, tool = "cloud", expectAnswer = said.trimEnd().endsWith('?'))
                    if (picked != null && picked.tool != CloudBrain.NONE.tool) return runTool(picked, input, "cloud")
                    if (picked == null) models.toolCall(text, contextLine())?.let { return runTool(it, input, "llm") }
                }
                if (AgentRouting.needsScreen(text)) {
                    if (smoke != null) {
                        val call = ToolCall("screen_agent", mapOf("task" to text))
                        trace("screen_agent", call)
                        val result = smoke.intercept(call)!!
                        return Reply(result.say, tool = call.tool)
                    }
                    return screenReply(screenAgent.run(text, input.locked, input.voiceOk, speak))
                }
            }
        }
        val facts = memory.all().map { it.text }
        // Local replies can contain private notes, notifications or screen text (D-035).
        if (smoke == null) cloud.chat(query, cloudHistory.toList(), facts, forced, speak)?.let {
            return Reply(it.text, tool = "cloud", spokenAlready = it.streamed, expectAnswer = it.text.trimEnd().endsWith('?'))
        }
        trace("chat", ToolCall("chat"))
        models.chat(query, history.toList(), facts, speak)?.let { return Reply(it.text, tool = "chat", spokenAlready = it.streamed, expectAnswer = it.text.trimEnd().endsWith('?')) }
        trace("fallback", ok = false)
        return Reply(Persona.CANT)
    }

    private fun clearConversation() {
        pending = null
        draft.clear()
        timeSlot.clear()
        cloudHistory.clear()
        contactChoice.clear()
        conversation.clear()
    }

    /** Tool replies never speak a package name; use the app label or "that app". */
    private fun plain(text: String) = ReplyText.withoutPackages(text) { pkg ->
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    private fun monotonicMillis() = System.nanoTime() / 1_000_000

    private fun contextLine(): String {
        val now = LocalDateTime.now()
        val app = JarvisAccessibilityService.instance?.appPackage()
        return "time ${now.toLocalDate()} ${now.toLocalTime().withNano(0)}" + (app?.let { ", app $it" } ?: "")
    }

    private fun screenReply(result: AgentResult) = Reply(
        result.summary, tool = "screen_agent", expectAnswer = result.needsConfirmation || result.question,
    )

    private suspend fun runTool(request: ToolCall, input: Input, source: String, confirmed: Boolean = false): Reply {
        var call = request
        val smoke = coroutineContext[SmokeSession]
        smoke?.route = source
        smoke?.call = call
        smoke?.requestedCall = request
        smoke?.ok = true
        if (smoke != null && call.tool in setOf("run_routine", "panic")) {
            val result = smoke.intercept(call)!!
            return Reply(result.say, tool = call.tool)
        }
        when (call.tool) {
            "cancel" -> {
                clearConversation()
                return Reply("Okay.", tool = "cancel", endConversation = true)
            }
            "repeat_last" -> return Reply(lastReply.ifEmpty { "I haven't said anything yet." }, tool = "repeat_last")
            "run_routine" -> return runRoutine(call.arg("name") ?: "", input)
            "panic" -> return panic(input)
        }
        if (source in setOf("llm", "cloud")) call = TimeSlot.asReminder(call, input.heard, LocalDateTime.now())
        // "set an alarm" / "remind me to call mom": ask once for the missing time (ROADMAP §M.5).
        if (smoke == null) timeSlot.start(call, monotonicMillis())?.let { q -> return Reply(q, tool = call.tool, expectAnswer = true) }
        val toSelf = call.tool in setOf("send_sms", "send_whatsapp") && SelfAccount.isSelf(call.arg("contact"))
        if (!confirmed && !toSelf && call.tool in setOf("call", "send_sms", "send_whatsapp")) {
            val who = call.arg("contact") ?: run {
                smoke?.ok = false
                return Reply("Who should I contact?", expectAnswer = true)
            }
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
                ContactResolver.Resolution.NotFound -> {
                    smoke?.ok = false
                    return Reply("I can't find $who in your contacts. Who did you mean?", expectAnswer = true)
                }
            }
        }
        smoke?.call = call
        stage = "tool:${call.tool}"
        val ctx = ToolContext(context, input.heard, input.locked)
        return when (val o = executor.execute(call, ctx, confirmed, input.voiceOk)) {
            is Outcome.NeedConfirm -> {
                pending = call to monotonicMillis()
                val recipient = if (toSelf) "yourself" else call.arg("contact_name")?.let { "$it (${call.arg("contact")})" }
                val question = when {
                    recipient == null -> o.question
                    call.tool == "call" -> "Call $recipient?"
                    call.tool in setOf("send_sms", "send_whatsapp") -> "Send \"${call.arg("text")}\" to $recipient?"
                    else -> o.question
                }
                Reply(question, tool = call.tool, expectAnswer = true)
            }
            is Outcome.Denied -> {
                smoke?.ok = false
                log(input.heard, call, source, ToolResult.fail(o.why))
                Reply(plain(o.why), tool = call.tool)
            }
            is Outcome.Done -> {
                smoke?.ok = o.result.ok
                log(input.heard, call, source, o.result)
                if (o.result.ok) conversation.remember(input.heard, call, o.result.say)
                if (o.result.ok && call.tool in setOf("web_search", "read_page") && o.result.data.isNotBlank()) {
                    val question = call.arg("query") ?: input.heard
                    val answer = try {
                        cloud.webAnswer(question, o.result.data)?.let { ModelBrain.ChatOut(it, streamed = false) }
                            ?: models.chat(question, emptyList(), emptyList(), {}, evidence = o.result.data)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        JarvisLog.e("web answer failed", e)
                        null
                    }
                    val sources = o.result.data.lineSequence().filter { it.startsWith("Source: ") }.distinct().joinToString("\n")
                    val text = answer?.text ?: o.result.say
                    return Reply(text + if (sources.isBlank()) "" else "\n\n$sources", o.result.data, call.tool)
                }
                Reply(plain(o.result.say), o.result.data, call.tool)
            }
        }
    }

    /** Panic word (PLAN 12.4): forget the pending question, lock the screen, refuse risky tools until unlocked. */
    private suspend fun panic(input: Input): Reply {
        clearConversation()
        executor.enterLockdown()
        screenAgent.enterLockdown()
        executor.observeLockState(input.locked)
        val call = ToolCall("lock_phone", emptyMap())
        val r = executor.execute(call, ToolContext(context, input.heard, input.locked), confirmed = true, voiceOk = null)
        log(input.heard, call, "panic", (r as? Outcome.Done)?.result ?: ToolResult.fail("not run"))
        JarvisLog.i("panic: locked down")
        return Reply("Locked down.", tool = "panic", endConversation = true)
    }

    private suspend fun log(heard: String, call: ToolCall, source: String, r: ToolResult) {
        if (coroutineContext[SmokeSession] != null) return
        runCatching {
            toolLog.insert(
                ToolCallLog(
                    timeMs = System.currentTimeMillis(), heard = heard, tool = call.tool, argsJson = Json.write(call.args),
                    source = source, ok = r.ok, result = r.say,
                ),
            )
        }
    }

    /** Runs each step and reports its actual result (PLAN 8.1–8.3). */
    private suspend fun runRoutine(name: String, input: Input): Reply {
        val steps = stepsFor(name) ?: return Reply("I don't have a routine called ${name.replace('_', ' ')}.", tool = "run_routine")
        val says = ArrayList<String>()
        val ctx = ToolContext(context, input.heard, input.locked)
        val stopped = RoutineRunner.run(
            steps,
            execute = { step, confirmed -> executor.execute(step, ctx, confirmed, input.voiceOk) },
            record = { step, result ->
                log(input.heard, step, "routine", result)
                if (result.say.isNotBlank()) says.add(result.say)
            },
        )
        if (stopped != null) return Reply((says + stopped).joinToString(" "), tool = "run_routine")
        val hello = when (name) {
            "good_night" -> "Good night, ${Persona.ADDRESS}."
            "good_morning" -> "Good morning, ${Persona.ADDRESS}."
            else -> "Routine finished."
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
        private val MESSAGE_TOOLS = setOf("send_sms", "send_whatsapp")
        private val WEB_TOOLS = setOf("web_search", "weather", "news", "wiki_lookup", "read_page", "open_url")

        internal fun ruleRouteCall(text: String, rule: ToolCall?, intent: IntentRouter.Decision): ToolCall? {
            if (intent.isConfident && intent.route == IntentRouter.Route.CHAT) return null
            return rule?.takeIf { AgentRouting.acceptsSingleTool(text, it.tool) }
        }

        internal fun webRouteCall(text: String, rule: ToolCall?, intent: IntentRouter.Decision): ToolCall? {
            if (!intent.isConfident || intent.route != IntentRouter.Route.WEB) return null
            if (rule != null && rule.tool !in WEB_TOOLS) return null
            return rule ?: ToolCall("web_search", mapOf("query" to text))
        }

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
