package com.alrasin.jarvis.actions

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.launch
import com.alrasin.jarvis.notify.JarvisNotificationListener

/** Pure call text and choices (ROADMAP B §I.1). Unit tested. */
object CallText {
    /** Apps whose ringing call shows as a CATEGORY_CALL notification with answer/decline buttons. */
    val APP_CALLS = mapOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp", "com.facebook.orca" to "Messenger")

    private val ANSWER = Regex("(?i)\\b(answer|accept|pick up)\\b")
    private val DECLINE = Regex("(?i)\\b(decline|reject|dismiss|hang up|end call|end)\\b")

    fun ringing(name: String, app: String? = null): String {
        val who = name.trim().ifEmpty { "Unknown number" }
        return if (app == null) "$who is calling." else "$who is calling on $app."
    }

    /** Index of the notification button to press, or -1. */
    fun actionIndex(titles: List<String?>, answer: Boolean): Int =
        titles.indexOfFirst { it != null && (if (answer) ANSWER else DECLINE).containsMatchIn(it) }

    /** Incoming app call: it has an answer button. An ongoing call has only "hang up". */
    fun isIncoming(titles: List<String?>) = actionIndex(titles, answer = true) >= 0

    /** "3 missed calls: Rahim twice, Karim." Newest caller first. */
    fun missedSummary(names: List<String>): String {
        if (names.isEmpty()) return "No missed calls."
        val counts = LinkedHashMap<String, Int>()
        names.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val who = counts.entries.joinToString(", ") { (n, c) -> n + times(c) }
        val head = if (names.size == 1) "1 missed call" else "${names.size} missed calls"
        return "$head: $who."
    }

    private fun times(c: Int) = when (c) { 1 -> ""; 2 -> " twice"; else -> " $c times" }
}

private const val KEY_CALL = 5
private const val KEY_ENDCALL = 6
private const val MISSED_WINDOW_MS = 24 * 60 * 60 * 1000L

/** Names (or "Unknown number") of missed callers since [since], newest first; null without call log permission. */
fun missedCallers(context: Context, contacts: Contacts, since: Long): List<String>? {
    if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
    val numbers = context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER),
        "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?", arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
        "${CallLog.Calls.DATE} DESC")?.use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) }
    }.orEmpty()
    JarvisLog.i("missed calls: ${numbers.size}")
    return numbers.map { if (it.isBlank()) "Unknown number" else contacts.nameFor(it) }
}

fun callTools(context: Context, contacts: Contacts): List<Tool> {
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val telecom = context.getSystemService(TelecomManager::class.java)
    val phone = context.getSystemService(TelephonyManager::class.java)

    @Suppress("DEPRECATION")
    fun phoneRinging() = granted(Manifest.permission.READ_PHONE_STATE) &&
        runCatching { phone.callState == TelephonyManager.CALL_STATE_RINGING }.getOrDefault(false)

    fun appCall(): Notification? = JarvisNotificationListener.instance?.let { l ->
        runCatching { l.activeNotifications }.getOrNull()?.firstOrNull {
            it.packageName in CallText.APP_CALLS && it.notification.category == Notification.CATEGORY_CALL &&
                CallText.isIncoming(it.notification.actions.orEmpty().map { a -> a.title?.toString() })
        }?.notification
    }

    fun pressAppButton(answer: Boolean): Boolean {
        val n = appCall() ?: return false
        val actions = n.actions.orEmpty()
        val i = CallText.actionIndex(actions.map { it.title?.toString() }, answer)
        if (i < 0) return false
        return runCatching { actions[i].actionIntent.send(); true }.getOrDefault(false)
    }

    /** Last call number of [type] in the call log; null if none or no permission. */
    fun lastNumber(vararg types: Int): String? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        val sel = "${CallLog.Calls.TYPE} IN (${types.joinToString(",")})"
        return context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER), sel, null,
            "${CallLog.Calls.DATE} DESC")?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    fun dial(number: String?, none: String): ToolResult {
        if (number == null) return if (granted(Manifest.permission.READ_CALL_LOG)) ToolResult.fail(none)
        else ToolResult.fail("I need the call log permission. Open Jarvis setup.")
        if (!granted(Manifest.permission.CALL_PHONE)) return ToolResult.fail("I need the phone call permission. Open Jarvis setup.")
        val name = contacts.nameFor(number)
        return if (context.launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))) ToolResult.ok("Calling $name.")
        else ToolResult.fail("I couldn't start the call.")
    }

    return listOf(
        SimpleTool("answer_call", "Answer the ringing phone, WhatsApp or Messenger call", emptyList(), Risk.SAFE) { _, _ ->
            when {
                phoneRinging() -> {
                    @Suppress("DEPRECATION")
                    val ok = granted(Manifest.permission.ANSWER_PHONE_CALLS) && runCatching { telecom.acceptRingingCall(); true }.getOrDefault(false)
                    if (ok || RootInput.key(KEY_CALL).ok) ToolResult.ok("Answered.") else ToolResult.fail("I couldn't answer the call.")
                }
                pressAppButton(answer = true) -> ToolResult.ok("Answered.")
                else -> ToolResult.fail("No call is ringing.")
            }
        },
        SimpleTool("reject_call", "Reject the ringing call or hang up the current call", emptyList(), Risk.SAFE) { _, _ ->
            @Suppress("DEPRECATION")
            val inCall = granted(Manifest.permission.READ_PHONE_STATE) &&
                runCatching { phone.callState != TelephonyManager.CALL_STATE_IDLE }.getOrDefault(false)
            when {
                inCall -> {
                    @Suppress("DEPRECATION")
                    val ok = granted(Manifest.permission.ANSWER_PHONE_CALLS) && runCatching { telecom.endCall() }.getOrDefault(false)
                    if (ok || RootInput.key(KEY_ENDCALL).ok) ToolResult.ok("Call ended.") else ToolResult.fail("I couldn't end the call.")
                }
                pressAppButton(answer = false) -> ToolResult.ok("Call declined.")
                else -> ToolResult.fail("No call is ringing.")
            }
        },
        SimpleTool("missed_calls", "Say who called and was missed in the last day", emptyList(), Risk.SAFE) { _, _ ->
            val names = missedCallers(context, contacts, System.currentTimeMillis() - MISSED_WINDOW_MS)
                ?: return@SimpleTool ToolResult.fail("I need the call log permission. Open Jarvis setup.")
            ToolResult.ok(CallText.missedSummary(names))
        },
        SimpleTool("call_back", "Call back the last missed or incoming caller", emptyList(), Risk.CONFIRM) { _, _ ->
            dial(lastNumber(CallLog.Calls.MISSED_TYPE, CallLog.Calls.INCOMING_TYPE, CallLog.Calls.REJECTED_TYPE), "No one called you yet.")
        },
        SimpleTool("redial", "Call the last dialed number again", emptyList(), Risk.CONFIRM) { _, _ ->
            dial(lastNumber(CallLog.Calls.OUTGOING_TYPE), "You haven't called anyone yet.")
        },
    )
}
