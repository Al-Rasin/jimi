package com.alrasin.jarvis.actions

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.telephony.SmsManager
import com.alrasin.jarvis.core.PhoneNumbers
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.isInstalled
import com.alrasin.jarvis.core.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pure text helpers for contact editing (ROADMAP #33). Unit tested. */
object ContactText {
    private val EMAIL = Regex("^[a-z0-9._%+-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}$")
    private val SPOKEN = listOf(
        Regex("\\s+dot\\s+") to ".", Regex("\\s+(?:underscore|under score)\\s+") to "_",
        Regex("\\s+(?:dash|hyphen|minus)\\s+") to "-", Regex("\\s+plus\\s+") to "+",
    )

    /** "Rahim At Gmail dot com" → "rahim@gmail.com"; null when it is not an email. The last " at " is the @. */
    fun email(spoken: String): String? {
        var t = " " + spoken.lowercase().trim().trimEnd('.', '!', '?') + " "
        if ('@' !in t) {
            val at = t.lastIndexOf(" at ")
            if (at < 0) return null
            t = t.substring(0, at) + "@" + t.substring(at + 4)
        }
        SPOKEN.forEach { (re, to) -> t = " " + t.trim() + " "; t = t.replace(re, to) }
        val out = t.replace(Regex("\\s+"), "")
        return out.takeIf { EMAIL.matches(it) }
    }

    /** "rahim uddin" → "Rahim Uddin". */
    fun name(spoken: String): String = spoken.trim().split(Regex("\\s+"))
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Text sent when sharing a contact. */
    fun card(name: String, number: String, email: String? = null): String =
        "$name: $number" + (email?.let { "\n$it" } ?: "")
}

fun contactEditTools(context: Context, contacts: Contacts): List<Tool> {
    fun can(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Found contact, or a fail reply. "rahims" (lost apostrophe) is tried as "rahim". */
    fun find(who: String): Pair<Contacts.Contact?, ToolResult?> {
        var match = contacts.resolve(who)
        if (match == ContactResolver.Resolution.NotFound && who.endsWith("s") && who.length > 3) match = contacts.resolve(who.dropLast(1))
        return when (match) {
            is ContactResolver.Resolution.Found -> match.contact to null
            is ContactResolver.Resolution.Ambiguous -> null to ToolResult.fail(match.question)
            ContactResolver.Resolution.NotFound -> null to ToolResult.fail("I can't find $who in your contacts.")
        }
    }

    /** Newest call-log number that is not saved yet. */
    fun lastUnsaved(): String? {
        if (!can(Manifest.permission.READ_CALL_LOG)) return null
        val saved = contacts.all()
        return context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER), null, null, "${CallLog.Calls.DATE} DESC")
            ?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < 50) {
                    val num = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val tail = PhoneNumbers.tail(num)
                    if (tail.length >= Contacts.MIN_MATCH_DIGITS && saved.none { PhoneNumbers.endsWith(it.number, tail) }) return@use num
                }
                null
            }
    }

    fun rawContactId(contactId: Long): Long? = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(contactId.toString()), null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun emailOf(contactId: Long?): String? = contactId?.let { id ->
        context.contentResolver.query(Email.CONTENT_URI, arrayOf(Email.ADDRESS), "${Email.CONTACT_ID} = ?", arrayOf(id.toString()), null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    return listOf(
        SimpleTool(
            "save_contact",
            "Save a phone number as a new contact; with no number, saves the last unsaved caller",
            listOf(arg("name", "contact name"), arg("number", "phone number", required = false)),
            Risk.CONFIRM,
        ) { c, _ ->
            val name = ContactText.name(c.require("name", "Save it as who?"))
            if (!can(Manifest.permission.WRITE_CONTACTS)) return@SimpleTool ToolResult.fail("I need the contacts permission. Open Jarvis setup.")
            val number = c.arg("number")?.let { n -> PhoneNumbers.parse(n) ?: return@SimpleTool ToolResult.fail("That doesn't sound like a phone number.") }
                ?: lastUnsaved() ?: return@SimpleTool ToolResult.fail("Which number should I save?")
            val already = contacts.nameFor(number)
            if (already != number) return@SimpleTool ToolResult.fail("That number is already saved as $already.")
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, number)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE).build(),
            )
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess }
            if (ok) ToolResult.ok("Saved $number as $name.") else ToolResult.fail("I couldn't save that contact.")
        },
        SimpleTool(
            "add_contact_email",
            "Add an email address to a saved contact",
            listOf(arg("contact", "contact name"), arg("email", "email address, may be spoken like 'rahim at gmail dot com'")),
            Risk.CONFIRM,
        ) { c, _ ->
            val who = c.require("contact", "Add the email to who?")
            val email = ContactText.email(c.require("email", "What is the email?"))
                ?: return@SimpleTool ToolResult.fail("That doesn't sound like an email address.")
            if (!can(Manifest.permission.WRITE_CONTACTS)) return@SimpleTool ToolResult.fail("I need the contacts permission. Open Jarvis setup.")
            val (p, fail) = find(who)
            if (p == null) return@SimpleTool fail!!
            val raw = p.contactId?.let(::rawContactId) ?: return@SimpleTool ToolResult.fail("I can't edit ${p.name}'s contact.")
            val op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
                .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email)
                .withValue(Email.TYPE, Email.TYPE_OTHER).build()
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op)) }.isSuccess }
            if (ok) ToolResult.ok("Added $email to ${p.name}.") else ToolResult.fail("I couldn't add that email.")
        },
        SimpleTool(
            "share_contact",
            "Share a contact's name and number: to a person (to) by WhatsApp, Messenger or SMS, or open the app's share screen",
            listOf(
                arg("contact", "contact to share"),
                arg("to", "who receives it", required = false),
                arg("app", "app to share with", required = false, choices = listOf("whatsapp", "messenger", "sms")),
            ),
        ) { c, _ ->
            val (p, fail) = find(c.require("contact", "Share whose contact?"))
            if (p == null) return@SimpleTool fail!!
            val text = ContactText.card(p.name, p.number, emailOf(p.contactId))
            val app = c.arg("app")?.lowercase()
            val to = c.arg("to")
            if (to != null) {
                val (r, rFail) = find(to)
                if (r == null) return@SimpleTool rFail!!
                return@SimpleTool when (app) {
                    "whatsapp", "messenger" -> AppCommunications(context, contacts).message(r, app, text)
                    else -> {
                        if (!can(Manifest.permission.SEND_SMS)) return@SimpleTool ToolResult.fail("I need the SMS permission. Open Jarvis setup.")
                        @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
                        sendSms(context, sms, r, text)
                    }
                }
            }
            val pkg = when (app) {
                "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull(context::isInstalled)
                    ?: return@SimpleTool ToolResult.fail("WhatsApp is not installed.")
                "messenger" -> "com.facebook.orca".takeIf(context::isInstalled) ?: return@SimpleTool ToolResult.fail("Messenger is not installed.")
                else -> null
            }
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            val intent = if (pkg != null) send.setPackage(pkg) else Intent.createChooser(send, "Share ${p.name}")
            if (context.launch(intent)) ToolResult.ok("Pick who gets ${p.name}'s contact.") else ToolResult.fail("I couldn't open the share screen.")
        },
    )
}
