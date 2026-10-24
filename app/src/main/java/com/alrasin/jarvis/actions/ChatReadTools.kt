package com.alrasin.jarvis.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.notify.ChatHistory

private const val LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
private val APP_NAMES = mapOf("whatsapp" to "WhatsApp", "messenger" to "Messenger", "sms" to "SMS")

/**
 * read_chat (ROADMAP §H.1): last messages from one person. Saved notifications first (works locked),
 * then the SMS inbox, then the open WhatsApp / Messenger chat screen.
 */
fun chatReadTool(context: Context, contacts: Contacts, notifications: NotificationDao): Tool = SimpleTool(
    "read_chat", "Read the last messages one person sent you on WhatsApp, Messenger or SMS",
    listOf(
        arg("contact", "name, nickname, relation or number"),
        arg("app", "chat app", required = false, choices = listOf("whatsapp", "messenger", "sms")),
        arg("count", "how many messages, 1 to 10", required = false),
    ),
) { c, _ ->
    val who = c.arg("contact") ?: return@SimpleTool ToolResult.fail("Whose messages?")
    val app = c.arg("app")?.lowercase()?.replace(" ", "")
    if (app != null && app !in APP_NAMES) return@SimpleTool ToolResult.fail("I can read WhatsApp, Messenger or SMS chats.")
    val count = (c.arg("count")?.toIntOrNull() ?: 1).coerceIn(1, 10)
    val found = (contacts.resolve(who) as? ContactResolver.Resolution.Found)?.contact
    val log = notifications.since(System.currentTimeMillis() - LOOKBACK_MS)
    val names = listOfNotNull(who, found?.name).distinct()
    names.firstNotNullOfOrNull { name -> ChatHistory.lastMessages(log, name, app, count).ifEmpty { null } }
        ?.let { return@SimpleTool ToolResult.ok(ChatHistory.say(it)) }
    val label = app?.let { APP_NAMES[it] }
    when {
        app == null -> ToolResult.fail("No recent message from $who in your notifications. Say which app: WhatsApp, Messenger or SMS.")
        found == null -> ToolResult.fail("No recent $label message from $who, and I can't find them in your contacts.")
        app == "sms" -> smsFrom(context, found, count)
        else -> AppCommunications(context, contacts).readChat(found, app, count)
    }
}

private fun smsFrom(context: Context, person: Contacts.Contact, count: Int): ToolResult {
    if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
        return ToolResult.fail("I need the SMS permission. Open Jarvis setup.")
    val tail = person.number.filter { it.isDigit() }.takeLast(9)
    if (tail.length < Contacts.MIN_MATCH_DIGITS) return ToolResult.fail("${person.name} has no usable phone number.")
    val texts = ArrayList<String>()
    context.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body"), null, null, "date DESC")?.use { cur ->
        while (cur.moveToNext() && texts.size < count) {
            if (cur.getString(0).orEmpty().filter { it.isDigit() }.endsWith(tail)) cur.getString(1)?.let { texts.add(it.take(300)) }
        }
    }
    if (texts.isEmpty()) return ToolResult.fail("No SMS from ${person.name}.")
    val msgs = texts.reversed().map { ChatHistory.Message(person.name, it, 0, "SMS") }
    return ToolResult.ok(ChatHistory.say(msgs))
}
