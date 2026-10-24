package com.alrasin.jarvis.brain

import com.alrasin.jarvis.agent.AgentRouting
import com.alrasin.jarvis.core.ToolCall
import java.util.Locale

object IntentRouter {
    enum class Route { CHAT, TOOL, WEB }

    data class Decision(val route: Route, val confidence: Float, val reason: String) {
        val isConfident: Boolean get() = confidence >= CONFIDENCE_THRESHOLD
    }

    const val CONFIDENCE_THRESHOLD = 0.75f

    private val spaces = Regex("\\s+")
    private val punctuation = Regex("[^\\p{L}\\p{N}_' ]")
    private val address = Regex("^(?:(?:hey |ok |okay )?(?:jimi|jimmy|jarvis)[ ,]+|please )+")
    private val polite = Regex("^(?:(?:can|could|would|will) you (?:please )?|i (?:want|would like) you to |please )")
    private val negation = Regex("^(?:(?:do not|don't|dont|never|not) (?!forget\\b)|i (?:do not|don't|dont) want (?:you )?to ).+")
    private val spokenQuote = Regex("^(?:say|repeat) (?:the (?:words|phrase) )?[\"“'].+")
    private val nickname = Regex("^call me (?!back\\b|later\\b|tomorrow\\b|at\\b|on\\b).+")
    private val explanation = Regex(
        "^(?:explain|define|describe the meaning of|tell me (?:about|why|how)|" +
            "show me how|teach me|help me understand|tell me what .+ means|what happens if|" +
            "what (?:does|do) .+ mean|" +
            "how (?:do|can|would|should) i|how (?:does|do) .+ work|why)\\b.*",
    )
    private val definition = Regex("^what (?:is|are) (?:(?:a|an) )?(?:weather|news|bluetooth|wi fi|wifi|timer|alarm|reminder|notifications?|battery|sms|email)$")
    private val creative = Regex(
        "^(?:(?:tell|give|show|read) me (?:a |an |another |some )?(?:bedtime |short )?(?:joke|story|poem|riddle|advice)|" +
            "(?:tell|give|share)(?: me)? (?:a |an |another |some )(?:[a-z]+ ){0,3}facts?\\b|" +
            "(?:write|compose|make up|create) (?:me )?(?:a |an )?(?:poem|story|joke|song|essay|haiku)|" +
            "(?:joke|fun fact|riddle)\\b|let'?s (?:talk|chat)|chat with me|talk to me).*$",
    )
    private val metaphor = Regex(
        "^(?:open (?:the |a )?door to\\b|open (?:your|my) mind\\b|" +
            "play devil'?s advocate\\b|call it a day\\b|kill (?:some )?time\\b|" +
            "find (?:happiness|meaning|peace)\\b|show me (?:a little )?(?:kindness|respect)\\b).*$",
    )
    private val greeting = Regex(
        "^(?:hi|hello|hey|hey there|hi there|hello there|how are you(?: doing)?|" +
            "how'?s it going|how do you feel|what'?s up|thanks|thank you(?: so much)?|" +
            "you'?re welcome|nice to meet you|good afternoon|good evening|" +
            "i (?:am|feel) (?:happy|sad|lonely|bored|tired)|i love you|do you love me)[ !?.]*$",
    )
    private val explicitWeb = Regex(
        "^(?:search(?: the web| the internet| online| google)?(?: for)?|google|" +
            "look up(?: on the web| online)?|browse the web for) .+|^(?:wiki|wikipedia) .+",
    )
    private val liveFacts = Regex(
        "\\b(?:latest (?:(?:cricket|football|soccer|match|game) )?(?:news|headlines|scores?|results?|prices?)|breaking news|" +
            "(?:current|live) (?:scores?|weather|exchange rate|stock price)|" +
            "who won (?:the |today'?s |yesterday'?s )?(?:match|game|election)|" +
            "weather (?:forecast|today|tomorrow)|(?:today'?s|tomorrow'?s) weather)\\b",
    )
    private val question = Regex("^(?:what|what's|whats|who|who's|whos|where|when|why|how|which|is|are|do|does|did|can|could|would|should)\\b")
    private val chatStatement = Regex("^(?:i (?:think|feel|wonder|wish|believe|like|love|hate|am)|i'm|im|let'?s discuss|tell me something)\\b")
    private val deviceQuestion = Regex("\\b(?:my|this|our) (?:phone|device|battery|screen|notifications?|messages?|notes?|reminders?|alarms?|contacts?)\\b")
    private val actionVerbs = setOf(
        "open", "close", "launch", "start", "run", "call", "phone", "ring", "dial", "text", "send",
        "set", "turn", "switch", "enable", "disable", "toggle", "play", "pause", "resume", "stop",
        "cancel", "delete", "remove", "clear", "read", "show", "list", "check", "take", "capture",
        "lock", "unlock", "scroll", "swipe", "tap", "click", "press", "type", "enter", "remember",
        "forget", "remind", "repeat", "compose", "increase", "decrease", "raise", "lower", "mute", "unmute", "navigate",
    )
    private val toolWords = setOf(
        "alarm", "timer", "reminder", "reminders", "wifi", "bluetooth", "flashlight", "torch", "brightness",
        "volume", "battery", "screen", "notifications", "messages", "contacts", "notes", "app", "phone",
        "hotspot", "screenshot", "sms", "whatsapp", "youtube", "spotify", "settings", "calendar", "routine",
    )
    private val chatWords = setOf("joke", "story", "poem", "gravity", "meaning", "explain", "opinion", "advice", "feel", "think")
    private val webWords = setOf("search", "google", "online", "internet", "weather", "news", "headlines", "forecast")
    private val webTools = setOf("web_search", "weather", "news")
    private val capabilityQuestion = Regex("\\bwhat (?:can|could|do) you (?:do|help)\\b")
    private val launchRequest = Regex("^(?:open|launch|start|run|show)(?: the| my)? (.+?)(?: app| application)?$")

