package com.alrasin.jarvis.agent.appmap

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.AppList
import com.alrasin.jarvis.actions.Fuzzy
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.arg
import com.alrasin.jarvis.agent.UiQuery
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.openApp
import com.alrasin.jarvis.core.pollUntil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure planning over one app's map (ROADMAP §J.4): find the target screen, pick a path, check risk,
 * tell which known screen the live screen is. No Android types, so it is unit-tested.
 */
object MapPlan {

    /** One app's map, loaded from [AppMapDao]. */
    data class Graph(val screens: List<AppScreen>, val elements: Map<Long, List<AppElement>>, val edges: List<AppEdge>) {
        fun screen(id: Long) = screens.firstOrNull { it.id == id }
        fun element(id: Long) = elements.values.flatten().firstOrNull { it.id == id }
    }

    sealed class Target {
        /** Go to [screen]; then tap [finalTap] if the target is a button with no known destination. */
        data class Found(val screen: Long, val finalTap: AppElement?, val label: String) : Target()
        data class Ambiguous(val options: List<String>) : Target()
        object None : Target()
    }

    sealed class Plan {
        data class Go(val path: List<AppEdge>, val finalTap: AppElement?, val label: String) : Plan()
        data class Ambiguous(val options: List<String>) : Plan()
        object NotFound : Plan()
        object NoPath : Plan()
        data class Risky(val label: String) : Plan()
    }

    const val MIN_SCORE = 0.75
    private const val CLOSE = 0.05
    private const val TAP_COST = 1000
    private val FILLER = Regex("\\s+(?:settings?|page|screen|tab|section|options?)$")

    fun plan(g: Graph, query: String, starts: Set<Long>): Plan {
        val target = when (val t = findTarget(g, query)) {
            is Target.Found -> t
            is Target.Ambiguous -> return Plan.Ambiguous(t.options)
            Target.None -> return Plan.NotFound
        }
        val path = shortestPath(g, starts, target.screen) ?: return Plan.NoPath
        riskyStep(g, path, target.finalTap)?.let { return Plan.Risky(it) }
        return Plan.Go(path, target.finalTap, target.label)
    }

    /** Fuzzy ≥ [MIN_SCORE] over screen names and element labels; matches are grouped by where they lead. */
    fun findTarget(g: Graph, query: String): Target {
        val q = ScreenStructure.clean(query)
        if (q.isEmpty()) return Target.None
        val queries = listOf(q, FILLER.replace(q, "")).filter { it.isNotBlank() }.distinct()
        fun score(name: String) = queries.maxOf { Fuzzy.score(it, name) }

        val hits = ArrayList<Pair<Target.Found, Double>>()
        g.screens.forEach { s -> hits += Target.Found(s.id, null, s.name) to score(s.name) }
        g.elements.forEach { (screenId, list) ->
            list.forEach { e ->
                val edge = g.edges.firstOrNull { it.fromScreen == screenId && it.element == e.id }
                val found = if (edge != null) Target.Found(edge.toScreen, null, e.label) else Target.Found(screenId, e, e.label)
                hits += found to score(e.label)
            }
        }
        val best = hits.filter { it.second >= MIN_SCORE }
            .groupBy { it.first.screen to it.first.finalTap?.id }
            .map { (_, list) -> list.maxBy { it.second } }
            .sortedByDescending { it.second }
        val top = best.firstOrNull() ?: return Target.None
        val close = best.filter { top.second - it.second < CLOSE }
        if (close.size > 1) return Target.Ambiguous(close.map { it.first.label }.distinct().take(3))
        return top.first
    }

