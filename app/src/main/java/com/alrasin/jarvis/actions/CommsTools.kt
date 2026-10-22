package com.alrasin.jarvis.actions

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Contacts lookup by spoken name. */
class Contacts(private val context: Context) {
    data class Contact(val name: String, val number: String)

    fun all(): List<Contact> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = ArrayList<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) out.add(Contact(c.getString(0) ?: "", c.getString(1) ?: "")) }
        return out.distinctBy { it.name to ContactResolver.number(it.number) }
    }

    /** Spoken digits ("01712 345678") are used as the number. */
    fun find(name: String): Contact? = (resolve(name) as? ContactResolver.Resolution.Found)?.contact

    fun resolve(name: String): ContactResolver.Resolution = ContactResolver.resolve(
        name, if (ContactResolver.number(name) != null) emptyList() else all(),
    )

    /** Contact name for a sender. Senders like "Teletalk" or short codes have too few digits to match: keep them as they are. */
    fun nameFor(number: String): String {
        val n = number.filter { it.isDigit() }.takeLast(9)
        if (n.length < MIN_MATCH_DIGITS) return number
        return all().firstOrNull { it.number.filter { d -> d.isDigit() }.endsWith(n) }?.name ?: number
    }

    companion object {
        const val MIN_MATCH_DIGITS = 7
    }
}

fun commsTools(context: Context, contacts: Contacts): List<Tool> = listOf(
    SimpleTool("call", "Phone call to a contact", listOf(arg("contact", "name or number"), arg("app", "phone or whatsapp", required = false)), Risk.CONFIRM) { c, _ ->
        val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("Call who?")
        val p = when (val match = contacts.resolve(who)) {
            is ContactResolver.Resolution.Found -> match.contact
            is ContactResolver.Resolution.Ambiguous -> return@SimpleTool ToolResult.fail(match.question)
            ContactResolver.Resolution.NotFound -> return@SimpleTool ToolResult.fail("I can't find $who in your contacts.")
        }
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return@SimpleTool ToolResult.fail("I need the phone permission. Open Jarvis setup.")
        if (context.launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(p.number)))))
            ToolResult.ok("Boss, I requested a call to ${c.arg("contact_name")?.takeIf { ContactResolver.number(who) != null } ?: p.name}.")
        else ToolResult.fail("Boss, I couldn't open the phone app.")
    },
    SimpleTool("send_sms", "Send a text message (SMS)", listOf(arg("contact", "name or number"), arg("text", "message")), Risk.CONFIRM) { c, _ ->
        val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("To who?")
        val text = c.arg("text") ?: return@SimpleTool ToolResult.fail("What should I say?")
        val p = when (val match = contacts.resolve(who)) {
            is ContactResolver.Resolution.Found -> match.contact
            is ContactResolver.Resolution.Ambiguous -> return@SimpleTool ToolResult.fail(match.question)
            ContactResolver.Resolution.NotFound -> return@SimpleTool ToolResult.fail("I can't find $who in your contacts.")
        }.let { it.copy(name = c.arg("contact_name")?.takeIf { ContactResolver.number(who) != null } ?: it.name) }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return@SimpleTool ToolResult.fail("I need the SMS permission. Open Jarvis setup.")
        @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
        sendSms(context, sms, p, text)
    },
    SimpleTool("send_whatsapp", "Send a WhatsApp message", listOf(arg("contact", "name or number"), arg("text", "message")), Risk.CONFIRM) { c, _ ->
        val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("To who?")
        val text = c.arg("text") ?: return@SimpleTool ToolResult.fail("What should I say?")
        val p = when (val match = contacts.resolve(who)) {
            is ContactResolver.Resolution.Found -> match.contact
            is ContactResolver.Resolution.Ambiguous -> return@SimpleTool ToolResult.fail(match.question)
            ContactResolver.Resolution.NotFound -> return@SimpleTool ToolResult.fail("I can't find $who in your contacts.")
        }
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { context.isInstalled(it) }
            ?: return@SimpleTool ToolResult.fail("WhatsApp is not installed.")
        var number = p.number.filter { it.isDigit() || it == '+' }.removePrefix("+")
        if (number.startsWith("0")) number = "88$number"   // Bangladesh local → international (+880)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}")
        if (context.launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)))
            ToolResult.ok("Boss, I opened WhatsApp with the message ready. Tap send.")
        else ToolResult.fail("Boss, I couldn't open WhatsApp.")
    },
    SimpleTool("read_messages", "Read the latest text messages") { _, _ ->
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return@SimpleTool ToolResult.fail("I need the SMS permission. Open Jarvis setup.")
        val inbox = Uri.parse("content://sms/inbox")
        // MIUI's Messages app leaves read = 0 on messages the user has opened; it only sets seen = 1
        val unreadWhere = "read = 0 AND seen = 0"
        val unreadCount = context.contentResolver.query(inbox, arrayOf("_id"), unreadWhere, null, null)?.use { it.count } ?: 0
        val msgs = ArrayList<Pair<String, String>>()
        val where = if (unreadCount > 0) unreadWhere else null
        context.contentResolver.query(inbox, arrayOf("address", "body"), where, null, "date DESC")?.use { c ->
            while (c.moveToNext() && msgs.size < 3) msgs.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
        }
        if (msgs.isEmpty()) return@SimpleTool ToolResult.ok("No messages.")
        val list = msgs.map { "${contacts.nameFor(it.first)}: ${it.second.take(160)}" }
        val head = when {
            unreadCount > msgs.size -> "$unreadCount unread. Newest ${msgs.size}:"
            unreadCount > 0 -> "$unreadCount unread."
            else -> "No unread messages. Latest:"
        }
        ToolResult.ok("$head ${list.joinToString(". ")}", list.joinToString("\n"))
    },
    SimpleTool("compose_email", "Write an email (opens the mail app)", listOf(arg("to", "address or contact", required = false), arg("subject", "subject", required = false), arg("body", "text", required = false))) { c, _ ->
        val uri = Uri.parse("mailto:" + (c.arg("to")?.replace(" at ", "@")?.replace(" dot ", ".")?.replace(" ", "") ?: ""))
        val i = Intent(Intent.ACTION_SENDTO, uri)
        c.arg("subject")?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
        c.arg("body")?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        if (context.launch(i)) ToolResult.ok("Boss, I opened email with the message ready. Tap send.") else ToolResult.fail("No mail app found.")
    },
)