    fun isAppLaunchQuestion(text: String): Boolean {
        val normalized = polite.replace(RuleMatcher.normalize(text), "")
        return Regex("\\b(?:what|who|where|when|why|how|which)\\b").containsMatchIn(normalized) ||
            question.containsMatchIn(normalized)
    }

    fun classify(text: String, ruleCall: ToolCall? = RuleMatcher.match(text)): Decision {
        val raw = text.trim().lowercase(Locale.ROOT).replace('’', '\'')
        var normalized = spaces.replace(punctuation.replace(raw, " "), " ").trim()
        normalized = address.replace(normalized, "")
        normalized = polite.replace(normalized, "").removeSuffix(" please").trim()
        if (normalized.isEmpty()) return Decision(Route.CHAT, 0f, "empty input")

        // These guards precede broad rules such as open_app, call and type_text.
        val quoted = raw.firstOrNull() in setOf('"', '“', '\'') && raw.lastOrNull() in setOf('"', '”', '\'')
        if (quoted || spokenQuote.matches(raw) || negation.matches(normalized)) return Decision(Route.CHAT, 0.98f, "quoted or negated request")
        if (nickname.matches(normalized) || metaphor.matches(normalized)) return Decision(Route.CHAT, 0.98f, "conversational expression")
        if (explanation.matches(normalized) || definition.matches(normalized) || creative.matches(normalized)) return Decision(Route.CHAT, 0.96f, "explanation or creative request")
        if (greeting.matches(normalized)) return Decision(Route.CHAT, 0.98f, "conversation")
        if (capabilityQuestion.containsMatchIn(normalized) && ruleCall?.tool != "app_abilities") return Decision(Route.CHAT, 0.98f, "capability question")

        val launchApp = when (ruleCall?.tool) {
            "open_app" -> ruleCall.arg("app")
            null -> launchRequest.matchEntire(normalized)?.groupValues?.get(1)
            else -> null
        }
        // "open messenger and find …" is a screen task, not an unknown app called "messenger and find".
        if (launchApp != null && !AgentRouting.needsMultiStep(normalized) && (isAppLaunchQuestion(normalized) ||
                (ruleCall == null && RuleMatcher.appNameMatches?.invoke(launchApp) == false))) {
            return Decision(Route.CHAT, 0.96f, "unmatched app or app question")
        }

        val first = normalized.substringBefore(' ')
        val directAction = first in actionVerbs
        if (explicitWeb.matches(normalized) || (!directAction && liveFacts.containsMatchIn(normalized))) {
            return Decision(Route.WEB, 0.96f, "explicit search or current information")
        }

        if (ruleCall != null) {
            if (ruleCall.tool in webTools) return Decision(Route.WEB, 0.96f, "web rule: ${ruleCall.tool}")
            // Generic factual questions stay offline; explicit Wikipedia requests retain their tool.
            if (ruleCall.tool == "wiki_lookup" && question.containsMatchIn(normalized)) {
                return Decision(Route.CHAT, 0.92f, "general knowledge question")
            }
            return Decision(Route.TOOL, 0.97f, "tool rule: ${ruleCall.tool}")
        }

        val tokens = normalized.split(' ').toSet()
        if (!chatStatement.containsMatchIn(normalized) && tokens.any { it in setOf(
                "battery", "alarm", "alarms", "timer", "volume", "flashlight", "torch", "wifi", "bluetooth", "brightness",
            ) } && tokens.none { it in webWords }) {
            return Decision(Route.TOOL, 0.84f, "local tool keyword")
        }
        var chat = 0
        var tool = 0
        var web = 0
        if (tokens.size >= 3 && question.containsMatchIn(normalized)) chat += 5
        if (chatStatement.containsMatchIn(normalized)) chat += 5
        if (tokens.any { it in chatWords }) chat += 2
        if (directAction) tool += 4
        if (tokens.any { it in toolWords }) tool += 2
        if (deviceQuestion.containsMatchIn(normalized)) tool += 3
        if (tokens.any { it in webWords }) web += 2
        if (tokens.any { it == "latest" || it == "current" || it == "today" || it == "online" }) web += 2
        if (tokens.any { '_' in it && it.substringBefore('_') in actionVerbs }) tool += 4
        val scores = listOf(Route.CHAT to chat, Route.TOOL to tool, Route.WEB to web).sortedByDescending { it.second }
        val (route, score) = scores.first()
        val margin = score - scores[1].second
        val confidence = if (score >= 4 && margin >= 3) {
            (0.76f + (score - 4) * 0.03f).coerceAtMost(0.91f)
        } else {
            (0.35f + score * 0.04f + margin * 0.02f).coerceAtMost(0.69f)
        }
        return Decision(route, confidence, "scores chat=$chat tool=$tool web=$web")
    }
}
