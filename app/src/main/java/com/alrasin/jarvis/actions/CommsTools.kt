package com.alrasin.jarvis.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.delay

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
        return out.distinctBy { it.name }
    }

    /** Spoken digits ("01712 345678") are used as the number. */
    fun find(name: String): Contact? {
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.length >= 5 && digits.length >= name.replace(" ", "").length - 1) return Contact(digits, digits)
        return Fuzzy.best(name, all(), { it.name }, min = 0.65)
    }

    fun nameFor(number: String): String {
        val n = number.filter { it.isDigit() }.takeLast(9)
        return all().firstOrNull { it.number.filter { d -> d.isDigit() }.endsWith(n) }?.name ?: number
    }
}

fun commsTools(context: Context, contacts: Contacts): List<Tool> = listOf(
    SimpleTool("call", "Phone call to a contact", listOf(arg("contact", "name or number"), arg("app", "phone or whatsapp", required = false)), Risk.CONFIRM) { c, _ ->
        val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("Call who?")
        val p = contacts.find(who) ?: return@SimpleTool ToolResult.fail("I can't find $who in your contacts.")
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return@SimpleTool ToolResult.fail("I need the phone permission. Open Jarvis setup.")
        context.launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(p.number))))
        ToolResult.ok("Calling ${p.name}.")
    },
    SimpleTool("send_sms", "Send a text message (SMS)", listOf(arg("contact", "name or number"), arg("text", "message")), Risk.CONFIRM) { c, _ ->
        val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("To who?")
        val text = c.arg("text") ?: return@SimpleTool ToolResult.fail("What should I say?")
        val p = contacts.find(who) ?: return@SimpleTool ToolResult.fail("I can't find $who in your contacts.")
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return@SimpleTool ToolResult.fail("I need the SMS permission. Open Jarvis setup.")
        @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
        sms.sendMultipartTextMessage(p.number, null, sms.divideMessage(text), null, null)
        ToolResult.ok("Message sent to ${p.name}.")
    },
    SimpleTool("send_whatsapp", "Send a WhatsApp message", listOf(arg("contact", "name or number"), arg("text", "message")), Risk.CONFIRM) { c, _ ->
        val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("To who?")
        val text = c.arg("text") ?: return@SimpleTool ToolResult.fail("What should I say?")
        val p = contacts.find(who) ?: return@SimpleTool ToolResult.fail("I can't find $who in your contacts.")
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { context.isInstalled(it) }
            ?: return@SimpleTool ToolResult.fail("WhatsApp is not installed.")
        var number = p.number.filter { it.isDigit() || it == '+' }.removePrefix("+")
        if (number.startsWith("0")) number = "88$number"   // Bangladesh local → international (+880)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}")
        context.launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
        // the chat opens with the text typed; press Send through accessibility
        repeat(10) {
            delay(700)
            val a = JarvisAccessibilityService.instance ?: return@SimpleTool ToolResult.ok("WhatsApp is open with your message. Please press send.")
            if (a.clickByDescription("Send") || a.clickById("$pkg:id/send")) return@SimpleTool ToolResult.ok("WhatsApp sent to ${p.name}.")
        }
        ToolResult.ok("WhatsApp is open with your message. I couldn't press send.")
    },
    SimpleTool("read_messages", "Read the latest text messages") { _, _ ->
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            return@SimpleTool ToolResult.fail("I need the SMS permission. Open Jarvis setup.")
        val msgs = ArrayList<Triple<String, String, Boolean>>()
        context.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "read"), null, null, "date DESC")?.use { c ->
            while (c.moveToNext() && msgs.size < 5) msgs.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getInt(2) == 1))
        }
        if (msgs.isEmpty()) return@SimpleTool ToolResult.ok("No messages.")
        val unread = msgs.filter { !it.third }
        val list = (unread.ifEmpty { msgs.take(3) }).map { "${contacts.nameFor(it.first)}: ${it.second.take(160)}" }
        val head = if (unread.isNotEmpty()) "${unread.size} unread." else "No unread messages. Latest:"
        ToolResult.ok("$head ${list.joinToString(". ")}", list.joinToString("\n"))
    },
    SimpleTool("compose_email", "Write an email (opens the mail app)", listOf(arg("to", "address or contact", required = false), arg("subject", "subject", required = false), arg("body", "text", required = false))) { c, _ ->
        val uri = Uri.parse("mailto:" + (c.arg("to")?.replace(" at ", "@")?.replace(" dot ", ".")?.replace(" ", "") ?: ""))
        val i = Intent(Intent.ACTION_SENDTO, uri)
        c.arg("subject")?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
        c.arg("body")?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        if (context.launch(i)) ToolResult.ok("Email is ready. Check and send it.") else ToolResult.fail("No mail app found.")
    },
)
