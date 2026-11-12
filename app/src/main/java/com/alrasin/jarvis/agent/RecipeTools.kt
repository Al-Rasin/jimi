package com.alrasin.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.AppList
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.a11y.findFirst
import com.alrasin.jarvis.actions.a11y.scroll
import com.alrasin.jarvis.actions.arg
import com.alrasin.jarvis.agent.appmap.AppCard
import com.alrasin.jarvis.agent.appmap.MapPlan
import com.alrasin.jarvis.agent.appmap.Planner
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.launch
import kotlinx.coroutines.delay

// Regexes built once, not on every call (ROADMAP §L.4).
private val LINK_RE = Regex("\\{(\\w+)\\}")

private const val NO_SERVICE = "Turn on Jarvis accessibility first, so I can use the screen."

/** run_recipe: replays a recipe found by [RecipeStore.find] (the assistant fills name, pkg, params, risky). */
fun recipeTools(context: Context, planner: Planner, apps: AppList, store: RecipeStore): List<Tool> = listOf(
    SimpleTool(
        "run_recipe",
        "Run a saved recipe (fixed steps) for a task in an app.",
        listOf(
            arg("name", "recipe name"),
            arg("app", "app name", required = false),
            arg("pkg", "app package", required = false),
            arg("params", "JSON object of recipe params", required = false),
            arg("risky", "true when the recipe sends, pays, calls or deletes", required = false),
        ),
    ) { c, _ ->
        val name = c.require("name", "Which recipe?")
        val pkg = c.arg("pkg")
        val appName = c.arg("app")
        val recipe = store.all().firstOrNull { r ->
            r.name.equals(name, ignoreCase = true) &&
                (pkg?.let { it == r.pkg } ?: appName?.let { r.appName.equals(it, ignoreCase = true) } ?: true)
        } ?: return@SimpleTool ToolResult.fail("I don't have a recipe called $name.")
        val app = planner.forPackage(recipe.pkg, apps)
        if (PhoneProfile.isPaymentPackage(app.pkg) || app.card?.readOnly == true) {
            return@SimpleTool ToolResult.fail("${app.name} is a payment app. I only read it, I don't tap in it.")
        }
        if (recipe.risky && c.arg("risky") != "true") {
            return@SimpleTool ToolResult.fail("That one needs your yes first. Say it again, please.")
        }
        val service = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.fail(NO_SERVICE)
        val params = parseParams(c.arg("params"))
        val steps = recipe.steps.map { RecipeBook.fill(it, params) }
        if (!recipe.risky && steps.any { it.action == "tap" && Skills.isRisky(it.target) }) {
            return@SimpleTool ToolResult.fail("That recipe taps something risky. I won't do it by myself.")
        }
        val live = LiveRecipeScreen(context, service, planner, apps, app, recipe.risky, params)
        val error = RecipeRunner(live).run(steps)
        JarvisLog.i("recipe ${recipe.appName}/${recipe.name}: ${error ?: "ok"}")
        if (error == null) ToolResult.ok("Done: ${recipe.name} in ${app.name}.")
        else ToolResult.fail("$error in ${app.name} (${recipe.name}).")
    },
)

@Suppress("UNCHECKED_CAST")
internal fun parseParams(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    (runCatching { Json.parse(raw) }.getOrNull() as? Map<String, Any?>)?.let { m ->
        return m.mapValues { it.value?.toString().orEmpty() }
    }
    return raw.split(',', ';').mapNotNull { part ->
        val k = part.substringBefore('=', "").trim()
        if (k.isEmpty()) null else k to part.substringAfter('=').trim()
    }.toMap()
}

