package com.alrasin.jarvis.agent

import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.RecipeDao
import com.alrasin.jarvis.data.RecipeRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Regexes built once, not on every call (ROADMAP §L.4).
private val CLEAN_RE = Regex("^(?:hey |ok |okay )?(?:jimi|jarvis)[, ]+")
private val CLEAN_RE_2 = Regex("^(?:please |can you |could you |now )+")
private val CLEAN_RE_3 = Regex(" please$")
private val SAME_FIELD_RE = Regex("pass(?:word|code|wd)?|\\botp\\b|\\bpin\\b|one.?time|verif|cvv|cvc|security.?code|auth.?code|\\bcode\\b|secret")
private val RENAMED_SAY_RE = Regex("\\{(\\w+)\\}")

/**
 * Teach mode (ROADMAP §H.6): "Jimi, learn order coffee" → the user does the task by hand → "Jimi, done".
 * Taps and typing become a recipe (source "taught") that "order coffee" replays with no AI.
 * [TeachCommand], [TeachRecorder] and [TeachBook] are pure (unit tested); [TeachSession] holds the live recording.
 */
object TeachCommand {
    sealed interface Cmd {
        data class Start(val name: String) : Cmd
        data object Done : Cmd
        data object Cancel : Cmd
    }

    private val START = listOf(
        Regex("^(?:learn|teach (?:you|yourself)|remember) how to (.+)$"),
        Regex("^(?:watch me|watch how i|let me show you how to|let me teach you(?: how)? to|i(?: will|ll) (?:show|teach) you how to) (.+)$"),
        Regex("^(?:learn|teach you)(?: the| a)? (?:task|new task|routine) (.+)$"),
    )

    /** "learn <name>" when <name> is not an app (apps go to learn_app, §J.5). Checked by [startOrNull]. */
    private val PLAIN_LEARN = Regex("^learn (.+)$")
    private val NOT_A_TASK = Regex("^(?:about|more|what|why|who|this|that|it|from|to|something|english|bangla|spanish)\\b")

    private val DONE = Regex("^(?:(?:ok(?:ay)? )?(?:i am |im |i'm )?(?:done|finished)(?: now)?|that'?s it|thats all|that is it|save it|stop (?:watching|learning|recording)(?: and save)?|(?:you )?got it)$")
    private val CANCEL = Regex("^(?:cancel|stop|forget it|never ?mind|dont save(?: it)?|do not save(?: it)?|cancel (?:learning|teaching|it|that))$")

    /** Start command, or null. [isApp] tells "learn whatsapp" (explore the app) from "learn order coffee". */
    fun startOrNull(text: String, isApp: (String) -> Boolean): Cmd.Start? {
        val t = clean(text)
        START.firstNotNullOfOrNull { it.find(t) }?.let { return start(it.groupValues[1]) }
        val m = PLAIN_LEARN.find(t) ?: return null
        val name = m.groupValues[1].removePrefix("the ").removeSuffix(" app")
        if (NOT_A_TASK.containsMatchIn(name) || isApp(name)) return null
        return start(m.groupValues[1])
    }

    /** Done / cancel; only asked while teaching, so plain "done" can't clash with anything else. */
    fun duringTeach(text: String): Cmd? {
        val t = clean(text)
        return when {
            DONE.matches(t) -> Cmd.Done
            CANCEL.matches(t) -> Cmd.Cancel
            else -> null
        }
    }

    private fun start(raw: String): Cmd.Start? {
        val name = RecipeBook.normalize(raw).removePrefix("to ").trim()
        return if (name.length < 2) null else Cmd.Start(name.take(60))
    }

    private fun clean(text: String) = RecipeBook.normalize(text)
        .replace(CLEAN_RE, "")
        .replace(CLEAN_RE_2, "")
        .replace(CLEAN_RE_3, "")
        .replace("'", "")
        .trim()
}

/** One node the user touched, as seen by the accessibility service. Text of editable fields is never kept here. */
data class TeachNode(
    val id: String = "",
    val text: String = "",
    val desc: String = "",
    val hint: String = "",
    val className: String = "",
    val editable: Boolean = false,
    val password: Boolean = false,
    val inList: Boolean = false,
) {
    val label: String get() = text.ifBlank { desc }
}

