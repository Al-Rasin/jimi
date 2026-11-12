package com.alrasin.jarvis.agent

import android.content.Context
import com.alrasin.jarvis.actions.a11y.scroll
import com.alrasin.jarvis.agent.appmap.AppCard
import com.alrasin.jarvis.agent.appmap.AppCards
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.TextNorm
import com.alrasin.jarvis.data.RecipeDao
import com.alrasin.jarvis.data.RecipeRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recipes (ROADMAP §H.5): a fixed list of steps for one spoken command in one app. They come from app cards
 * (tasks) or are saved after the AI screen agent finished a task, so the same command next time runs with no AI.
 * [RecipeBook] and [RecipeRunner] are pure (unit tested); [RecipeStore] and [recipeTools] touch Android.
 */
data class RecipeStep(
    val action: String,
    val target: String = "",
    val text: String = "",
    val expect: String = "",
    /** {type, value} maps, like app card selectors; may hold {params}. */
    val selectors: List<Map<String, String>> = emptyList(),
)

data class Recipe(
    val id: Long = 0,
    val pkg: String,
    val appName: String,
    val name: String,
    /** Spoken patterns with {param} holes, e.g. "search {query} on youtube". */
    val say: List<String>,
    val params: List<String>,
    val steps: List<RecipeStep>,
    val risky: Boolean,
    /** "card" | "agent" | "taught". */
    val source: String,
    val minVersion: Long = 0L,
    val maxVersion: Long = 0L,
    val aliases: List<String> = emptyList(),
)

object RecipeBook {
    data class Match(val recipe: Recipe, val params: Map<String, String>)

    private val PARAM = Regex("\\{(\\w+)\\}")
    private val TRAILING_PUNCT = Regex("[.!?,]+$")
    private const val FIRST_RESULT = "{first result}"

    fun fromCard(card: AppCard, pkg: String): List<Recipe> = card.tasks.map { task ->
        val elements = card.screens.flatMap { it.elements }
        val steps = task.steps.map { s ->
            val sel = if (s.action == "tap" || s.action == "type") {
                elements.firstOrNull { it.label.equals(s.target, ignoreCase = true) }?.selectors.orEmpty()
            } else emptyList()
            RecipeStep(s.action, s.target, s.text, s.expect, sel)
        }
        val riskyTap = steps.any { it.action == "tap" && Skills.isRisky(it.target) }
        Recipe(
            pkg = pkg, appName = card.name, name = task.name, say = task.say, params = task.params, steps = steps,
            risky = task.risky || riskyTap, source = "card", minVersion = card.minVersion, maxVersion = card.maxVersion,
            aliases = card.aliases,
        )
    }

    fun normalize(text: String): String =
        TextNorm.squash(text).replace(TRAILING_PUNCT, "").trim()

    /** Best recipe for what the user said, with its params filled. Null = none fits. */
    fun match(text: String, recipes: List<Recipe>): Match? {
        val said = normalize(text)
        if (said.isEmpty()) return null
        var best: Match? = null
        var bestScore = -1
        for (r in recipes) {
            for (pattern in r.say) {
                val p = normalize(pattern)
                val literal = PARAM.split(p).joinToString(" ")
                // Taught recipes ("learn order coffee") may be one word: the user picked it, and the fit is exact.
                val minWords = if (r.source == TeachBook.SOURCE) 1 else 2
                if (literal.split(' ').count { it.isNotBlank() } < minWords) continue
                if (r.source == "card" && !namesApp(r, said) && !namesApp(r, p)) continue
                val params = fit(p, said) ?: continue
                val score = literal.count { !it.isWhitespace() } * 2 + when (r.source) {
                    TeachBook.SOURCE -> 2
                    "agent" -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Match(r, params)
                }
            }
        }
        return best
    }

    private fun namesApp(r: Recipe, text: String): Boolean =
        (listOf(r.appName) + r.aliases).any { it.isNotBlank() && Regex("\\b${Regex.escape(it.lowercase())}\\b").containsMatchIn(text) }

