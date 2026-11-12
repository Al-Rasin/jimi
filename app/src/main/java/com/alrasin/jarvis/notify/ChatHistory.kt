package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationLog

// Regexes built once, not on every call (ROADMAP §L.4).
private val WORDS_RE = Regex("[^\\p{L}\\p{N}]+")

/**
 * Last messages from one person (ROADMAP §H.1 read_chat). Pure, so it is unit tested.
 * Source 1: saved notifications (title = sender, or "Sender: text" lines from a group / MessagingStyle).
 * Source 2: chat bubbles read from the open conversation, when notifications have nothing.
 */
object ChatHistory {
    val PACKAGES = mapOf(
        "whatsapp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
        "messenger" to setOf("com.facebook.orca"),
        "sms" to setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
    )

    data class Message(val sender: String, val text: String, val timeMs: Long, val app: String)

    /** One bubble on the chat screen; [mine] = sent by the phone owner. */
    data class Bubble(val text: String, val mine: Boolean)

    private val LINE = Regex("^([^:\\n]{1,40}):\\s+(.+)$")
    private val TITLE_TAIL = Regex("\\s*\\(\\d+ (?:new )?messages?\\)$")
    private val SUMMARY = Regex("^\\d+ (?:new )?messages?(?: from \\d+ chats?)?$", RegexOption.IGNORE_CASE)
    private val TIME_ONLY = Regex("^(?:\\d{1,2}:\\d{2}(?:\\s?[ap]\\.?m\\.?)?|today|yesterday|[a-z]+day|\\d{1,2}/\\d{1,2}/\\d{2,4})$", RegexOption.IGNORE_CASE)

    /** Newest [count] messages from [contact] (oldest first), from notification [log]; [app] null = any chat app. */
    fun lastMessages(log: List<NotificationLog>, contact: String, app: String?, count: Int): List<Message> {
        val pkgs = app?.let { PACKAGES[it] ?: return emptyList() } ?: PACKAGES.values.flatten().toSet()
        val seen = LinkedHashMap<Pair<String, String>, Message>()
        log.filter { it.pkg in pkgs }.sortedBy { it.timeMs }.forEach { n ->
            val title = n.title.replace(TITLE_TAIL, "").trim()
            n.text.lines().map(String::trim).filter { it.isNotEmpty() && !SUMMARY.matches(it) }.forEach { line ->
                val m = LINE.find(line)
                val sender = m?.groupValues?.get(1)?.trim()?.takeUnless { it.equals("Message", true) } ?: title
                val text = m?.groupValues?.get(2)?.trim() ?: line
                if (!sameName(sender, contact)) return@forEach
                // Notifications repeat older lines as new ones arrive: keep each line once, at its latest time.
                val key = sender.lowercase() to text
                seen.remove(key)
                seen[key] = Message(sender, text, n.timeMs, n.appName)
            }
        }
        return seen.values.toList().takeLast(count.coerceAtLeast(1))
    }

    /** Newest [count] incoming bubbles (oldest first); drops the owner's own and time/date labels. */
    fun lastIncoming(bubbles: List<Bubble>, count: Int): List<String> = bubbles
        .filter { !it.mine && it.text.isNotBlank() && !TIME_ONLY.matches(it.text.trim()) }
        .map { it.text.trim() }.takeLast(count.coerceAtLeast(1))

    /** Every word of the shorter name starts a word of the other: "rahim" fits "Rahim Uddin" both ways. */
    fun sameName(name: String, spoken: String): Boolean {
        val have = words(name)
        val want = words(spoken)
        if (have.isEmpty() || want.isEmpty()) return false
        return want.all { w -> have.any { it.startsWith(w) } } || have.all { h -> want.any { it.startsWith(h) } }
    }

    private fun words(s: String) = s.lowercase().split(WORDS_RE).filter { it.isNotEmpty() }

    /** What to say: "Rahim on WhatsApp: hi." or numbered when several. */
    fun say(messages: List<Message>): String {
        val first = messages.first()
        val head = "${first.sender} on ${first.app}"
        if (messages.size == 1) return "$head said: ${first.text}"
        return "Last ${messages.size} from $head: " + messages.mapIndexed { i, m -> "${i + 1}, ${m.text}" }.joinToString(". ")
    }
}