sealed interface TeachEvent {
    val pkg: String
    data class Click(override val pkg: String, val node: TeachNode) : TeachEvent
    data class Typed(override val pkg: String, val node: TeachNode, val text: String) : TeachEvent
}

/**
 * Collects what the user does in ONE app. Skips Jimi, the keyboard, system UI and launchers ([skip]),
 * other apps after the first, and password / OTP fields. Typing in one field keeps only its last text.
 */
class TeachRecorder(private val skip: (String) -> Boolean) {
    var pkg: String? = null
        private set
    var skippedSecret = false
        private set
    private val events = mutableListOf<TeachEvent>()

    val size: Int get() = events.size

    fun events(): List<TeachEvent> = events.toList()

    fun add(e: TeachEvent) {
        if (skip(e.pkg) || PhoneProfile.isPaymentPackage(e.pkg)) return
        if (pkg == null) pkg = e.pkg else if (e.pkg != pkg) return
        when (e) {
            is TeachEvent.Click -> {
                if (e.node.editable) return // focusing a field; the typing that follows is the step
                events += e
            }
            is TeachEvent.Typed -> {
                if (isSecret(e.node)) {
                    skippedSecret = true
                    return
                }
                val last = events.lastOrNull()
                if (last is TeachEvent.Typed && sameField(last.node, e.node)) events.removeAt(events.lastIndex)
                if (e.text.isNotBlank()) events += e
            }
        }
        if (events.size > MAX_EVENTS) events.removeAt(0)
    }

    private fun sameField(a: TeachNode, b: TeachNode) = a.id == b.id && a.hint == b.hint && a.className == b.className

    companion object {
        const val MAX_EVENTS = 60
        private val SECRET = SAME_FIELD_RE

        fun isSecret(n: TeachNode): Boolean =
            n.password || SECRET.containsMatchIn("${n.id.substringAfter(":id/")} ${n.hint} ${n.desc}".lowercase().replace('_', ' '))
    }
}

object TeachBook {
    sealed interface Built {
        data class Ok(val recipe: Recipe) : Built
        data class Fail(val why: String) : Built
    }

    /**
     * Recipe from what the user did. Typed text that is also in [name] becomes {text}, {text2}…, and so does
     * a tapped label equal to it ("learn message rahim": typed "rahim", tapped "Rahim"). Typing followed by
     * nothing or by a list tap means the keyboard's search/enter key was pressed, so an "enter" step is added.
     */
    fun build(name: String, events: List<TeachEvent>, pkg: String?, appName: String, version: Long): Built {
        if (pkg == null || events.isEmpty()) return Built.Fail("I didn't see you do anything in an app.")
        var pattern = RecipeBook.normalize(name)
        val values = LinkedHashMap<String, String>() // normalized typed text → param name
        val steps = mutableListOf(RecipeStep("open"))
        events.forEachIndexed { i, e ->
            when (e) {
                is TeachEvent.Typed -> {
                    val t = RecipeBook.normalize(e.text)
                    var text = e.text
                    if (t.length >= 2 && wordIn(t, pattern)) {
                        val p = values.getOrPut(t) { if (values.isEmpty()) "text" else "text${values.size + 1}" }
                        pattern = pattern.replace(Regex("\\b${Regex.escape(t)}\\b"), "{$p}")
                        text = "{$p}"
                    }
                    steps += RecipeStep("type", e.node.hint.ifBlank { e.node.desc }, text, selectors = fieldSelectors(e.node))
                    val next = events.getOrNull(i + 1)
                    if (next == null || (next is TeachEvent.Click && next.node.inList && !sameText(next.node.label, e.text))) {
                        steps += RecipeStep("enter")
                    }
                }
                is TeachEvent.Click -> {
                    val n = e.node
                    if (n.label.isBlank() && n.id.isBlank()) return Built.Fail("I couldn't tell which button you tapped. Please try again.")
                    val label = values[RecipeBook.normalize(n.label)]?.let { "{$it}" } ?: n.label
                    steps += RecipeStep("tap", label, selectors = tapSelectors(n, label))
                }
            }
        }
        val risky = steps.any { it.action == "tap" && Skills.isRisky(it.target) }
        return Built.Ok(
            Recipe(
                pkg = pkg, appName = appName, name = RecipeBook.normalize(name).take(60), say = listOf(pattern),
                params = values.values.toList(), steps = steps, risky = risky, source = SOURCE, minVersion = version,
            ),
        )
    }

