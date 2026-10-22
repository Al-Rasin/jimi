package com.alrasin.jarvis.agent

import com.alrasin.jarvis.core.Json

sealed interface AgentAction {
    data class OpenApp(val packageName: String) : AgentAction
    data class Tap(val element: Int) : AgentAction
    data class Type(val element: Int, val text: String) : AgentAction
    data class Scroll(val direction: String) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data object Wait : AgentAction
    data class Done(val summary: String) : AgentAction
    data class AskUser(val question: String) : AgentAction

    fun label(): String = when (this) {
        is OpenApp -> "Open $packageName"
        is Tap -> "Tap #$element"
        is Type -> "Type into #$element"
        is Scroll -> "Scroll $direction"
        Back -> "Back"
        Home -> "Home"
        Wait -> "Wait"
        is Done -> "Done"
        is AskUser -> "Ask user"
    }

    companion object {
        fun parse(json: String): AgentAction? = runCatching {
            require(json.length <= 8192)
            val obj = Json.parse(json) as? Map<*, *> ?: return null
            fun str(key: String): String = (obj[key] as? String)?.takeIf { it.length <= 2000 }
                ?: error("Missing $key")
            fun index(): Int {
                val value = obj["element"] as? Long ?: error("Invalid element")
                require(value in 1..60)
                return value.toInt()
            }
            fun keys(vararg names: String) { require(obj.keys == setOf("action", *names)) }
            when (str("action")) {
                "open_app" -> { keys("package"); OpenApp(str("package").also {
                    require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                }) }
                "tap" -> { keys("element"); Tap(index()) }
                "type" -> { keys("element", "text"); Type(index(), str("text")) }
                "scroll" -> { keys("direction"); Scroll(str("direction").also {
                    require(it in setOf("up", "down", "left", "right"))
                }) }
                "back" -> { keys(); Back }
                "home" -> { keys(); Home }
                "wait" -> { keys(); Wait }
                "done" -> { keys("summary"); Done(str("summary").also { require(it.isNotBlank()) }) }
                "ask_user" -> { keys("question"); AskUser(str("question").also { require(it.isNotBlank()) }) }
                else -> null
            }
        }.getOrNull()
    }
}
