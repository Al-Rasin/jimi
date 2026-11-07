package com.alrasin.jarvis.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.AppList
import com.alrasin.jarvis.actions.Fuzzy
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.a11y.findAll
import com.alrasin.jarvis.actions.a11y.findFirst
import com.alrasin.jarvis.actions.a11y.label
import com.alrasin.jarvis.actions.a11y.scroll
import com.alrasin.jarvis.actions.arg
import com.alrasin.jarvis.agent.appmap.AppCard
import com.alrasin.jarvis.agent.appmap.Planner
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.launch
import com.alrasin.jarvis.core.pollUntil
import kotlinx.coroutines.delay

/**
 * General skills for apps without a card (ROADMAP §H.4): search, open a result, tap / toggle by name,
 * switch tabs, read the list. No AI. The pick* functions are pure so they are unit tested.
 */
object Skills {
    /** One visible node, as plain data. Clickable rows with no own label get their children's text. */
    data class Item(
        val text: String = "",
        val desc: String = "",
        val id: String = "",
        val hint: String = "",
        val clickable: Boolean = false,
        val checkable: Boolean = false,
        val checked: Boolean = false,
        val editable: Boolean = false,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val node: AccessibilityNodeInfo? = null,
    ) {
        val label: String get() = text.ifBlank { desc }
    }

    sealed class Pick {
        data class Found(val item: Item) : Pick()
        data class Ambiguous(val options: List<String>) : Pick()
        data object NotFound : Pick()
    }

    const val MIN = 0.7
    private const val CLOSE = 0.05
    private const val BOTTOM_BAR = 0.85
    private const val TOP_BAR = 0.25

    private val SEARCH_WORDS = listOf("search", "খুঁজুন", "অনুসন্ধান", "সার্চ")
    private val RISKY = Regex(
        "\\b(?:pay|buy|send|delete|purchase|checkout|place.?order|confirm|book|request|remove|transfer|submit|allow|" +
            "accept|install|uninstall|subscribe|follow|sign.?out|log.?out|logout|call|share|post|order)\\b" +
            "|পাঠান|মুছুন|পেমেন্ট|কিনুন|নিশ্চিত",
        RegexOption.IGNORE_CASE,
    )