    /** Dijkstra: fewest taps first, then fewest past failures. Empty list = already there. */
    fun shortestPath(g: Graph, starts: Set<Long>, goal: Long): List<AppEdge>? {
        val dist = HashMap<Long, Int>()
        val via = HashMap<Long, AppEdge>()
        val queue = PriorityQueue<Pair<Long, Int>>(compareBy { it.second })
        starts.forEach { dist[it] = 0; queue += it to 0 }
        while (queue.isNotEmpty()) {
            val (at, d) = queue.poll()!!
            if (d > (dist[at] ?: Int.MAX_VALUE)) continue
            if (at == goal) break
            g.edges.filter { it.fromScreen == at }.forEach { e ->
                // Stale edges (failed twice) stay usable as a last way only (§J.6).
                val nd = d + TAP_COST + e.failedCount + if (SelfHeal.isStale(e)) SelfHeal.STALE_COST else 0
                if (nd < (dist[e.toScreen] ?: Int.MAX_VALUE)) {
                    dist[e.toScreen] = nd
                    via[e.toScreen] = e
                    queue += e.toScreen to nd
                }
            }
        }
        if (goal !in dist) return null
        val path = ArrayList<AppEdge>()
        var at = goal
        while (at !in starts) {
            val e = via[at] ?: return null
            path += e
            at = e.fromScreen
        }
        return path.reversed()
    }

    /** Label of the first high/secret risk tap on the way, or null (§J.4: never done automatically). */
    fun riskyStep(g: Graph, path: List<AppEdge>, finalTap: AppElement?): String? =
        (path.mapNotNull { g.element(it.element) } + listOfNotNull(finalTap))
            .firstOrNull { it.risk == "high" || it.risk == "secret" }?.label

    /**
     * How well a live screen matches a known one, 0 = not it. Same window class → [AppFingerprint.sameScreen].
     * Card screens without a window class ("card:") → share of their labels seen on the live screen
     * (its parts or any of [liveTexts]).
     */
    fun matchScore(known: String, live: String, liveTexts: Set<String> = emptySet()): Double {
        val kParts = parts(known)
        val lParts = parts(live)
        if (kParts.isEmpty()) return 0.0
        val cardOnly = known.substringBefore("::").startsWith("card:")
        if (!cardOnly && AppFingerprint.sameScreen(known, live)) return 1.0
        val kLabels = kParts.map { alias(it.substringAfter(':')) }.toSet()
        val lLabels = (lParts.map { it.substringAfter(':') } + liveTexts).map(::alias).toSet()
        val share = kLabels.count { it in lLabels }.toDouble() / kLabels.size
        return if (share >= CARD_MATCH) share else 0.0
    }

    /** Apps name the same thing differently across versions ("Navigate up" vs "Back"). */
    private fun alias(label: String) = LABEL_ALIASES[label] ?: label

    private val LABEL_ALIASES = mapOf("back" to "navigate up", "profile picture" to "profile photo")

    /** Known screens the live screen looks like, best first. */
    fun whereAmI(g: Graph, liveFingerprint: String, liveTexts: Set<String> = emptySet()): List<Long> =
        g.screens.map { it.id to matchScore(it.fingerprint, liveFingerprint, liveTexts) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }

    /** Answer for "what can you do in X": the card if there is one, else the learned screen names. */
    fun abilities(appName: String, card: AppCard?, g: Graph): String {
        card?.let { return it.abilities() }
        if (g.screens.isEmpty()) return "I don't know $appName yet. Use it once while I watch, and I'll learn it."
        val names = g.screens.map { it.name }.filter { it.isNotBlank() }.distinct().take(8)
        return "In $appName I know these screens: ${names.joinToString(", ")}. Say \"open <screen> in $appName\"."
    }

    @Suppress("UNCHECKED_CAST")
    fun selectors(e: AppElement): List<UiQuery.Selector> = runCatching {
        val list = Json.parse(e.selectorsJson) as List<Map<*, *>>
        AppCard.toSelectors(list.map { m -> m.entries.associate { it.key.toString() to it.value?.toString().orEmpty() } })
    }.getOrDefault(emptyList()).ifEmpty { listOf(UiQuery.Selector.Desc(e.label), UiQuery.Selector.Text(e.label)) }

    private fun parts(fp: String) = fp.substringAfter("::", "").split("|").filter { it.isNotBlank() }

    private const val CARD_MATCH = 0.6
}

