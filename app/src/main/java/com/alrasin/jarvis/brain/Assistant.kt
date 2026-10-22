package com.alrasin.jarvis.brain

import android.content.Context
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

    /** [endConversation]: no follow-up listening (user said cancel / bye). [expectAnswer]: a yes/no question was asked. */
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
            lastReply = r.text
            history.addLast(Exchange(text, r.text))
            while (history.size > 6) history.removeFirst()
        }
        r.copy(turnId = id)
    }

    private suspend fun route(text: String, input: Input, speak: (String) -> Unit): Reply {
        pending?.let { (call, at) ->
            pending = null
            if (System.currentTimeMillis() - at < PENDING_MS) {
                if (Persona.isYes(text)) return runTool(call, input, "confirm", confirmed = true)
                if (Persona.isNo(text)) return Reply("Okay, cancelled.", tool = call.tool)
            }
        }
        Persona.smallTalk(text, LocalDateTime.now().hour)?.let {
            return Reply(it, endConversation = it.startsWith("Bye"))
        }
        val forced = cloud.asked(text)   // "think hard …" skips the tools
        if (!forced) {
            val rule = RuleMatcher.match(text)
            val intent = IntentRouter.classify(text, rule)
            JarvisLog.i("route ${intent.route} ${intent.confidence}: ${intent.reason}")
            if (intent.isConfident && intent.route == IntentRouter.Route.WEB) {
                val call = rule?.takeIf { it.tool in WEB_TOOLS }
                    ?: ToolCall("web_search", mapOf("query" to text))
                return runTool(call, input, "router")
            }
            if (!intent.isConfident || intent.route != IntentRouter.Route.CHAT) {
                rule?.let { return runTool(it, input, "rule") }
                models.toolCall(text, contextLine())?.let { return runTool(it, input, "llm") }
            }
        }
        val facts = memory.all().map { it.text }
        // Local replies can contain private notes, notifications or screen text (D-035).
        cloud.chat(text, emptyList(), facts, forced)?.let { return Reply(it, tool = "cloud") }
        models.chat(text, history.toList(), facts, speak)?.let { return Reply(it.text, tool = "chat", spokenAlready = it.streamed) }
        return Reply(Persona.CANT)
    }

    private fun contextLine(): String {
        val now = LocalDateTime.now()
        val app = JarvisAccessibilityService.instance?.appPackage()
        return "time ${now.toLocalDate()} ${now.toLocalTime().withNano(0)}" + (app?.let { ", app $it" } ?: "")
    }

    private suspend fun runTool(call: ToolCall, input: Input, source: String, confirmed: Boolean = false): Reply {
        when (call.tool) {
            "cancel" -> return Reply("Okay.", tool = "cancel", endConversation = true)
            "repeat_last" -> return Reply(lastReply.ifEmpty { "I haven't said anything yet." }, tool = "repeat_last")
            "run_routine" -> return runRoutine(call.arg("name") ?: "", input)
            "panic" -> return panic(input)
        }
        val ctx = ToolContext(context, input.heard, input.locked)
        return when (val o = executor.execute(call, ctx, confirmed, input.voiceOk)) {
            is Outcome.NeedConfirm -> {
                pending = call to System.currentTimeMillis()
                Reply(o.question, tool = call.tool, expectAnswer = true)
            }
            is Outcome.Denied -> {
                log(input.heard, call, source, ToolResult.fail(o.why))
                Reply(o.why, tool = call.tool)
            }
            is Outcome.Done -> {
                log(input.heard, call, source, o.result)
                if (o.result.ok && call.tool in setOf("web_search", "read_page") && o.result.data.isNotBlank()) {
                    val answer = try {
                        models.chat(call.arg("query") ?: input.heard, emptyList(), emptyList(), {}, evidence = o.result.data)
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
                Reply(o.result.say, o.result.data, call.tool)
            }
        }
    }

    /** Panic word (PLAN 12.4): forget the pending question, lock the screen, refuse risky tools until unlocked. */
    private suspend fun panic(input: Input): Reply {
        pending = null
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
        private val WEB_TOOLS = setOf("web_search", "weather", "news", "wiki_lookup", "read_page", "open_url")

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