    /** Params when [said] fits [pattern], else null. */
    private fun fit(pattern: String, said: String): Map<String, String>? {
        val names = PARAM.findAll(pattern).map { it.groupValues[1] }.toList()
        val regex = buildString {
            append('^')
            PARAM.split(pattern).forEachIndexed { i, lit ->
                if (lit.isNotEmpty()) append(Regex.escape(lit))
                if (i < names.size) append("(.+?)")
            }
            append('$')
        }
        val m = Regex(regex, RegexOption.IGNORE_CASE).find(said) ?: return null
        val values = m.groupValues.drop(1).map { it.trim() }
        if (values.any { it.isEmpty() }) return null
        return names.zip(values).toMap()
    }

    /** Fills {param} holes in a step; unknown params become blank. "{first result}" stays as it is. */
    fun fill(step: RecipeStep, params: Map<String, String>): RecipeStep {
        fun f(s: String) = PARAM.replace(s) { params[it.groupValues[1]].orEmpty() }
        return step.copy(
            target = f(step.target), text = f(step.text), expect = f(step.expect),
            selectors = step.selectors.map { sel -> sel.mapValues { (k, v) -> if (k == "value") f(v) else v } },
        )
    }

    fun versionOk(v: Long, min: Long, max: Long): Boolean = (min == 0L || v >= min) && (max == 0L || v <= max)

    fun isFirstResult(target: String) = target == FIRST_RESULT

    /**
     * Recipe from a finished screen-agent run. Text it typed that the user also said becomes a {text} param,
     * so "note buy milk in keep" also works for "note call mom in keep". Null when it can't be replayed.
     */
    fun fromAgent(
        command: String,
        pkg: String,
        appName: String,
        performed: List<ScreenAgent.Performed>,
        risky: Boolean,
        version: Long,
    ): Recipe? {
        if ('\n' in command) return null
        var pattern = normalize(command)
        val params = mutableListOf<String>()
        val steps = performed.map { p ->
            when (val a = p.action) {
                is AgentAction.OpenApp -> RecipeStep("open")
                is AgentAction.Tap -> {
                    val e = p.element ?: return null
                    val label = e.text.ifBlank { e.description }
                    if (label.isBlank() && e.resourceId.isBlank()) return null
                    RecipeStep("tap", label, selectors = selectors(e))
                }
                is AgentAction.Type -> {
                    var text = a.text
                    val t = normalize(a.text)
                    if (t.length >= 2 && t in pattern) {
                        val name = if (params.isEmpty()) "text" else "text${params.size + 1}"
                        params += name
                        pattern = pattern.replace(t, "{$name}")
                        text = "{$name}"
                    }
                    RecipeStep("type", p.element?.let { it.text.ifBlank { it.description } }.orEmpty(), text,
                        selectors = p.element?.let(::selectors).orEmpty())
                }
                is AgentAction.Scroll -> RecipeStep("scroll", a.direction)
                AgentAction.Back -> RecipeStep("back")
                AgentAction.Home -> RecipeStep("home")
                AgentAction.Wait -> RecipeStep("wait")
                is AgentAction.Done, is AgentAction.AskUser -> return null
            }
        }
        if (steps.none { it.action != "open" }) return null
        return Recipe(
            pkg = pkg, appName = appName, name = normalize(command).take(60), say = listOf(pattern), params = params,
            steps = steps, risky = risky, source = "agent", minVersion = version,
        )
    }

    private fun selectors(e: ScreenElement): List<Map<String, String>> = buildList {
        if (e.text.isNotBlank()) add(mapOf("type" to "text", "value" to e.text))
        if (e.description.isNotBlank()) add(mapOf("type" to "desc", "value" to e.description))
        if (e.resourceId.isNotBlank()) add(mapOf("type" to "id", "value" to e.resourceId))
    }