/**
 * Walks the app map on the live phone (ROADMAP §J.4): open the app, find where we are, tap along the
 * cheapest path, check each screen, re-plan once on a wrong screen. Payment/read-only apps are refused.
 */
@Singleton
class Planner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppMapDao,
    private val appCards: AppCards,
) {
    data class App(val pkg: String, val name: String, val card: AppCard?)

    fun resolve(name: String, apps: AppList): App? {
        appCards.byName(name)?.let { return App(it.pkg, it.name, it) }
        val app = apps.find(name) ?: return null
        return App(app.pkg, app.label, appCards.card(app.pkg))
    }

    /** The app for a known package (e.g. the one on screen now). */
    fun forPackage(pkg: String, apps: AppList): App {
        val card = appCards.card(pkg)
        return App(pkg, card?.name ?: apps.all().firstOrNull { it.pkg == pkg }?.label ?: pkg, card)
    }

    suspend fun abilities(name: String, apps: AppList): ToolResult {
        val app = resolve(name, apps) ?: return ToolResult.fail("I can't find an app called $name.")
        return ToolResult.ok(MapPlan.abilities(app.name, app.card, graph(app.pkg)))
    }

    /** "what can't you do in X" (§J.7): card reasons, payment/skip, risky and denied buttons. */
    suspend fun limits(name: String, apps: AppList): ToolResult {
        val app = resolve(name, apps) ?: return ToolResult.fail("I can't find an app called $name.")
        val readOnly = PhoneProfile.isPaymentPackage(app.pkg) || app.card?.readOnly == true
        val risky = graph(app.pkg).elements.values.flatten().filter { it.risk != "low" }.map { it.label }.distinct().size
        val denied = dao.asksFor(app.pkg).filter { it.status == AppAskQueue.DENIED }.map { it.label }
        val skipped = ExplorePolicy.skipPackage(app.pkg, context.packageName)
        return ToolResult.ok(AppAskQueue.limits(app.name, app.card?.cannot.orEmpty(), readOnly, skipped, denied, risky))
    }

    @Volatile private var lastAsk: Long = 0

    /** Permission queue (§J.7): [action] list = ask the oldest question; yes/no = answer it. */
    suspend fun questions(action: String, appName: String, apps: AppList): ToolResult {
        val pkg = appName.takeIf { it.isNotBlank() }?.let { resolve(it, apps)?.pkg }
        val pending = dao.pendingAsksOnce().filter { pkg == null || it.pkg == pkg }
        if (action == "yes" || action == "no") {
            val ask = dao.ask(lastAsk)?.takeIf { it.status == AppAskQueue.PENDING } ?: pending.firstOrNull()
                ?: return ToolResult.fail("I have no question waiting, ${Persona.ADDRESS}.")
            val yes = action == "yes"
            dao.setAskStatus(ask.id, if (yes) AppAskQueue.APPROVED else AppAskQueue.DENIED)
            lastAsk = 0
            val name = forPackage(ask.pkg, apps).name
            return ToolResult.ok(if (yes) "OK, I'll tap \"${ask.label}\" next time I learn $name." else "OK, I won't tap \"${ask.label}\" in $name.")
        }
        val ask = pending.firstOrNull() ?: return ToolResult.ok("No questions, ${Persona.ADDRESS}.")
        lastAsk = ask.id
        val more = if (pending.size > 1) " ${pending.size - 1} more after this." else ""
        return ToolResult.ok(AppAskQueue.question(forPackage(ask.pkg, apps).name, ask) + more)
    }

    suspend fun go(name: String, target: String, apps: AppList): ToolResult {
        val app = resolve(name, apps) ?: return ToolResult.fail("I can't find an app called $name.")
        if (PhoneProfile.isPaymentPackage(app.pkg) || app.card?.readOnly == true) {
            return ToolResult.fail("${app.name} is a payment app. I only read it, I don't tap in it.")
        }
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.fail("Turn on Jarvis accessibility first, so I can use the screen.")
        var g = graph(app.pkg)
        if (g.screens.isEmpty()) return ToolResult.fail("I don't know ${app.name} yet. Use it once while I watch.")
        // Fail fast before opening anything when the target is unknown or unclear.
        when (val t = MapPlan.findTarget(g, target)) {
            MapPlan.Target.None -> return ToolResult.fail("I don't know \"$target\" in ${app.name}.")
            is MapPlan.Target.Ambiguous -> return ToolResult.fail("Which one: ${t.options.joinToString(" or ")}?")
            is MapPlan.Target.Found -> Unit
        }
        if (!openIfNeeded(service, app.pkg, apps)) return ToolResult.fail("I couldn't open ${app.name}.")

        var replanned = false
        while (true) {
            val starts = startScreens(service, g, app)
            // Learned screens are often not linked to card screens; try from the launch screen too.
            val first = MapPlan.plan(g, target, starts)
            val planned = if (first == MapPlan.Plan.NoPath) MapPlan.plan(g, target, starts + launchScreens(g, app)) else first
            val plan = when (val p = planned) {
                is MapPlan.Plan.Go -> p
                is MapPlan.Plan.Ambiguous -> return ToolResult.fail("Which one: ${p.options.joinToString(" or ")}?")
                MapPlan.Plan.NotFound -> return ToolResult.fail("I don't know \"$target\" in ${app.name}.")
                MapPlan.Plan.NoPath -> return ToolResult.fail("I don't know the way to $target in ${app.name} from here.")
                is MapPlan.Plan.Risky -> return ToolResult.fail(
                    "The way to $target needs \"${p.label}\", which I don't tap by myself. Please do that part.",
                )
            }
            val failed = walk(service, g, plan.path)
            if (failed == null) {
                plan.finalTap?.let { e ->
                    val node = findOrHeal(e, app.pkg)
                    if (node == null || !UiQuery.tap(node)) return ToolResult.fail("I got there but couldn't tap ${e.label}.")
                }
                return ToolResult.ok("Opened ${plan.label} in ${app.name}.")
            }
            dao.updateEdge(failed.copy(failedCount = failed.failedCount + 1))
            JarvisLog.i("map planner: edge ${failed.id} failed in ${app.pkg}")
            if (replanned) return ToolResult.fail("I lost my way in ${app.name}. The app may have changed.")
            replanned = true
            g = graph(app.pkg)
        }
    }

    /** Taps along [path]. Returns the edge that failed, or null when all screens were right. */
    private suspend fun walk(service: JarvisAccessibilityService, g: MapPlan.Graph, path: List<AppEdge>): AppEdge? {
        for (edge in path) {
            val element = g.element(edge.element) ?: return edge
            val node = findOrHeal(element, g.screen(edge.fromScreen)?.pkg.orEmpty()) ?: return edge
            if (!UiQuery.tap(node)) return edge
            UiQuery.settle(service, tries = 4, pauseMs = 300)
            val expected = g.screen(edge.toScreen) ?: return edge
            // Check, then look once more (slow screens), before calling it wrong.
            if (!onScreen(service, expected) && !(pause() && onScreen(service, expected))) return edge
            dao.updateEdge(edge.copy(timesSeen = edge.timesSeen + 1, lastOk = System.currentTimeMillis(), failedCount = 0))
        }
        return null
    }

    /**
     * Saved selectors first; if they fail, self-heal (§H.7): score what is on screen and take only a clear,
     * not-opposite, not-riskier winner, then save its new selectors. Unsure → null (caller fails, agent takes over).
     */
    private suspend fun findOrHeal(e: AppElement, pkg: String): AccessibilityNodeInfo? {
        val selectors = MapPlan.selectors(e)
        UiQuery.waitFor(selectors, STEP_WAIT_MS)?.let { return it }
        val winner = SelfHeal.pick(SelfHeal.saved(e, selectors), UiQuery.candidates())
        if (winner?.node == null) {
            JarvisLog.i("self-heal: no clear match for \"${e.label}\" in $pkg")
            return null
        }
        val healed = SelfHeal.healedSelectors(winner)
        if (healed.isNotEmpty()) dao.updateElement(e.copy(selectorsJson = Json.write(healed)))
        JarvisLog.i("self-heal: \"${e.label}\" → \"${winner.text.ifBlank { winner.desc }}\" ${winner.resourceId} in $pkg")
        return winner.node
    }

    private fun onScreen(service: JarvisAccessibilityService, s: AppScreen): Boolean {
        val live = service.liveScreen() ?: return false
        return MapPlan.matchScore(s.fingerprint, live.fingerprint, live.texts) > 0
    }

    private suspend fun pause(): Boolean {
        kotlinx.coroutines.delay(RECHECK_MS)
        return true
    }

    /** Every known screen the live one looks like; unknown screen → the card's launch screen (the app usually opens there). */
    private fun startScreens(service: JarvisAccessibilityService, g: MapPlan.Graph, app: App): Set<Long> {
        service.liveScreen()?.takeIf { it.pkg == app.pkg }?.let { live ->
            MapPlan.whereAmI(g, live.fingerprint, live.texts).takeIf { it.isNotEmpty() }?.let { return it.toSet() }
        }
        return launchScreens(g, app)
    }

    private fun launchScreens(g: MapPlan.Graph, app: App): Set<Long> {
        val launch = app.card?.screens?.firstOrNull { it.launch }?.name
        return g.screens.filter { it.name == launch }.map { it.id }.toSet()
    }

    internal suspend fun openIfNeeded(service: JarvisAccessibilityService, pkg: String, apps: AppList): Boolean {
        if (service.appPackage() == pkg) return true
        if (!context.openApp(apps, pkg)) return false
        val open = pollUntil(OPEN_TRIES, OPEN_PAUSE_MS) { service.appPackage() == pkg }
        if (open) UiQuery.settle(service, tries = 4, pauseMs = 300)
        return open
    }

    private suspend fun graph(pkg: String): MapPlan.Graph {
        val screens = dao.screensFor(pkg)
        val elements = screens.associate { it.id to dao.elementsFor(it.id) }
        val edges = screens.flatMap { dao.edgesFrom(it.id) }
        return MapPlan.Graph(screens, elements, edges)
    }

    private companion object {
        const val STEP_WAIT_MS = 3000L
        const val RECHECK_MS = 800L
        const val OPEN_TRIES = 16
        const val OPEN_PAUSE_MS = 250L
    }
}

