package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/** RuleMatcher maps, directions and "go to X in app" rules (ROADMAP §H.2). */
internal object MapRules {
    private fun call(tool: String, vararg args: Pair<String, String?>) = RuleMatcher.call(tool, *args)

    /** Maps (§H.2): "navigate to gulshan 2", "walking directions to the mosque", "show dhanmondi lake on the map". */
    fun maps(t: String): ToolCall? {
        val travel = "walking|walk|on foot|foot|driving|drive|car|cycling|bicycle|bike|motorbike|motorcycle|scooter"
        Regex("^(?:(?:get|give me|show me|start) )?(?:($travel) )?(?:directions|route|navigation|the way) to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[2], it.groupValues[1].ifEmpty { it.groupValues[3] })
        }
        Regex("^(?:navigate|(walk|drive|cycle|ride)) (?:me )?to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[2], it.groupValues[1].ifEmpty { it.groupValues[3] })
        }
        Regex("^(?:how far(?: away)? is|whats the distance to|what is the distance to|distance to|how far (?:is it|am i) (?:to|from)|how long (?:does it take |will it take )?to (?:get|go) to) (?:the )?(.+?)(?: from here)?(?: (?:by|on) ($travel))?$").find(t)?.let {
            val p = place(it.groupValues[1])?.takeUnless { p -> NOT_ON_MAP.containsMatchIn(p) } ?: return null
            return navigate(p, it.groupValues[2])?.let { c -> call("open_map", "place" to p, "mode" to "distance", "travel" to c.arg("travel")) }
        }
        Regex("^how (?:do i|can i|to|do we|can we) (?:get|go) to (.+?)(?: (?:by|on) ($travel))?$").find(t)?.let {
            return navigate(it.groupValues[1], it.groupValues[2])
        }
        MAPS_RE.find(t)?.let {
            return place(it.groupValues[1])?.let { p -> call("open_map", "place" to p, "mode" to "show") }
        }
        MAPS_RE_2.find(t)?.let {
            return place(it.groupValues[1])?.let { p -> call("open_map", "place" to p, "mode" to "show") }
        }
        return null
    }

    // "how far is the moon from earth" is a question for the brain, not a map.
    private val NOT_ON_MAP = Regex("\\bfrom\\b|^(?:it|that|this|there)$|\\b(?:moon|sun|mars|earth|space|planet|stars?|galaxy|jupiter|venus|saturn|mercury|pluto|universe)\\b")

    private fun navigate(place: String, travel: String): ToolCall? {
        val p = place(place) ?: return null
        val mode = when {
            travel.isEmpty() -> null
            NAVIGATE_RE.containsMatchIn(travel) -> "walk"
            NAVIGATE_RE_2.containsMatchIn(travel) -> "motorbike"
            NAVIGATE_RE_3.containsMatchIn(travel) -> "bike"
            else -> "drive"
        }
        return call("open_map", "place" to p, "mode" to "navigate", "travel" to mode)
    }

    /** Not a place: phone screens ("navigate to wifi settings") and app names stay with the app/screen tools. */
    private fun place(raw: String): String? {
        val p = raw.trim().removePrefix("the ").trim()
        if (p.isEmpty() || PLACE_RE.containsMatchIn(p)) return null
        if (RuleMatcher.appNameMatches?.invoke(p) == true) return null
        val inApp = PLACE_RE_2.find(p)?.groupValues?.get(1)
        if (inApp != null && !inApp.matches(PLACE_RE_3) && RuleMatcher.appNameMatches?.invoke(inApp) == true) return null
        return p
    }

    /** App map (ROADMAP §J.4): "open privacy settings in whatsapp", "what can you do in youtube". */
    fun appMap(t: String): ToolCall? {
        // Permission queue (§J.7): plain "yes"/"no" stay with the confirm flow, so these need "learn it"/"skip it".
        if (t.matches(APP_MAP_WHOLE_RE)) return call("app_questions", "action" to "list")
        if (t.matches(APP_MAP_WHOLE_RE_2)) return call("app_questions", "action" to "yes")
        if (t.matches(APP_MAP_WHOLE_RE_3)) return call("app_questions", "action" to "no")
        APP_MAP_RE.find(t)
            ?.let {
                val app = it.groupValues[1]
                return if (RuleMatcher.appNameMatches?.invoke(app) == true) call("app_limits", "app" to app) else null
            }
        // Explorer (§J.5): "learn this app", "learn mi remote".
        if (t.matches(APP_MAP_WHOLE_RE_4)) return call("learn_app")
        APP_MAP_RE_2.find(t)?.let {
            val app = it.groupValues[1]
            if (RuleMatcher.appNameMatches?.invoke(app) == true) return call("learn_app", "app" to app)
        }
        // polite() already dropped "can you": "what can you do in x" arrives as "what do in x"
        APP_MAP_RE_3.find(t)?.let {
            val app = it.groupValues[1]
            return if (RuleMatcher.appNameMatches?.invoke(app) == true) call("app_abilities", "app" to app) else null
        }
        APP_MAP_RE_4.find(t)?.let {
            val app = it.groupValues[2]
            if (RuleMatcher.appNameMatches?.invoke(app) == true) return call("app_go", "app" to app, "target" to it.groupValues[1])
        }
        return null
    }
}
