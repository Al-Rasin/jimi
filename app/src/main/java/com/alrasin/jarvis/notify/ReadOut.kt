package com.alrasin.jarvis.notify

import com.alrasin.jarvis.brain.ContextModes
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.data.NotificationLog

/**
 * Which new notifications Jimi reads aloud, and what it says (D-080). Pure so it is unit tested;
 * the listener only feeds it the row and the phone's state.
 */
object ReadOut {
    /** "What to read" setting. */
    enum class What(val id: String, val label: String) {
        PEOPLE_IMPORTANT("people_important", "People + important"),
        PEOPLE("people", "People only");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: PEOPLE_IMPORTANT }
    }

    /** "When to read" setting. */
    enum class When(val id: String, val label: String) {
        OFF("off", "Off"),
        CAR_HEADPHONES("car_headphones", "Car or headphones"),
        ALWAYS("always", "Every time");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    /** Per-app rule (app_rule.mode). Old "keep" means read. */
    enum class AppMode(val id: String, val label: String) {
        READ("read", "Read"), SILENT("silent", "Silent"), CLEAR("clear", "Clear");
        companion object {
            fun of(id: String?) = when (id) { "keep" -> READ; else -> entries.firstOrNull { it.id == id } }
        }
    }

    enum class Kind { PERSON, IMPORTANT, CODE, OTHER }

    data class Phone(
        val car: Boolean = false,
        val headphones: Boolean = false,
        val bedtime: Boolean = false,
        val locked: Boolean = false,
        val inCall: Boolean = false,
        val doNotDisturb: Boolean = false,
        val ringerSilent: Boolean = false,
        val jimiBusy: Boolean = false,
    )

    data class Prefs(val what: What = What.PEOPLE_IMPORTANT, val whenRead: When = When.CAR_HEADPHONES, val fullWhenLocked: Boolean = false)

    /** [line] is null when Jimi stays silent; [why] is always set, for the log. */
    data class Decision(val line: String?, val why: String)

    /** Old switches → new "when": important alerts on = every time; messages on = car/headphones; else off. */
    fun migrateWhen(readImportant: Boolean, readMessages: Boolean): When = when {
        readImportant -> When.ALWAYS
        readMessages -> When.CAR_HEADPHONES
        else -> When.OFF
    }

    private val IMPORTANT_CATEGORIES = setOf("alarm", "reminder", "event", "call", "missed_call")
    private val IMPORTANT_WORDS = Regex(
        "\\b(missed call|voicemail|urgent|emergency|security alert|sign.in|login attempt|delivered|out for delivery|" +
            "order shipped|arriving|flight|boarding|gate|appointment|interview|meeting|reminder|battery low|storage full)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CODE_WORDS = Regex("\\b(otp|one.time|verification|passcode|pin|code)\\b", RegexOption.IGNORE_CASE)

    fun kind(row: NotificationLog, category: String?, chat: Boolean): Kind {
        val all = "${row.title} ${row.text}"
        if (CODE_WORDS.containsMatchIn(all) && Triage.hasCode(all)) return Kind.CODE
        if (Proactive.isChatLike(row, chat)) return Kind.PERSON
        if (category in IMPORTANT_CATEGORIES || IMPORTANT_WORDS.containsMatchIn(all)) return Kind.IMPORTANT
        return Kind.OTHER
    }

    fun decide(row: NotificationLog, kind: Kind, rule: AppMode?, prefs: Prefs, phone: Phone): Decision {
        if (Triage.isMoneyApp(row.pkg)) return Decision(null, "money app")
        if (prefs.whenRead == When.OFF) return Decision(null, "read-out off")
        val onTheGo = phone.car || phone.headphones
        if (prefs.whenRead == When.CAR_HEADPHONES && !onTheGo) return Decision(null, "not in car or headphones")
        quietReason(phone, onTheGo)?.let { return Decision(null, it) }
        if (rule == AppMode.SILENT || rule == AppMode.CLEAR) return Decision(null, "app set to ${rule.id}")
        val wanted = rule == AppMode.READ || when (kind) {
            Kind.PERSON -> true
            Kind.IMPORTANT, Kind.CODE -> prefs.what == What.PEOPLE_IMPORTANT
            Kind.OTHER -> false
        }
        if (!wanted) return Decision(null, "not ${if (prefs.what == What.PEOPLE) "a person" else "people or important"}")
        val line = line(row, kind, whoOnly = phone.locked && !prefs.fullWhenLocked) ?: return Decision(null, "empty")
        return Decision(line, kind.name.lowercase())
    }

    private fun quietReason(p: Phone, onTheGo: Boolean): String? = when {
        p.bedtime -> "bedtime"
        p.inCall -> "in a call"
        p.jimiBusy -> "Jimi busy"
        // In the car or on headphones nobody else hears it, so phone silent / DND does not block.
        !onTheGo && p.doNotDisturb -> "do not disturb"
        !onTheGo && p.ringerSilent -> "phone on silent"
        else -> null
    }

    /** Codes are never read; locked phone says only who or which app. */
    private fun line(row: NotificationLog, kind: Kind, whoOnly: Boolean): String? {
        val app = row.appName.ifBlank { "an app" }
        val who = row.title.trim()
        return when {
            kind == Kind.CODE -> "${Persona.ADDRESS}, a code from $app."
            kind == Kind.PERSON && whoOnly -> if (who.isEmpty()) "${Persona.ADDRESS}, new message on $app." else "${Persona.ADDRESS}, message from $who on $app."
            kind == Kind.PERSON -> ContextModes.messageLine(app, row.title, row.text)?.let { "${Persona.ADDRESS}, $it" }
            whoOnly -> "${Persona.ADDRESS}, alert from $app."
            else -> {
                val text = row.text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
                val body = listOf(who, text).filter { it.isNotEmpty() }.distinct().joinToString(": ")
                if (body.isEmpty()) null else "${Persona.ADDRESS}, $app: $body"
            }
        }
    }

    private const val MAX_TEXT = 120
}

/**
 * Lines waiting to be read. A burst is not dropped: up to [max] wait their turn, the rest become
 * "and N more". The same line is not said twice within [repeatMs] (apps re-post notifications).
 */
class ReadQueue(private val max: Int = 3, private val repeatMs: Long = 10 * 60_000L) {
    private val pending = ArrayDeque<String>()
    private val said = LinkedHashMap<String, Long>()
    private var extra = 0

    @Synchronized fun add(line: String, nowMs: Long): Boolean {
        said.entries.removeAll { nowMs - it.value > repeatMs }
        if (line in pending || line in said) return false
        if (pending.size >= max) extra++ else pending.addLast(line)
        said[line] = nowMs
        return true
    }

    /** Next line to say, or null. The last one in a burst carries the overflow count. */
    @Synchronized fun next(): String? {
        val line = pending.removeFirstOrNull() ?: return null
        if (pending.isNotEmpty() || extra == 0) return line
        val more = extra
        extra = 0
        return "$line And $more more."
    }

    @Synchronized fun isEmpty() = pending.isEmpty()
}
