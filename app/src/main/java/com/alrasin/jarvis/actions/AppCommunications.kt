package com.alrasin.jarvis.actions

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.delay
import java.util.Locale

internal class AppCommunications(private val context: Context, private val contacts: Contacts) {
    private fun packageFor(app: String): String? = when (app) {
        "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
        "messenger" -> listOf("com.facebook.orca")
        else -> emptyList()
    }.firstOrNull(context::isInstalled)

    private fun international(number: String): String? {
        CommsAddress.international(number)?.let { return it }
        val country = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.isNotBlank() }
                ?: context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: Locale.getDefault().country
        return PhoneNumberUtils.formatNumberToE164(number, country.uppercase(Locale.ROOT))?.let(CommsAddress::international)
    }

    private fun matchingContactIds(person: Contacts.Contact): Set<Long> = person.contactId?.let { setOf(it) }
        ?: contacts.all().filter {
            ContactResolver.number(it.number) == ContactResolver.number(person.number) ||
                international(person.number)?.let { number -> number == international(it.number) } == true
        }.mapNotNull { it.contactId }.toSet()

    private fun openContactRow(person: Contacts.Contact, pkg: String, mime: String? = null): Boolean = runCatching {
        val ids = matchingContactIds(person)
        if (ids.isEmpty()) return@runCatching false
        val matches = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} IN (${ids.joinToString(",") { "?" }})",
            ids.map(Long::toString).toTypedArray(), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(2).orEmpty()
                if (mime != null) {
                    if (type != mime) continue
                    val rowNumber = cursor.getString(3).orEmpty().substringBefore('@')
                    val exact = ContactResolver.number(rowNumber) == ContactResolver.number(person.number)
                    val e164 = international(person.number)
                    if (!exact && (e164 == null || (e164 != rowNumber && e164 != international(rowNumber)))) continue
                } else if (!CommsAddress.isMessengerConversation(type)) continue
                matches.add(cursor.getLong(0) to type)
            }
        }
        if (matches.size != 1) return@runCatching false
        val row = matches.single()
        context.launch(Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row.first), row.second).setPackage(pkg))
    }.getOrDefault(false)

    suspend fun call(person: Contacts.Contact, app: String, video: Boolean): ToolResult {
        val label = if (app == "whatsapp") "WhatsApp" else "Messenger"
        val pkg = packageFor(app) ?: return ToolResult.fail("$label is not installed.")
        if (app == "whatsapp") {
            val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call" else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            if (openContactRow(person, pkg, mime)) return ToolResult.ok("Boss, I requested a $label ${if (video) "video" else "voice"} call to ${person.name}.")
        }
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.fail("Enable Jimi accessibility in Android settings so I can place the $label call.")
        openConversation(person, app, pkg, service)?.let { return ToolResult.fail(it) }
        val labels = if (video) listOf("Video call", "Start video call") else listOf("Voice call", "Audio call", "Start audio call", "Call")
        val ids = if (app == "whatsapp") listOf("$pkg:id/${if (video) "video_call" else "menuitem_conversation_call"}") else emptyList()
        if (!conversationMatches(service, pkg, person)) return ToolResult.fail("The $label conversation changed. No call was placed.")
        if (!service.clickExact(pkg, labels = labels, ids = ids)) return ToolResult.fail("I opened ${person.name}'s $label chat, but couldn't identify the ${if (video) "video" else "voice"} call button.")
        return ToolResult.ok("Boss, I requested a $label ${if (video) "video" else "voice"} call to ${person.name}.")
    }

    suspend fun message(person: Contacts.Contact, app: String, text: String): ToolResult {
        val label = if (app == "whatsapp") "WhatsApp" else "Messenger"
        val pkg = packageFor(app) ?: return ToolResult.fail("$label is not installed.")
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.fail("Enable Jimi accessibility in Android settings so I can send the $label message.")
        openConversation(person, app, pkg, service, text)?.let { return ToolResult.fail(it) }
        if (!service.replaceText(pkg, text) || service.editableText(pkg) != text)
            return ToolResult.fail("I couldn't enter the exact message in ${person.name}'s $label chat. Nothing was sent.")
        val ids = if (app == "whatsapp") listOf("$pkg:id/send") else emptyList()
        if (!conversationMatches(service, pkg, person)) return ToolResult.fail("The $label conversation changed. Nothing was sent.")
        if (!waitFor { service.clickExact(pkg, labels = listOf("Send", "Send message"), ids = ids) })
            return ToolResult.fail("The message is ready in $label, but I couldn't identify Send. Nothing was sent.")
        return ToolResult.ok("Boss, I tapped send for ${person.name} in $label. Delivery isn't confirmed.")
    }

    /** "Message yourself" on WhatsApp, or the owner's own Messenger thread. */
    suspend fun messageSelf(app: String, text: String): ToolResult {
        val label = if (app == "whatsapp") "WhatsApp" else "Messenger"
        val pkg = packageFor(app) ?: return ToolResult.fail("$label is not installed.")
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult.fail("Enable Jimi accessibility in Android settings so I can send the $label message.")
        val uri = if (app == "whatsapp") {
            val number = SelfAccount.whatsappNumber()?.let(CommsAddress::international)
                ?: return ToolResult.fail("I couldn't find your own WhatsApp number.")
            Uri.parse("https://wa.me/$number").buildUpon().appendQueryParameter("text", text).build()
        } else {
            Uri.parse("fb-messenger://user/" + (SelfAccount.messengerId() ?: return ToolResult.fail("I couldn't find your Messenger account.")))
        }
        if (!context.launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return ToolResult.fail("I couldn't open $label.")
        // Messenger has no "you" mark in the thread; the deep link to the owner's own id is the check there.
        val opened = waitFor { service.editableText(pkg) != null && (app != "whatsapp" || service.isSelfChat(pkg)) }
        if (!opened) return ToolResult.fail("I couldn't open your own $label chat. Nothing was sent. Unlock the phone and try again.")
        service.focusEditable(pkg)
        delay(300)
        if (!service.replaceText(pkg, text) || !waitFor { service.editableText(pkg) == text })
            return ToolResult.fail("I couldn't enter the exact message in your $label chat. Nothing was sent.")
        if (app == "whatsapp" && !service.isSelfChat(pkg)) return ToolResult.fail("The WhatsApp chat changed. Nothing was sent.")
        val ids = if (app == "whatsapp") listOf("$pkg:id/send") else emptyList()
        // Messenger swaps the like button for Send only after the text is in.
        if (!waitFor { service.clickExact(pkg, labels = listOf("Send", "Send message"), ids = ids) })
            return ToolResult.fail("The message is ready in $label, but I couldn't identify Send. Nothing was sent.")
        return ToolResult.ok("Boss, I sent it to your own $label chat.")
    }

    private suspend fun openConversation(person: Contacts.Contact, app: String, pkg: String, service: JarvisAccessibilityService, text: String = ""): String? {
        val label = if (app == "whatsapp") "WhatsApp" else "Messenger"
        if (app == "whatsapp") {
            val number = international(person.number)
                ?: return "Save ${person.name}'s number with its country code before using WhatsApp."
            val uri = Uri.parse("https://wa.me/$number").buildUpon().appendQueryParameter("text", text).build()
            if (!context.launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return "I couldn't open WhatsApp."
        } else if (!openContactRow(person, pkg)) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "I couldn't open Messenger."
            if (!context.launch(launch) || !waitFor { service.appPackage() == pkg }) return "I couldn't open Messenger. Unlock the phone and try again."
            if (!waitFor { service.clickExact(pkg, labels = listOf("Search", "Search Messenger")) }) return "I couldn't find Messenger search. Open Messenger's Chats tab and try again."
            if (!waitFor { service.replaceText(pkg, person.name) }) return "I couldn't type in Messenger search."
            delay(750)
            if (!waitFor { service.clickUniqueText(pkg, person.name) }) return "I couldn't find one unique Messenger result for ${person.name}. Choose their conversation in Messenger."
        }
        val verified = waitFor {
            conversationMatches(service, pkg, person) && service.editableText(pkg) != null
        }
        return if (verified) null else "I couldn't verify ${person.name}'s $label conversation. Nothing was sent or called. Unlock the phone and check $label."
    }

    private fun conversationMatches(service: JarvisAccessibilityService, pkg: String, person: Contacts.Contact): Boolean =
        service.appPackage() == pkg && (
            service.hasConversationTitle(pkg, person.name) || service.hasConversationTitle(pkg, person.number) ||
                international(person.number)?.let { service.hasConversationTitle(pkg, "+$it") } == true
            )

    private suspend fun waitFor(check: () -> Boolean): Boolean {
        repeat(20) {
            if (check()) return true
            delay(250)
        }
        return false
    }
}

internal object CommsAddress {
    fun international(value: String): String? {
        val compact = value.trim().replace(Regex("[\\s().-]"), "")
        val digits = when {
            compact.startsWith("+") -> compact.drop(1)
            compact.startsWith("00") -> compact.drop(2)
            else -> return null
        }
        return digits.takeIf { it.matches(Regex("[1-9][0-9]{6,14}")) }
    }

    fun isMessengerConversation(mime: String): Boolean = mime.startsWith("vnd.android.cursor.item/") &&
        (mime.contains("com.facebook.messenger") || mime.contains("com.facebook.orca")) &&
        (mime.endsWith(".chat") || mime.endsWith(".message"))
}