/** Every part must receive its own successful sent callback. Null means still unconfirmed. */
internal class SmsSentStatus(private val partCount: Int) {
    private val confirmed = mutableSetOf<Int>()
    private var failed = false

    fun record(part: Int, sent: Boolean): Boolean? {
        if (part !in 0 until partCount) return null
        if (!sent) failed = true else confirmed.add(part)
        return when {
            failed -> false
            confirmed.size == partCount -> true
            else -> null
        }
    }
}

private suspend fun sendSms(context: Context, sms: SmsManager, contact: Contacts.Contact, text: String): ToolResult {
    val parts = sms.divideMessage(text)
    if (parts.isEmpty()) return ToolResult.fail("Boss, the message is empty.")
    val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
    val status = SmsSentStatus(parts.size)
    val result = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != action) return
            status.record(intent.getIntExtra("part", -1), resultCode == Activity.RESULT_OK)?.let { result.complete(it) }
        }
    }
    val callbacks = arrayListOf<PendingIntent>()
    var registered = false
    try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        registered = true
        parts.indices.forEach { part ->
            callbacks.add(PendingIntent.getBroadcast(context, part,
                Intent(action).setPackage(context.packageName).putExtra("part", part),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE))
        }
        sms.sendMultipartTextMessage(contact.number, null, parts, callbacks, null)
        return when (withTimeoutOrNull(5_000) { result.await() }) {
            true -> ToolResult.ok("Boss, message sent to ${contact.name}.")
            false -> ToolResult.fail("Boss, the message wasn't fully sent.")
            null -> ToolResult.fail("Boss, I requested the SMS, but couldn't confirm it was sent. Check Messages.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return ToolResult.fail("Boss, I couldn't confirm the SMS was sent. Check Messages.")
    } finally {
        if (registered) context.unregisterReceiver(receiver)
        callbacks.forEach { it.cancel() }
    }
}