/** "open privacy settings in WhatsApp" and "what can you do in WhatsApp" (ROADMAP §J.4). */
fun mapTools(planner: Planner, apps: AppList): List<Tool> = listOf(
    SimpleTool(
        "app_go",
        "Go to a screen or setting inside an app using its learned map, e.g. privacy settings in WhatsApp.",
        listOf(arg("app", "app name"), arg("target", "screen, tab or setting to open")),
    ) { call, _ ->
        val app = call.arg("app").orEmpty()
        val target = call.arg("target").orEmpty()
        if (app.isBlank() || target.isBlank()) ToolResult.fail("Which app, and what should I open in it?")
        else planner.go(app, target, apps)
    },
    SimpleTool(
        "app_abilities",
        "Say what Jarvis can do inside an app.",
        listOf(arg("app", "app name")),
    ) { call, _ ->
        val app = call.arg("app").orEmpty()
        if (app.isBlank()) ToolResult.fail("Which app?") else planner.abilities(app, apps)
    },    SimpleTool(
        "app_limits",
        "Say what Jarvis can't do inside an app and why.",
        listOf(arg("app", "app name")),
    ) { call, _ ->
        val app = call.arg("app").orEmpty()
        if (app.isBlank()) ToolResult.fail("Which app?") else planner.limits(app, apps)
    },
    SimpleTool(
        "app_questions",
        "Ask or answer Jarvis's questions about buttons it may tap while learning apps.",
        listOf(arg("action", "list, yes or no", choices = listOf("list", "yes", "no")), arg("app", "app name, optional", required = false)),
    ) { call, _ ->
        planner.questions(call.arg("action")?.lowercase() ?: "list", call.arg("app").orEmpty(), apps)
    },
)
