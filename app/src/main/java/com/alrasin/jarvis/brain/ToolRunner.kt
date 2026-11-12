package com.alrasin.jarvis.brain

import android.content.Context
import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.SelfAccount
import com.alrasin.jarvis.actions.ToolExecutor
import com.alrasin.jarvis.agent.ScreenAgentSession
import com.alrasin.jarvis.brain.Assistant.Input
import com.alrasin.jarvis.brain.Assistant.Reply
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.SmokeSession
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.Routine
import com.alrasin.jarvis.data.RoutineDao
import com.alrasin.jarvis.data.ToolCallLog
import com.alrasin.jarvis.data.ToolLogDao
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import kotlin.coroutines.coroutineContext

/** Runs one chosen tool call for [Assistant]: contact lookup, confirm questions, logging, web answers, chains, routines. */
internal class ToolRunner(
    private val context: Context,
    private val executor: ToolExecutor,
    private val toolLog: ToolLogDao,
    private val routines: RoutineDao,
    private val screenAgent: ScreenAgentSession,
    private val cloud: CloudBrain,
    private val models: ModelBrain,
    private val state: TalkState,
) {
    private val contacts = Contacts(context)

    /** Tool replies never speak a package name; use the app label or "that app". */
    private fun plain(text: String) = ReplyText.withoutPackages(text) { pkg ->
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    suspend fun run(request: ToolCall, input: Input, source: String, confirmed: Boolean = false): Reply {
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
                state.clear()
                return Reply("Okay.", tool = "cancel", endConversation = true)
            }
            "repeat_last" -> return Reply(state.lastReply.ifEmpty { "I haven't said anything yet." }, tool = "repeat_last")
            "run_routine" -> return runRoutine(call.arg("name") ?: "", input)
            "panic" -> return panic(input)
        }
        if (source in setOf("llm", "cloud")) call = TimeSlot.asReminder(call, input.heard, LocalDateTime.now())
        // "set an alarm" / "remind me to call mom": ask once for the missing time (ROADMAP §M.5).
        if (smoke == null) state.timeSlot.start(call, monotonicMillis())?.let { q -> return Reply(q, tool = call.tool, expectAnswer = true) }
        val toSelf = call.tool in setOf("send_sms", "send_whatsapp") && SelfAccount.isSelf(call.arg("contact"))
        if (!confirmed && !toSelf && call.tool in setOf("call", "send_sms", "send_whatsapp")) {
            val who = call.arg("contact") ?: run {
                smoke?.ok = false
                return Reply("Who should I contact?", expectAnswer = true)
            }
            when (val resolved = contacts.resolve(who)) {
                is ContactResolver.Resolution.Ambiguous -> {
                    state.pending = null
                    state.conversation.clear()
                    return Reply(state.contactChoice.begin(call, resolved).question, tool = call.tool, expectAnswer = true)
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
        state.stage = "tool:${call.tool}"
        val ctx = ToolContext(context, input.heard, input.locked)
        return when (val o = executor.execute(call, ctx, confirmed, input.voiceOk)) {
            is Outcome.NeedConfirm -> {
                state.pending = call to monotonicMillis()
                val recipient = if (toSelf) "yourself" else call.arg("contact_name")?.let { "$it (${call.arg("contact")})" }
                val question = when {
                    recipient == null -> o.question
                    call.tool == "call" -> "Call $recipient?"
                    call.tool in setOf("send_sms", "send_whatsapp") -> "Send \"${call.arg("text")}\" to $recipient?"
                    else -> o.question
                }
                state.pendingQuestion = question
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
                if (o.result.ok) state.conversation.remember(input.heard, call, o.result.say)
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
        state.clear()
        executor.enterLockdown()
        screenAgent.enterLockdown()
        executor.observeLockState(input.locked)
        val call = ToolCall("lock_phone", emptyMap())
        val r = executor.execute(call, ToolContext(context, input.heard, input.locked), confirmed = true, voiceOk = null)
        log(input.heard, call, "panic", (r as? Outcome.Done)?.result ?: ToolResult.fail("not run"))
        JarvisLog.i("panic: locked down")
        return Reply("Locked down. Unlock with your PIN to end panic mode.", tool = "panic", endConversation = true)
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

    /** Runs a task chain step by step and speaks once at the end; a question (confirm, which contact) stops it. */
    suspend fun runChain(plan: TaskChain.Plan, input: Input): Reply {
        JarvisLog.i("route chain: ${plan.steps.joinToString(" → ") { it.call!!.tool }}")
        val says = ArrayList<String>()
        for ((i, step) in plan.steps.withIndex()) {
            val reply = run(step.call!!, input, "chain")
            if (reply.text.isNotBlank()) says += reply.text
            if (reply.expectAnswer) {
                val left = plan.steps.drop(i + 1).joinToString(" and ") { it.text }
                if (left.isNotEmpty()) says.add(says.size - 1, "Then I stopped before: $left.")
                return reply.copy(text = says.joinToString(" "), tool = "chain")
            }
            if (step.call.tool in setOf("lock_phone", "unlock_phone") && i < plan.steps.lastIndex) {
                says += "I stopped after changing the lock. Say the rest again."
                break
            }
            if (step.call.tool == "open_app" && coroutineContext[SmokeSession] == null) kotlinx.coroutines.delay(800)
        }
        return Reply(says.joinToString(" "), tool = "chain")
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
        val json = routines.get(name)?.takeIf { it.enabled }?.stepsJson ?: Routines.DEFAULT_ROUTINES[name]?.let { Routines.stepsJson(it) } ?: return null
        return (Json.parse(json) as List<*>).mapNotNull { (it as? Map<*, *>)?.let { m -> ToolCall.fromJson(Json.write(m)) } }
    }

    suspend fun saveRoutine(name: String, steps: List<ToolCall>) = routines.put(Routine(name, Routines.stepsJson(steps)))
}
