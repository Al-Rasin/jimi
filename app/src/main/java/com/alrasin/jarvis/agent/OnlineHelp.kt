package com.alrasin.jarvis.agent

import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.Web
import com.alrasin.jarvis.brain.CloudBrain
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

// Regexes built once, not on every call (ROADMAP §L.4).
private val STEPS_RE = Regex("^\\s*(?:\\d{1,2}[.)]|[-*•]|step \\d{1,2}:?)\\s+(.+)$", RegexOption.IGNORE_CASE)
private val SEARCH_RE = Regex("uddg=([^&]+)")

/**
 * Online research when the screen agent is stuck (ROADMAP §G.4): web search "how to X in Y on Android",
 * the cloud model turns the results into short steps, and the agent tries again with them as a hint.
 * Needs internet and a cloud key (D-040); never for payment apps (D-072). Risky taps still ask (AgentSafety).
 * [query], [prompt], [steps] and [hint] are pure (unit tested).
 */
object OnlineSteps {
    const val MAX_STEPS = 8
    private const val MAX_SOURCE_CHARS = 6_000

    const val SYSTEM = "You turn web search results into short phone steps. Reply ONLY with a numbered list, " +
        "at most $MAX_STEPS steps, each one short action naming the button or menu to tap " +
        "(for example: 1. Tap the three dots menu). No intro, no notes. If the results do not say how, reply NONE."

    private val LEAD = Regex("^(?:please |can you |could you |hey |ok |okay )*(?:(?:jimi|jarvis)[, ]+)?", RegexOption.IGNORE_CASE)
    private val OPEN_APP = Regex("^(?:open|launch|start|go to) (?:the |my )?.+?(?: app)?,? (?:and(?: then)?|then) ", RegexOption.IGNORE_CASE)

    /** "open whatsapp and turn off read receipts" in WhatsApp → "how to turn off read receipts in WhatsApp Android". */
    fun query(task: String, appName: String): String {
        val what = task.trim().replace(LEAD, "").replace(OPEN_APP, "").trim().trimEnd('.', '?', '!').take(120)
        val app = appName.trim()
        val inApp = if (app.isNotBlank() && !what.contains(app, ignoreCase = true)) " in $app" else ""
        return "how to $what$inApp Android"
    }

    /** Text sent to the cloud model: the task plus search results, cut to a safe size. */
    fun prompt(task: String, appName: String, sources: List<String>): String {
        val body = sources.filter { it.isNotBlank() }.joinToString("\n\n").take(MAX_SOURCE_CHARS)
        val app = if (appName.isBlank()) "" else "App: $appName\n"
        return "${app}Task: ${task.trim()}\n\nSearch results:\n$body"
    }

    /** Numbered or bulleted lines from the model's answer; empty when it said NONE or gave no list. */
    fun steps(answer: String): List<String> {
        if (answer.trim().uppercase().startsWith("NONE")) return emptyList()
        return answer.lines()
            .mapNotNull { STEPS_RE.find(it)?.groupValues?.get(1) }
            .map { it.replace("**", "").trim() }
            .filter { it.length in 3..160 }
            .take(MAX_STEPS)
    }

    /** Hint added to the agent task. */
    fun hint(steps: List<String>): String = if (steps.isEmpty()) "" else
        "Steps found online (may be out of date; follow them only when they match the screen):\n" +
            steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
}

@Singleton
class OnlineHelp @Inject constructor(private val cloud: CloudBrain) {
    private val web = Web()

    /** True when a lookup can run now: cloud on, a key saved and the phone online. */
    suspend fun available(): Boolean = runCatching { cloud.enabled() }.getOrDefault(false)

    /** Steps for [task] in [appName] / [pkg], or empty. Never throws; capped at ~25 s. */
    suspend fun lookup(task: String, appName: String, pkg: String): List<String> {
        if (PhoneProfile.isPaymentPackage(pkg) || !available()) return emptyList()
        val provider = cloud.provider() ?: return emptyList()
        return withTimeoutOrNull(25_000) {
            runCatching {
                val q = OnlineSteps.query(task, appName)
                val sources = search(q)
                if (sources.isEmpty()) return@runCatching emptyList()
                val answer = provider.chat(OnlineSteps.SYSTEM, emptyList(), OnlineSteps.prompt(task, appName, sources))
                OnlineSteps.steps(answer).also { JarvisLog.i("online help: \"$q\" → ${it.size} steps") }
            }.onFailure { JarvisLog.i("online help failed: ${it.message}") }.getOrNull()
        }.orEmpty()
    }

    /** DuckDuckGo snippets plus the text of the top page. */
    private suspend fun search(q: String): List<String> {
        val doc = Jsoup.parse(web.get("https://html.duckduckgo.com/html/?q=${Web.enc(q)}"))
        val results = doc.select(".result").mapNotNull { r ->
            val a = r.selectFirst(".result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            SEARCH_RE.find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            Triple(a.text(), r.selectFirst(".result__snippet")?.text().orEmpty(), href)
        }.filter { it.third.startsWith("http") && !it.third.contains("duckduckgo.com/y.js") }.take(5)
        val top = results.firstOrNull()?.third?.let { url ->
            runCatching {
                val page = Jsoup.parse(web.get(url), url)
                page.select("script,style,nav,header,footer,aside").remove()
                page.select("h2, h3, p, li").joinToString("\n") { it.text() }.take(3_500)
            }.getOrNull()
        }
        return listOfNotNull(top) + results.map { "${it.first}: ${it.second}" }
    }
}
