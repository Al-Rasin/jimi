package com.alrasin.jarvis.brain

import android.content.Context
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.ToolExecutor
import com.alrasin.jarvis.agent.AgentResult
import com.alrasin.jarvis.agent.AgentRouting
import com.alrasin.jarvis.agent.RecipeStore
import com.alrasin.jarvis.agent.ScreenAgentSession
import com.alrasin.jarvis.agent.RecipeBook
import com.alrasin.jarvis.agent.TeachBook
import com.alrasin.jarvis.agent.TeachSession
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.SmokeSession
import kotlin.coroutines.coroutineContext
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.data.ConversationTurn
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.RoutineDao
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
    private val teach: TeachSession,
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
    private val state = TalkState()
    private val tools = ToolRunner(context, executor, toolLog, routines, screenAgent, cloud, models, state)
    private var smokePending: Pair<ToolCall, Long>? = null
    private var smokeLastReply = ""
    private val smokeHistory = ArrayDeque<Exchange>()
    private val smokeContactChoice = PendingContactChoice()
    private val smokeConversation = ConversationContext()
    private var lastTurnAt = 0L

    fun stopScreenAgent() = screenAgent.stop()

    /** [speak] is called for chat sentences while the model is still writing. */
    suspend fun handle(input: Input, speak: (String) -> Unit = {}): Reply {
        val smoke = coroutineContext[SmokeSession]
        if (smoke == null && RuleMatcher.interrupt(input.heard) != null) {
            screenAgent.stop()
        }
        return lock.withLock {
        if (smoke == null) return@withLock handleLocked(input, speak)
        val liveContactChoice = state.contactChoice
        val liveConversation = state.conversation
        state.contactChoice = smokeContactChoice
        state.conversation = smokeConversation
        val livePending = state.pending
        val liveReply = state.lastReply
        val liveHistory = state.history.toList()
        state.pending = smokePending
        state.lastReply = smokeLastReply
        state.history.clear()
        state.history.addAll(smokeHistory)
        try {
            handleLocked(input, {})
        } finally {
            smokePending = state.pending
            smokeLastReply = state.lastReply
            smokeHistory.clear()
            smokeHistory.addAll(state.history)
            state.contactChoice = liveContactChoice
            state.conversation = liveConversation
            state.pending = livePending
            state.lastReply = liveReply
            state.history.clear()
            state.history.addAll(liveHistory)
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
        state.stage = "start"
        val now = System.currentTimeMillis()
        // A new talk after a long break starts fresh, so old turns are not mixed into a new topic.
        if (smoke == null && now - lastTurnAt > HISTORY_IDLE_MS) state.history.clear()
        if (smoke == null) lastTurnAt = now
        val r = if (text.isEmpty()) Reply(Persona.DIDNT_GET) else runCatching { route(text, input, speak) }
            .onFailure { if (it is CancellationException) throw it; JarvisLog.e("assistant failed", it) }
            .getOrElse { smoke?.ok = false; smoke?.route = "error"; Reply(ReplyText.routeFailed(state.stage)) }
        val id = if (smoke != null) 0L else turns.insert(
            ConversationTurn(
                timeMs = System.currentTimeMillis(), source = input.source, heard = text, rawText = input.raw,
                audioPath = input.audioPath, reply = r.text, tool = r.tool, wakeId = input.wakeId,
            ),
        )
        if (r.text.isNotBlank()) {
            if (r.endConversation) state.clear()
            else if (r.tool in setOf("chat", "cloud")) state.conversation.remember(text, null, r.text)
            state.lastReply = r.text
            state.history.addLast(Exchange(text, r.text))
            while (state.history.size > 6) state.history.removeFirst()
            if (r.tool == "cloud") {
                state.cloudHistory.addLast(Exchange(text, r.text))
                while (state.cloudHistory.size > 4) state.cloudHistory.removeFirst()
            }
        }
        return r.copy(turnId = id)
    }

    private suspend fun route(text: String, input: Input, speak: (String) -> Unit): Reply {
        val smoke = coroutineContext[SmokeSession]
        fun trace(route: String, call: ToolCall? = null, ok: Boolean = true) {
            state.stage = route
            smoke?.route = route
            smoke?.call = call
            smoke?.ok = ok
        }
        val routingStarted = System.nanoTime()
        val rule = RuleMatcher.match(text)
        if (smoke == null && rule?.tool != "panic") teachCall(text, rule)?.let { return tools.run(it, input, "teach") }
        if (rule?.tool in setOf("panic", "cancel")) return tools.run(rule!!, input, "rule")
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
            when (val d = state.draft.answer(text, monotonicMillis(), newCommand = rule != null && rule.tool !in MESSAGE_TOOLS)) {
                is MessageDraft.Answer.Ready -> return tools.run(d.call, input, "draft")
                is MessageDraft.Answer.Ask -> return Reply(d.question, expectAnswer = true)
                MessageDraft.Answer.Cancelled -> {
                    state.clear()
                    return Reply("Okay, cancelled.", endConversation = true)
                }
                MessageDraft.Answer.None -> Unit
            }
            when (val t = state.timeSlot.answer(text, monotonicMillis(), newCommand = rule != null)) {
                is TimeSlot.Answer.Ready -> return tools.run(t.call, input, "time_slot")
                is TimeSlot.Answer.Ask -> return Reply(t.question, expectAnswer = true)
                TimeSlot.Answer.Cancelled -> {
                    state.clear()
                    return Reply("Okay, cancelled.", endConversation = true)
                }
                TimeSlot.Answer.GaveUp -> return Reply(TimeSlot.GAVE_UP)
                TimeSlot.Answer.None -> Unit
            }
        }
        trace("smalltalk")
        val smallTalk = Persona.smallTalk(text, LocalDateTime.now().hour)
        if (smallTalk?.startsWith("Bye") == true) return Reply(smallTalk, endConversation = true)
        val followUp = state.conversation.resolve(text)
        trace("contact_choice")
        when (val choice = state.contactChoice.answer(text, newRequest = rule != null || smallTalk != null || followUp != null || cloud.asked(text))) {
            is PendingContactChoice.Reply.Selection -> return tools.run(choice.call, input, "contact_choice")
            is PendingContactChoice.Reply.Prompt -> return Reply(choice.question, expectAnswer = true)
            PendingContactChoice.Reply.Cancelled -> {
                state.clear()
                return Reply("Okay, cancelled.", endConversation = true)
            }
            PendingContactChoice.Reply.Expired -> {
                trace("contact_choice", ok = false)
                return Reply("That contact choice expired. Who should I contact?", expectAnswer = true)
            }
            is PendingContactChoice.Reply.GaveUp -> {
                trace("contact_choice", ok = false)
                state.clear()
                return Reply("I couldn't tell which one. Here they are — say the full name or the number:\n${choice.list}")
            }
            PendingContactChoice.Reply.NoPending -> Unit
        }
        state.pending?.let { (call, at) ->
            if (followUp != null && monotonicMillis() - at in 0 until PENDING_MS) {
                trace("confirm", call)
                return Reply("Please confirm the pending request with yes, or cancel it with no first.", expectAnswer = true)
            }
            state.pending = null
            if (monotonicMillis() - at in 0 until PENDING_MS) {
                // "No, I said Mother G" fixes a misheard contact instead of cancelling.
                MessageDraft.correctedContact(text)?.takeIf { call.arg("contact") != null }?.let { who ->
                    return tools.run(call.copy(args = call.args - "contact_name" + ("contact" to who)), input, "correction")
                }
                if (Persona.isYes(text)) return tools.run(call, input, "confirm", confirmed = true)
                if (Persona.isNo(text)) {
                    trace("confirm", call)
                    state.clear()
                    return Reply("Okay, cancelled.", tool = call.tool, endConversation = true)
                }
            } else if (Persona.isYes(text)) {
                trace("confirm", call, false)
                // A late "yes" (dialog still on screen) asks again instead of dropping the request.
                val question = state.pendingQuestion
                if (question != null && monotonicMillis() - at in 0 until LATE_YES_MS) {
                    state.pending = call to monotonicMillis()
                    return Reply("That took a while, so once more: $question", tool = call.tool, expectAnswer = true)
                }
                return Reply("That confirmation expired. Say the request again.", expectAnswer = true)
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
            is ConversationContext.Resolution.Tool -> return tools.run(followUp.call, input, "follow_up")
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
            // A task the user taught by hand beats rules: they named it themselves (§H.6).
            if (smoke == null && RecipeBook.match(text, teach.taught().map { RecipeBook.fromRow(it, it.pkg) }) != null) {
                recipes.find(text)?.takeIf { it.recipe.source == TeachBook.SOURCE }?.let { (r, params) ->
                    JarvisLog.i("route taught recipe ${r.name}")
                    val call = ToolCall("run_recipe", mapOf("name" to r.name, "app" to r.appName, "pkg" to r.pkg,
                        "risky" to r.risky.toString(), "params" to Json.write(params)))
                    return tools.run(call, input, "recipe")
                }
            }
            // "turn on wifi and then open youtube": every part a known tool → run them in order, no AI (§H.1).
            val chain = AssistantRouting.chainPlan(text, rule, intent)
            if (chain?.complete == true) return tools.runChain(chain, input)
            AssistantRouting.webRouteCall(text, rule, intent)?.let { return tools.run(it, input, "router") }
            AssistantRouting.ruleRouteCall(text, rule, intent)?.let {
                if (smoke == null) state.draft.start(it, monotonicMillis())?.let { q -> return Reply(q, expectAnswer = true) }
                JarvisLog.i("route rule ${it.tool} in ${(System.nanoTime() - routingStarted) / 1_000_000} ms; llm=false")
                return tools.run(it, input, "rule")
            }
            if (!intent.isConfident || intent.route != IntentRouter.Route.CHAT) {
                // A saved recipe replays known steps with no AI (ROADMAP #9).
                recipes.find(text)?.let { (r, params) ->
                    JarvisLog.i("route recipe ${r.name} (${r.source})")
                    val call = ToolCall("run_recipe", mapOf("name" to r.name, "app" to r.appName, "pkg" to r.pkg,
                        "risky" to r.risky.toString(), "params" to Json.write(params)))
                    return tools.run(call, input, "recipe")
                }
                if (chain != null && chain.known.isNotEmpty() && !AgentRouting.needsScreen(text) && (smoke != null || !cloud.enabled())) {
                    trace("chain_partial", ok = false)
                    return Reply(TaskChain.partialReply(chain), tool = "chain")
                }
                // "My wife's name is Sara" in plain chat: save it; the user can say "forget that" (§K 10.1).
                FactSpotter.spot(text)?.let { fact -> return tools.run(ToolCall("remember_fact", mapOf("text" to fact)), input, "spotted") }
                if (!AgentRouting.needsMultiStep(text)) {
                    JarvisLog.i("route llm fallback: rule=${rule?.tool ?: "none"}, reason=${intent.reason}")
                    // Cloud picks in ~1 s when allowed (D-045); NONE means plain talk, null means ask the local model.
                    val picked = if (smoke == null) cloud.toolCall(text, state.cloudHistory.toList()) else null
                    val said = picked?.takeIf { it.tool == CloudBrain.NONE.tool }?.arg("say")
                    if (said != null && !Persona.isMemoryQuestion(text)) return Reply(said, tool = "cloud", expectAnswer = said.trimEnd().endsWith('?'))
                    if (picked != null && picked.tool != CloudBrain.NONE.tool) return tools.run(picked, input, "cloud")
                    if (picked == null) models.toolCall(text, contextLine())?.let { return tools.run(it, input, "llm") }
                    // Local model unsure or missing and the router was unsure too: one cloud try if cloud is on (§K 14.2).
                    val backup = if (picked == null && smoke == null && !intent.isConfident) cloud.toolCall(text, state.cloudHistory.toList(), backup = true) else null
                    val backupSaid = backup?.takeIf { it.tool == CloudBrain.NONE.tool }?.arg("say")
                    if (backupSaid != null && !Persona.isMemoryQuestion(text)) return Reply(backupSaid, tool = "cloud", expectAnswer = backupSaid.trimEnd().endsWith('?'))
                    if (backup != null && backup.tool != CloudBrain.NONE.tool) return tools.run(backup, input, "cloud")
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
        if (smoke == null) cloud.chat(query, state.cloudHistory.toList(), facts, forced, speak)?.let {
            return Reply(it.text, tool = "cloud", spokenAlready = it.streamed, expectAnswer = it.text.trimEnd().endsWith('?'))
        }
        trace("chat", ToolCall("chat"))
        models.chat(query, state.history.toList(), facts, speak)?.let { return Reply(it.text, tool = "chat", spokenAlready = it.streamed, expectAnswer = it.text.trimEnd().endsWith('?')) }
        // No local chat model (or it failed): cloud as backup when it is on (§K 14.2).
        if (smoke == null) cloud.chat(query, state.cloudHistory.toList(), facts, forced, speak, backup = true)?.let {
            return Reply(it.text, tool = "cloud", spokenAlready = it.streamed, expectAnswer = it.text.trimEnd().endsWith('?'))
        }
        trace("fallback", ok = false)
        return Reply(Persona.CANT)
    }

    private fun teachCall(text: String, rule: ToolCall?): ToolCall? =
        AssistantRouting.teachCallFor(text, rule, teach.active) { RuleMatcher.appNameMatches?.invoke(it) == true }

    /** Phone state for the LOCAL tool picker only (§K 9.5); screen text never goes to the cloud (D-035). */
    private fun contextLine(): String {
        val now = LocalDateTime.now()
        val a11y = JarvisAccessibilityService.instance
        val battery = runCatching {
            context.getSystemService(android.os.BatteryManager::class.java)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        val screen = runCatching { a11y?.screenText(SCREEN_CHARS * 2) }.getOrNull()
        return contextLine("${now.toLocalDate()} ${now.toLocalTime().withNano(0)}", a11y?.appPackage(), battery, screen)
    }

    private fun screenReply(result: AgentResult) = Reply(
        result.summary, tool = "screen_agent", expectAnswer = result.needsConfirmation || result.question,
    )

    suspend fun stepsFor(name: String): List<ToolCall>? = tools.stepsFor(name)

    suspend fun saveRoutine(name: String, steps: List<ToolCall>) = tools.saveRoutine(name, steps)

    companion object {
        private const val SCREEN_CHARS = 200

        internal fun contextLine(time: String, app: String?, battery: Int?, screen: String?): String {
            val snippet = screen?.replace(Regex("\\s+"), " ")?.trim()?.take(SCREEN_CHARS).orEmpty()
            return "time $time" + (app?.let { ", app $it" } ?: "") +
                (battery?.takeIf { it in 0..100 }?.let { ", battery $it%" } ?: "") +
                (if (snippet.isEmpty()) "" else ", screen: $snippet")
        }

        private const val PENDING_MS = 30_000L
        private const val LATE_YES_MS = 180_000L
        private const val HISTORY_IDLE_MS = 5 * 60_000L
        private val MESSAGE_TOOLS = setOf("send_sms", "send_whatsapp")
    }
}
