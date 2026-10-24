package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.agent.AgentSafety
import com.alrasin.jarvis.agent.UiQuery
import com.alrasin.jarvis.core.Json

/**
 * Built-in app card (ROADMAP §J.3): written knowledge about one common app, kept in
 * `assets/apps/<package>.json`. It is the starting map (screens, elements, "tap X → Y" edges)
 * plus things the map can't hold: summary for chat answers, deep links (§H.2), how to search,
 * tasks (recipes §H.5), risky labels and "can't do" reasons (§J.7). Pure — parsed on the JVM in tests.
 */
data class AppCard(
    val packages: List<String>,
    val name: String,
    val aliases: List<String>,
    /** Bump when the card changes, so phones load it again. */
    val cardVersion: Int,
    /** App versionCode range the card was written for; outside it = check again (§J.6). 0 = no limit. */
    val minVersion: Long,
    val maxVersion: Long,
    /** False until the selectors were checked on the real phone. */
    val verified: Boolean,
    /** Payment apps: Jimi only reads, never taps (§I item 14). */
    val readOnly: Boolean,
    val summary: String,
    val screens: List<Screen>,
    val deepLinks: List<DeepLink>,
    val search: Search?,
    val tasks: List<Task>,
    val risky: List<String>,
    val cannot: List<Cannot>,
) {
    /** [window]: activity class when known (lets learned screens merge with this one), else blank. */
    data class Screen(val name: String, val window: String, val launch: Boolean, val elements: List<Element>)

    /** [to]: screen name this tap leads to (becomes an edge), blank if unknown. */
    data class Element(val label: String, val role: String, val selectors: List<Map<String, String>>, val to: String, val risk: String)

    /**
     * [uri] may hold `{query}`, `{number}`, `{text}`, `{user}`, `{contact}` — filled and URI-escaped by the caller.
     * Blank [uri] = intent with only [action] (e.g. SET_ALARM, WIFI_SETTINGS).
     */
    data class DeepLink(val task: String, val uri: String, val action: String)

    /** How to search: a deep link task name, or the element to tap then type + enter. */
    data class Search(val deepLink: String, val screen: String, val selectors: List<Map<String, String>>)

    /** Recipe (§H.5). Step `do`: open|link|tab|tap|type|enter|back|search. [risky] = needs voice confirm. */
    data class Task(val name: String, val say: List<String>, val params: List<String>, val steps: List<Step>, val risky: Boolean)
    data class Step(val action: String, val target: String, val text: String, val expect: String)

    data class Cannot(val what: String, val why: String)

    val pkg: String get() = packages.first()

    fun screen(name: String): Screen? = screens.firstOrNull { it.name == name }

    /** Spoken answer to "what can you do in <app>?". */
    fun abilities(): String = buildString {
        append(summary)
        if (tasks.isNotEmpty()) append(" I can: ").append(tasks.joinToString(", ") { it.name }).append('.')
        val tabs = screens.flatMap { s -> s.elements.filter { it.role == "tab" } }.map { it.label }.distinct()
        if (tabs.isNotEmpty()) append(" Tabs: ").append(tabs.joinToString(", ")).append('.')
        if (cannot.isNotEmpty()) append(" I can't ").append(cannot.joinToString(", ") { it.what }).append('.')
    }

    companion object {
        val STEP_ACTIONS = setOf("open", "link", "tab", "tap", "type", "enter", "back", "search")
        val ROLES = setOf("tab", "drawer", "menu", "button", "field", "list", "switch")

        /** Fingerprint parts in the same "role:label" form [ScreenStructure.parts] makes from a live screen. */
        fun parts(s: Screen): List<String> = s.elements.map { "${it.role}:${ScreenStructure.clean(it.label)}" }

        /** Fingerprint for a card screen. Without a known window class it gets its own "card:" class. */
        fun fingerprint(card: AppCard, s: Screen): String =
            AppFingerprint.compute(s.window.ifBlank { "card:${card.pkg}/${s.name}" }, parts(s))

        /** Stored selector maps ({type, value}) → UiQuery selectors, in order. Unknown types are skipped. */
        fun toSelectors(list: List<Map<String, String>>): List<UiQuery.Selector> = list.mapNotNull {
            val v = it["value"].orEmpty()
            if (v.isBlank()) return@mapNotNull null
            when (it["type"]) {
                "id" -> UiQuery.Selector.Id(v)
                "desc" -> UiQuery.Selector.Desc(v)
                "text" -> UiQuery.Selector.Text(v)
                "hint" -> UiQuery.Selector.Hint(v)
                else -> null
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): AppCard {
            val m = Json.parse(text) as Map<String, Any?>
            val risky = m.strings("risky")
            return AppCard(
                packages = m.strings("packages").also { require(it.isNotEmpty()) { "card has no packages" } },
                name = m.str("name"),
                aliases = m.strings("aliases"),
                cardVersion = m.num("cardVersion", 1).toInt(),
                minVersion = m.num("minVersion", 0),
                maxVersion = m.num("maxVersion", 0),
                verified = m["verified"] as? Boolean ?: false,
                readOnly = m["readOnly"] as? Boolean ?: false,
                summary = m.str("summary"),
                screens = m.maps("screens").map { s ->
                    Screen(
                        name = s.str("name"), window = s.str("window"), launch = s["launch"] as? Boolean ?: false,
                        elements = s.maps("elements").map { element(it, risky) },
                    )
                },
                deepLinks = m.maps("deepLinks").map { DeepLink(it.str("task"), it.str("uri"), it.str("action").ifBlank { "android.intent.action.VIEW" }) },
                search = (m["search"] as? Map<String, Any?>)?.let { Search(it.str("deepLink"), it.str("screen"), selectors(it)) },
                tasks = m.maps("tasks").map { t ->
                    Task(
                        name = t.str("name"), say = t.strings("say"), params = t.strings("params"),
                        steps = t.maps("steps").map { Step(it.str("do"), it.str("target"), it.str("text"), it.str("expect")) },
                        risky = t["risky"] as? Boolean ?: false,
                    )
                },
                risky = risky,
                cannot = m.maps("cannot").map { Cannot(it.str("what"), it.str("why")) },
            )
        }

        /** Risk: card's own risky list wins, else the same word rules learned elements use. */
        private fun element(e: Map<String, Any?>, risky: List<String>): Element {
            val label = e.str("label")
            val base = AgentSafety.riskLevel(label)
            val risk = if (base == "low" && risky.any { it.equals(label, ignoreCase = true) }) "high" else base
            val sel = selectors(e).ifEmpty { listOf(mapOf("type" to "desc", "value" to label), mapOf("type" to "text", "value" to label)) }
            return Element(label, e.str("role").ifBlank { "button" }, sel, e.str("to"), risk)
        }

        @Suppress("UNCHECKED_CAST")
        private fun selectors(m: Map<String, Any?>): List<Map<String, String>> =
            (m["selectors"] as? List<Map<String, Any?>>).orEmpty().map { s -> s.mapValues { it.value?.toString().orEmpty() } }

        private fun Map<String, Any?>.str(k: String) = this[k] as? String ?: ""
        private fun Map<String, Any?>.num(k: String, def: Long) = (this[k] as? Number)?.toLong() ?: def
        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.strings(k: String) = (this[k] as? List<Any?>).orEmpty().filterIsInstance<String>()
        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.maps(k: String) = (this[k] as? List<Map<String, Any?>>).orEmpty()
    }
}