/** Does recipe steps on the real screen with the accessibility service. */
private class LiveRecipeScreen(
    private val context: Context,
    private val service: JarvisAccessibilityService,
    private val planner: Planner,
    private val apps: AppList,
    private val app: Planner.App,
    private val risky: Boolean,
    private val params: Map<String, String>,
) : RecipeScreen {

    override suspend fun perform(step: RecipeStep): Boolean = when (step.action) {
        "open" -> planner.openIfNeeded(service, app.pkg, apps)
        "link" -> link(step.target)
        "tab" -> Skills.read(service)?.let { s ->
            (Skills.pickTab(s.items, step.target, s.height) as? Skills.Pick.Found)?.item?.node?.let { UiQuery.tap(it) }
        } == true
        "tap" -> tap(step)
        "type" -> field(step)?.let { UiQuery.type(context, it, step.text) } == true
        "enter" -> editable()?.let { UiQuery.enter(it) } ?: true
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "scroll" -> service.rootInActiveWindow?.findFirst { it.isScrollable && it.isVisibleToUser }
            ?.scroll(step.target != "up") == true
        "wait" -> { delay(WAIT_MS); true }
        "search" -> (searchByLink(context, app, step.text) ?: searchOnScreen(context, service, app, step.text)).ok
        else -> false
    }

    override fun shows(expect: String): Boolean {
        val card = app.card
        val known = card?.screen(expect)
        val inApp = service.appPackage() == app.pkg
        if (card != null && known != null) {
            val live = service.liveScreen()
            if (MapPlan.matchScore(AppCard.fingerprint(card, known), live?.fingerprint.orEmpty(), live?.texts.orEmpty()) > 0) return true
            // Unchecked card selectors can't judge the screen; being in the app is enough.
            if (!card.verified && inApp) return true
        } else if (card != null && inApp) {
            return true // expect is not a card screen (e.g. "browser"): the app being open is the check.
        }
        return Skills.read(service)?.items?.any { it.label.contains(expect, ignoreCase = true) } == true
    }

    override suspend fun settle() = UiQuery.settle(service, tries = 4, pauseMs = 300)

    private fun link(task: String): Boolean {
        val link = app.card?.deepLinks?.firstOrNull { it.task == task } ?: return false
        val values = params + ("number" to (params["number"] ?: params["contact"].orEmpty()))
        val uri = LINK_RE.replace(link.uri) { Uri.encode(values[it.groupValues[1]].orEmpty()) }
        val intent = if (uri.isBlank()) Intent(link.action) else Intent(link.action, Uri.parse(uri))
        return context.launch(Intent(intent).setPackage(app.pkg)) || context.launch(intent)
    }

    private suspend fun tap(step: RecipeStep): Boolean {
        val node = bySelectors(step.selectors) ?: run {
            val s = Skills.read(service) ?: return false
            val pick = if (RecipeBook.isFirstResult(step.target)) {
                val below = s.items.filter { it.editable }.minByOrNull { it.top }?.bottom ?: 0
                Skills.firstResult(s.items, below, s.height)?.let { Skills.Pick.Found(it) } ?: Skills.Pick.NotFound
            } else Skills.pickNamed(s.items, step.target)
            val item = (pick as? Skills.Pick.Found)?.item ?: return ocrTap(step)
            if (Skills.isRisky(item.label) && !risky) return false
            item.node
        } ?: return false
        return UiQuery.tap(node)
    }

    /** Last try for a named tap: find the words on the pixels (§H.8) and tap their centre. */
    private suspend fun ocrTap(step: RecipeStep): Boolean {
        if (step.target.isBlank() || RecipeBook.isFirstResult(step.target)) return false
        val item = (ScreenOcr.find(service, step.target) as? Skills.Pick.Found)?.item ?: return false
        if (Skills.isRisky(item.label) && !risky) return false
        val (x, y) = ScreenOcr.centre(item)
        return UiQuery.tapAt(x, y)
    }

    private suspend fun field(step: RecipeStep): AccessibilityNodeInfo? =
        bySelectors(step.selectors)?.takeIf { it.isEditable } ?: editable()

    private fun editable(): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.findFirst { it.isEditable && it.isVisibleToUser }

    private suspend fun bySelectors(list: List<Map<String, String>>): AccessibilityNodeInfo? =
        AppCard.toSelectors(list).takeIf { it.isNotEmpty() }?.let { UiQuery.waitFor(it, SELECTOR_WAIT_MS) }

    private companion object {
        const val WAIT_MS = 500L
        const val SELECTOR_WAIT_MS = 3000L
    }
}