    const val SOURCE = "taught"

    private fun wordIn(t: String, pattern: String) = Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(pattern)

    private fun sameText(a: String, b: String) = a.isNotBlank() && RecipeBook.normalize(a) == RecipeBook.normalize(b)

    private fun fieldSelectors(n: TeachNode) = buildList {
        if (n.id.isNotBlank()) add(mapOf("type" to "id", "value" to n.id))
        if (n.hint.isNotBlank()) add(mapOf("type" to "hint", "value" to n.hint))
        if (n.desc.isNotBlank()) add(mapOf("type" to "desc", "value" to n.desc))
    }

    /** First match wins, so labels go first; a list row's id is shared by every row, so it is only a last resort. */
    private fun tapSelectors(n: TeachNode, label: String) = buildList {
        val param = label.startsWith("{")
        if (n.text.isNotBlank()) add(mapOf("type" to "text", "value" to if (param) label else n.text))
        if (n.desc.isNotBlank()) add(mapOf("type" to "desc", "value" to if (param && n.text.isBlank()) label else n.desc))
        if (n.id.isNotBlank() && (!n.inList || n.label.isBlank())) add(mapOf("type" to "id", "value" to n.id))
    }

    /** New say pattern after a rename; params of the old pattern that the new name lacks go on the end. */
    fun renamedSay(oldSay: String, newName: String): String {
        var say = RecipeBook.normalize(newName)
        RENAMED_SAY_RE.findAll(oldSay).map { it.value }.distinct().forEach { if (it !in say) say += " $it" }
        return say
    }

    /** Spoken summary of a saved recipe. */
    fun summary(r: Recipe): String {
        val taps = r.steps.count { it.action == "tap" }
        val types = r.steps.count { it.action == "type" }
        val say = r.say.first().replace(RENAMED_SAY_RE) { if (it.groupValues[1] == "text") "something" else "more" }
        return "Saved \"${r.name}\" in ${r.appName}: $taps taps, $types typing. Say \"$say\" to run it."
    }
}

/** The live recording, fed by the accessibility service, plus an in-memory list of taught recipes for fast matching. */
@Singleton
class TeachSession @Inject constructor(private val dao: RecipeDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var recorder: TeachRecorder? = null
    @Volatile var name: String? = null
        private set
    @Volatile private var startedMs = 0L
    @Volatile private var taughtRows: List<RecipeRow>? = null

    val active: Boolean get() = recorder != null && System.currentTimeMillis() - startedMs < MAX_MS

    fun start(task: String, skip: (String) -> Boolean) {
        recorder = TeachRecorder(skip)
        name = task
        startedMs = System.currentTimeMillis()
        JarvisLog.i("teach: start \"$task\"")
    }

    fun onEvent(e: TeachEvent) {
        if (!active) return
        recorder?.add(e)
    }

    /** Stops and returns the recording (null if none). */
    fun stop(): Pair<String, TeachRecorder>? {
        val r = recorder ?: return null
        val n = name ?: return null
        recorder = null
        name = null
        return n to r
    }

    /** Taught recipe rows, cached; [refresh] after save / rename / delete. */
    suspend fun taught(): List<RecipeRow> =
        taughtRows ?: runCatching { dao.all().filter { it.source == TeachBook.SOURCE } }.getOrDefault(emptyList()).also { taughtRows = it }

    fun refresh() {
        taughtRows = null
        scope.launch { taught() }
    }

    private companion object {
        const val MAX_MS = 10 * 60_000L
    }
}
