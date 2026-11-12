package com.alrasin.jarvis.brain

import com.alrasin.jarvis.agent.AgentRouting
import com.alrasin.jarvis.agent.TeachCommand
import com.alrasin.jarvis.core.ToolCall

/** Pure route picks for [Assistant]: chains, single rules, web, teach mode. */
internal object AssistantRouting {
    val WEB_TOOLS = setOf("web_search", "weather", "news", "wiki_lookup", "read_page", "open_url", "currency", "prayer_times")

    /** Tools that can grab a whole "X and Y" sentence by one loose word, so a full chain beats them. */
    val LOOSE_TOOLS = setOf("toggle", "open_app", "set_volume", "set_brightness", "go_home", "go_back")

    /** A chain plan when the sentence has parts; a matching non-loose rule for the whole sentence wins (it may own the "and"). */
    internal fun chainPlan(text: String, rule: ToolCall?, intent: IntentRouter.Decision): TaskChain.Plan? {
        if (intent.isConfident && intent.route == IntentRouter.Route.CHAT) return null
        if (rule != null && rule.tool !in LOOSE_TOOLS && AgentRouting.acceptsSingleTool(text, rule.tool)) return null
        return TaskChain.plan(text) { RuleMatcher.match(it) }
    }

    internal fun ruleRouteCall(text: String, rule: ToolCall?, intent: IntentRouter.Decision): ToolCall? {
        if (intent.isConfident && intent.route == IntentRouter.Route.CHAT) return null
        return rule?.takeIf { AgentRouting.acceptsSingleTool(text, it.tool) }
    }

    /** Teach mode commands: done / cancel while recording, or "learn order coffee" to start. */
    internal fun teachCallFor(text: String, rule: ToolCall?, active: Boolean, isApp: (String) -> Boolean): ToolCall? {
        if (active) when (TeachCommand.duringTeach(text)) {
            TeachCommand.Cmd.Done -> return ToolCall("teach_done", emptyMap())
            TeachCommand.Cmd.Cancel -> return ToolCall("teach_cancel", emptyMap())
            else -> Unit
        }
        if (rule != null && rule.tool != "learn_app") return null
        return TeachCommand.startOrNull(text, isApp)?.let { ToolCall("teach_start", mapOf("task" to it.name)) }
    }

    internal fun webRouteCall(text: String, rule: ToolCall?, intent: IntentRouter.Decision): ToolCall? {
        if (!intent.isConfident || intent.route != IntentRouter.Route.WEB) return null
        if (rule != null && rule.tool !in WEB_TOOLS) return null
        return rule ?: ToolCall("web_search", mapOf("query" to text))
    }
}