    /** True when tapping [label] may pay, send, delete or similar (never tapped without the user's say-so). */
    fun isRisky(label: String): Boolean = RISKY.containsMatchIn(label)

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** 0..1: like [Fuzzy.score], but a long row that holds every spoken word still scores well. */
    fun rowScore(query: String, label: String): Double {
        val q = words(query)
        val l = words(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        val hit = q.count { w -> l.any { it == w || (w.length >= 3 && it.startsWith(w)) } }
        val cover = if (hit == q.size) 0.75 + 0.25 * q.size / l.size else 0.6 * hit / q.size
        return maxOf(Fuzzy.score(query, label), cover)
    }

    /** Best item for [query] among [items]; two close different labels → ask which one. */
    fun pickBest(items: List<Item>, query: String): Pick {
        val scored = items.filter { it.label.isNotBlank() }
            .map { it to rowScore(query, it.label) }
            .filter { it.second >= MIN }
            .sortedWith(compareByDescending<Pair<Item, Double>> { it.second }.thenBy { it.first.top })
        val best = scored.firstOrNull() ?: return Pick.NotFound
        val rival = scored.firstOrNull { !it.first.label.equals(best.first.label, true) && best.second - it.second <= CLOSE }
        return if (rival != null) Pick.Ambiguous(listOf(best.first.label, rival.first.label)) else Pick.Found(best.first)
    }

    /** First result row under the search field ([belowY]) that matches [query]. */
    fun pickResult(items: List<Item>, query: String, belowY: Int): Pick =
        pickBest(items.filter { it.clickable && !it.editable && it.top >= belowY }, query)

    /** Top-most list row under the search field, for "open the first result". */
    fun firstResult(items: List<Item>, belowY: Int, screenH: Int): Item? =
        rows(items.filter { it.top >= belowY }, screenH).firstOrNull()

    /** Item named [name]; clickable ones win over plain text. */
    fun pickNamed(items: List<Item>, name: String): Pick {
        val clickable = pickBest(items.filter { it.clickable && !it.editable }, name)
        return if (clickable is Pick.NotFound) pickBest(items.filter { !it.editable }, name) else clickable
    }

    /** Tab named [name] in the bottom or top bar of a [screenH] tall screen. */
    fun pickTab(items: List<Item>, name: String, screenH: Int): Pick {
        val bars = items.filter { !it.editable && (it.top >= screenH * BOTTOM_BAR || it.bottom <= screenH * TOP_BAR) }
        val bottom = pickBest(bars.filter { it.top >= screenH * BOTTOM_BAR }, name)
        return if (bottom is Pick.NotFound) pickBest(bars, name) else bottom
    }

    /** The search box (editable first), else the search button / magnifier, top-most first. */
    fun pickSearch(items: List<Item>): Item? {
        fun hasSearch(s: String) = SEARCH_WORDS.any { s.contains(it, ignoreCase = true) }
        val byTop = items.sortedBy { it.top }
        return byTop.firstOrNull { it.editable && (hasSearch(it.id) || hasSearch(it.hint) || hasSearch(it.desc) || hasSearch(it.text)) }
            ?: byTop.firstOrNull { !it.editable && (it.clickable || it.desc.isNotBlank()) && (hasSearch(it.id) || hasSearch(it.desc) || hasSearch(it.text)) }
    }

    /** The switch / checkbox for [label]: the item itself if checkable, else the nearest one on the same row. */
    fun pickToggle(items: List<Item>, label: String): Pick {
        val switches = items.filter { it.checkable }
        val own = pickBest(switches, label)
        if (own !is Pick.NotFound) return own
        return when (val l = pickBest(items.filter { !it.checkable && !it.editable }, label)) {
            is Pick.Found -> switches
                .filter { it.top < l.item.bottom && it.bottom > l.item.top }
                .minByOrNull { minOf(kotlin.math.abs(it.left - l.item.right), kotlin.math.abs(it.right - l.item.left)) }
                ?.let { Pick.Found(it.copy(text = l.item.label)) } ?: Pick.NotFound
            else -> l
        }
    }

    /** Visible list rows: clickable, labelled, outside the bars, not inside another row; top to bottom. */
    fun rows(items: List<Item>, screenH: Int): List<Item> {
        val cand = items.filter {
            it.clickable && !it.editable && it.label.isNotBlank() &&
                it.top >= screenH * 0.1 && it.bottom <= screenH * BOTTOM_BAR
        }
        return cand.filter { r ->
            cand.none { o -> o !== r && o.left <= r.left && o.top <= r.top && o.right >= r.right && o.bottom >= r.bottom && o != r }
        }.distinctBy { it.label.lowercase() }.sortedBy { it.top }
    }

    /** "1, Alpha. 2, Beta." — at most [max] rows. */
    fun formatRows(rows: List<Item>, max: Int = 8): String =
        rows.take(max).mapIndexed { i, r -> "${i + 1}, ${r.label.replace("\n", ", ").take(80)}" }.joinToString(". ") + "."

    /** Fills a deep link template with the url-encoded query. */
    fun fillLink(uri: String, query: String): String = uri.replace("{query}", Uri.encode(query))

    // ---------------------------------------------------------------- Live screen

    data class Screen(val items: List<Item>, val height: Int)

    fun read(service: JarvisAccessibilityService): Screen? {
        val root = service.rootInActiveWindow ?: return null
        val rootRect = Rect().also(root::getBoundsInScreen)
        val items = root.findAll(maxDepth = 40) { it.isVisibleToUser }.map { n ->
            val r = Rect().also(n::getBoundsInScreen)
            val own = n.label()
            val text = if (own.isBlank() && n.isClickable) childText(n) else n.text?.toString()?.trim().orEmpty()
            Item(
                text = text,
                desc = n.contentDescription?.toString()?.trim().orEmpty(),
                id = n.viewIdResourceName.orEmpty(),
                hint = n.hintText?.toString().orEmpty(),
                clickable = n.isClickable,
                checkable = n.isCheckable,
                checked = n.isChecked,
                editable = n.isEditable,
                left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                node = n,
            )
        }
        return Screen(items, rootRect.height().coerceAtLeast(1))
    }

    private fun childText(n: AccessibilityNodeInfo): String =
        n.findAll(maxDepth = 6) { it !== n && it.label().isNotBlank() }.map { it.label() }.distinct().take(3).joinToString("\n")

    /** Tap refused: payment app on screen, or a risky label the user did not say. Null = fine. */
    fun refuse(service: JarvisAccessibilityService, found: Item, said: String): String? = when {
        service.appPackage()?.let { PhoneProfile.isPaymentPackage(it) } == true ->
            "This is a payment app. I only read it, I don't tap in it."
        isRisky(found.label) && !isRisky(said) -> "That is \"${found.label.lineSequence().first()}\", I don't tap that by myself."
        else -> null
    }

    fun say(p: Pick, what: String): String = when (p) {
        is Pick.Ambiguous -> "Which one: ${p.options.joinToString(" or ") { it.lineSequence().first() }}?"
        else -> "I don't see \"$what\" on the screen."
    }
}

private const val NO_SERVICE = "Turn on Jarvis accessibility first, so I can use the screen."

/** search_in_app, open_result, tap_named, go_tab, read_list, toggle_named (ROADMAP §H.4). */
fun skillTools(context: Context, planner: Planner, apps: AppList): List<Tool> = listOf(
    SimpleTool(
        "search_in_app",
        "Search for something inside an app (its own search box). Without an app, searches in the app on screen.",
        listOf(arg("query", "what to search for"), arg("app", "app name", required = false)),
    ) { c, _ ->
        val query = c.arg("query") ?: return@SimpleTool ToolResult.fail("What should I search for?")
        searchInApp(context, planner, apps, query, c.arg("app"), useLink = true)
    },
    SimpleTool(
        "find_in_app",
        "Find a person or item inside an app and open it (search, then tap the match), e.g. a chat in Messenger.",
        listOf(arg("query", "who or what to find"), arg("app", "app name", required = false)),
    ) { c, _ ->
        val query = c.arg("query") ?: return@SimpleTool ToolResult.fail("Who should I find?")
        // The screen search, not a deep link: the result list must be on screen to open the match (§M.9).
        val searched = searchInApp(context, planner, apps, query, c.arg("app"), useLink = false)
        if (!searched.ok) return@SimpleTool searched
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        var pick: Skills.Pick = Skills.Pick.NotFound
        pollUntil(10, 300) {
            val screen = Skills.read(service) ?: return@pollUntil false
            val below = screen.items.filter { it.editable }.minByOrNull { it.top }?.bottom ?: 0
            pick = Skills.pickResult(screen.items, query, below)
            pick !is Skills.Pick.NotFound
        }
        tapPick(service, pick, query, "Opened")
    },
    SimpleTool(
        "open_result",
        "Open the search result or list row that matches the words, on the screen now. No words = the first result.",
        listOf(arg("query", "words of the result to open", required = false)),
    ) { c, _ ->
        val query = c.arg("query")
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        val screen = Skills.read(service) ?: return@SimpleTool ToolResult.fail("I can't read the screen.")
        val below = screen.items.filter { it.editable }.minByOrNull { it.top }?.bottom ?: 0
        val pick = if (query != null) Skills.pickResult(screen.items, query, below)
        else Skills.firstResult(screen.items, below, screen.height)?.let { Skills.Pick.Found(it) } ?: Skills.Pick.NotFound
        tapPick(service, pick, query ?: "a result", "Opened")
    },
    SimpleTool(
        "tap_named",
        "Tap the button or item with this name on the screen; scrolls to find it.",
        listOf(arg("name", "text or label of the thing to tap")),
    ) { c, _ ->
        val name = c.arg("name") ?: return@SimpleTool ToolResult.fail("Tap what?")
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        var pick: Skills.Pick = Skills.Pick.NotFound
        for (i in 0..MAX_SCROLLS) {
            val screen = Skills.read(service) ?: break
            pick = Skills.pickNamed(screen.items, name)
            // Flutter / game / WebView screens have no text in the tree: look with OCR before scrolling (§H.8).
            if (pick is Skills.Pick.NotFound && i == 0) pick = ScreenOcr.find(service, name)
            if (pick !is Skills.Pick.NotFound || i == MAX_SCROLLS) break
            if (!scrollDown(service)) break
        }
        tapPick(service, pick, name, "Tapped")
    },
    SimpleTool(
        "go_tab",
        "Switch to a tab in the app on screen (bottom or top bar), e.g. Calls, Library, Home.",
        listOf(arg("name", "tab name")),
    ) { c, _ ->
        val name = c.arg("name") ?: return@SimpleTool ToolResult.fail("Which tab?")
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        val screen = Skills.read(service) ?: return@SimpleTool ToolResult.fail("I can't read the screen.")
        tapPick(service, Skills.pickTab(screen.items, name, screen.height), name, "Opened")
    },
    SimpleTool("read_list", "Read the rows of the list on the screen now.") { _, _ ->
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        val screen = Skills.read(service) ?: return@SimpleTool ToolResult.fail("I can't read the screen.")
        val rows = Skills.rows(screen.items, screen.height)
        if (rows.isEmpty()) ToolResult.fail("I don't see a list here.") else ToolResult.ok(Skills.formatRows(rows))
    },
    SimpleTool(
        "toggle_named",
        "Turn on or off the switch or checkbox next to a label on the screen.",
        listOf(arg("name", "label next to the switch"), arg("state", "on or off", required = false, choices = listOf("on", "off"))),
    ) { c, _ ->
        val name = c.arg("name") ?: return@SimpleTool ToolResult.fail("Which switch?")
        val want = when (c.arg("state")?.lowercase()) { "on" -> true; "off" -> false; else -> null }
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        val screen = Skills.read(service) ?: return@SimpleTool ToolResult.fail("I can't read the screen.")
        val item = when (val p = Skills.pickToggle(screen.items, name)) {
            is Skills.Pick.Found -> p.item
            else -> return@SimpleTool ToolResult.fail(Skills.say(p, "$name switch"))
        }
        val label = item.label.lineSequence().first()
        if (want != null && item.checked == want) return@SimpleTool ToolResult.ok("$label is already ${if (want) "on" else "off"}.")
        Skills.refuse(service, item, name)?.let { return@SimpleTool ToolResult.fail(it) }
        val node = item.node ?: return@SimpleTool ToolResult.fail("I couldn't tap $label.")
        if (!UiQuery.tap(node)) return@SimpleTool ToolResult.fail("I couldn't tap $label.")
        ToolResult.ok("Turned $label ${if (item.checked) "off" else "on"}.")
    },
)

private const val MAX_SCROLLS = 8

private suspend fun tapPick(service: JarvisAccessibilityService, pick: Skills.Pick, said: String, verb: String): ToolResult {
    val item = (pick as? Skills.Pick.Found)?.item ?: return ToolResult.fail(Skills.say(pick, said))
    Skills.refuse(service, item, said)?.let { return ToolResult.fail(it) }
    val label = item.label.lineSequence().first()
    val node = item.node
    val ok = if (node != null) UiQuery.tap(node) else ScreenOcr.centre(item).let { (x, y) -> UiQuery.tapAt(x, y) }
    if (!ok) return ToolResult.fail("I couldn't tap $label.")
    UiQuery.settle(service, tries = 4, pauseMs = 300)
    return ToolResult.ok("$verb $label.")
}

private suspend fun scrollDown(service: JarvisAccessibilityService): Boolean {
    val list = service.rootInActiveWindow?.findFirst { it.isScrollable && it.isVisibleToUser } ?: return false
    if (!list.scroll(true)) return false
    delay(300)
    return true
}

/** Shared by search_in_app and find_in_app: resolve the app, refuse payment apps, open it and search. */
private suspend fun searchInApp(
    context: Context, planner: Planner, apps: AppList, query: String, appName: String?, useLink: Boolean,
): ToolResult {
    val service = JarvisAccessibilityService.instance ?: return ToolResult.fail(NO_SERVICE)
    val app = appName?.let { planner.resolve(it, apps) ?: return ToolResult.fail("I can't find an app called $it.") }
        ?: service.appPackage()?.let { planner.forPackage(it, apps) }
        ?: return ToolResult.fail("Which app should I search in?")
    if (PhoneProfile.isPaymentPackage(app.pkg) || app.card?.readOnly == true) {
        return ToolResult.fail("${app.name} is a payment app. I only read it, I don't tap in it.")
    }
    if (useLink) searchByLink(context, app, query)?.let { return it }
    if (!planner.openIfNeeded(service, app.pkg, apps)) return ToolResult.fail("I couldn't open ${app.name}.")
    return searchOnScreen(context, service, app, query)
}

/** Card says "search via deep link": open it with the query filled in. Null = no link, use the screen. */
internal fun searchByLink(context: Context, app: Planner.App, query: String): ToolResult? {
    val card = app.card ?: return null
    val task = card.search?.deepLink?.takeIf { it.isNotBlank() } ?: return null
    val link = card.deepLinks.firstOrNull { it.task == task } ?: return null
    val uri = Uri.parse(Skills.fillLink(link.uri, query))
    val opened = context.launch(Intent(link.action, uri).setPackage(app.pkg)) || context.launch(Intent(link.action, uri))
    JarvisLog.i("search_in_app ${app.pkg}: deep link ${if (opened) "ok" else "failed"}")
    return if (opened) ToolResult.ok("Searching $query in ${app.name}.") else null
}

/** Tap the search box (card selectors first, then any search box / magnifier), type, press enter. */
internal suspend fun searchOnScreen(context: Context, service: JarvisAccessibilityService, app: Planner.App, query: String): ToolResult {
    val cardSelectors = app.card?.search?.selectors?.let(AppCard::toSelectors).orEmpty()
    val first = cardSelectors.takeIf { it.isNotEmpty() }?.let { UiQuery.waitFor(it, 1500) }
        ?: Skills.read(service)?.let { Skills.pickSearch(it.items)?.node }
        ?: return ToolResult.fail("I can't find a search box in ${app.name}.")
    val field = if (first.isEditable) first else {
        if (!UiQuery.tap(first)) return ToolResult.fail("I couldn't tap search in ${app.name}.")
        var edit: AccessibilityNodeInfo? = null
        pollUntil(12, 250) {
            edit = service.rootInActiveWindow?.findFirst { it.isEditable && it.isVisibleToUser }
            edit != null
        }
        edit ?: return ToolResult.fail("I tapped search in ${app.name} but no text box opened.")
    }
    if (!UiQuery.type(context, field, query)) return ToolResult.fail("I couldn't type in the search box.")
    UiQuery.enter(field)
    UiQuery.settle(service, tries = 4, pauseMs = 300)
    return ToolResult.ok("Searched $query in ${app.name}.")
}