    fun toRow(r: Recipe, now: Long): RecipeRow = RecipeRow(
        id = r.id, pkg = r.pkg, name = r.name, say = r.say.joinToString("\n"),
        stepsJson = Json.write(r.steps.map { s ->
            mapOf("do" to s.action, "target" to s.target, "text" to s.text, "expect" to s.expect, "selectors" to s.selectors)
        }),
        risky = r.risky, source = r.source, minVersion = r.minVersion, maxVersion = r.maxVersion, timeMs = now,
    )

    @Suppress("UNCHECKED_CAST")
    fun fromRow(row: RecipeRow, appName: String): Recipe {
        val steps = (Json.parse(row.stepsJson) as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }.map { m ->
            RecipeStep(
                action = m["do"]?.toString().orEmpty(),
                target = m["target"]?.toString().orEmpty(),
                text = m["text"]?.toString().orEmpty(),
                expect = m["expect"]?.toString().orEmpty(),
                selectors = (m["selectors"] as? List<Any?>).orEmpty().mapNotNull { s ->
                    (s as? Map<String, Any?>)?.mapValues { it.value?.toString().orEmpty() }
                },
            )
        }
        val say = row.say.split('\n').filter { it.isNotBlank() }
        val params = say.flatMap { p -> PARAM.findAll(p).map { it.groupValues[1] } }.distinct()
        return Recipe(
            id = row.id, pkg = row.pkg, appName = appName, name = row.name, say = say, params = params, steps = steps,
            risky = row.risky, source = row.source, minVersion = row.minVersion, maxVersion = row.maxVersion,
        )
    }
}

/** What the runner needs from the live phone. */
interface RecipeScreen {
    suspend fun perform(step: RecipeStep): Boolean
    fun shows(expect: String): Boolean
    suspend fun settle()
}

/**
 * Runs filled steps in order. A failed step is tried once more. A missed `expect` waits and looks again;
 * only open / link / tab are done again (never a tap, so "Send" is never pressed twice).
 */
class RecipeRunner(private val screen: RecipeScreen) {
    /** Null = all steps worked, else what went wrong. */
    suspend fun run(steps: List<RecipeStep>): String? {
        steps.forEachIndexed { i, step ->
            if (!screen.perform(step) && !screen.perform(step)) return "Step ${i + 1} didn't work"
            screen.settle()
            if (step.expect.isBlank() || screen.shows(step.expect)) return@forEachIndexed
            screen.settle()
            if (screen.shows(step.expect)) return@forEachIndexed
            if (step.action in REDO && screen.perform(step)) {
                screen.settle()
                if (screen.shows(step.expect)) return@forEachIndexed
            }
            return "Step ${i + 1} didn't work"
        }
        return null
    }

    private companion object {
        val REDO = setOf("open", "link", "tab")
    }
}

@Singleton
class RecipeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appCards: AppCards,
    private val dao: RecipeDao,
) {
    /** Card recipes + saved ones, only for installed apps whose version fits. */
    suspend fun all(): List<Recipe> {
        val pm = context.packageManager
        fun version(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0).longVersionCode }.getOrNull()
        fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val cards = appCards.all().flatMap { card ->
            val pkg = card.packages.firstOrNull { version(it) != null } ?: return@flatMap emptyList()
            RecipeBook.fromCard(card, pkg)
        }
        val saved = runCatching { dao.all() }.getOrDefault(emptyList()).map { row ->
            RecipeBook.fromRow(row, appCards.card(row.pkg)?.name ?: label(row.pkg))
        }
        return (saved + cards).filter { r ->
            version(r.pkg)?.let { RecipeBook.versionOk(it, r.minVersion, r.maxVersion) } == true
        }
    }

    suspend fun find(text: String): RecipeBook.Match? = RecipeBook.match(text, all())

    suspend fun save(recipe: Recipe) {
        val row = RecipeBook.toRow(recipe, System.currentTimeMillis())
        dao.deleteSame(row.pkg, row.say)
        dao.put(row)
        JarvisLog.i("recipe saved: ${recipe.appName} \"${row.say}\" (${recipe.steps.size} steps)")
    }
}
