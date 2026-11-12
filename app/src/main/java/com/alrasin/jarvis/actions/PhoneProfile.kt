package com.alrasin.jarvis.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What this phone has (D-049): device, launchable apps grouped by what they can do, contact counts, toggles it supports.
 * Scanned on first start, then once a day or on "scan my phone". Stays on the phone in app storage; contact names are
 * never stored here (ContactResolver reads them live). Only app names may go to the cloud, inside the tool prompt.
 */
class PhoneProfile(private val context: Context, private val apps: AppList) {
    data class Profile(
        val scannedMs: Long,
        val device: String,
        val android: String,
        val apps: List<String>,
        val roles: Map<String, List<String>>,
        val contacts: Int,
        val whatsappContacts: Int,
        val sims: Int,
        val features: List<String>,
    )

    private val file get() = File(context.filesDir, "phone_profile.json")

    @Volatile private var cached: Profile? = null

    private val scanning = Mutex()

    fun current(): Profile? = cached ?: load()?.also { cached = it }

    /** Scans when there is no profile yet or it is older than a day. */
    suspend fun refreshIfOld(): Profile = scanning.withLock {
        current()?.takeIf { System.currentTimeMillis() - it.scannedMs <= DAY_MS } ?: scanNow()
    }

    suspend fun scan(): Profile = scanning.withLock { scanNow() }

    private suspend fun scanNow(): Profile = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val installed = apps.all()
        val pm = context.packageManager
        val p = Profile(
            scannedMs = System.currentTimeMillis(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            android = "Android ${Build.VERSION.RELEASE}" + (miui()?.let { ", MIUI $it" } ?: ""),
            apps = installed.map { it.label }.distinct().sortedBy { it.lowercase() },
            roles = merge(abilities(installed), roles(installed)),
            contacts = count(null),
            whatsappContacts = count(WHATSAPP_ACCOUNT),
            sims = sims(),
            features = FEATURES.filter { (_, f) -> pm.hasSystemFeature(f) }.map { it.first },
        )
        runCatching { file.writeText(toJson(p)) }.onFailure { JarvisLog.e("phone profile not saved", it) }
        cached = p
        JarvisLog.i("phone profile: ${p.apps.size} apps, ${p.contacts} contacts in ${System.currentTimeMillis() - t0} ms")
        p
    }

    /**
     * What each app can do, asked from Android (D-056, ROADMAP §G.1), so it works on any phone:
     * apps that handle a standard intent, plus the app category the developer set.
     */
    private fun abilities(installed: List<AppList.App>): Map<String, List<String>> {
        val pm = context.packageManager
        val labels = installed.associate { it.pkg to it.label }
        fun handlers(i: Intent) = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_ALL) }.getOrDefault(emptyList())
            .mapNotNull { labels[it.activityInfo.packageName] }.distinct()
        val byIntent = ABILITY_INTENTS.mapValues { (_, make) -> handlers(make()) }
        val byCategory = installed.groupBy { a ->
            runCatching { CATEGORY_ROLES[pm.getApplicationInfo(a.pkg, 0).category] }.getOrNull()
        }.filterKeys { it != null }.map { (k, v) -> k!! to v.map { it.label } }.toMap()
        return merge(byIntent, byCategory)
    }

    private fun load(): Profile? = runCatching { if (file.isFile) fromJson(file.readText()) else null }.getOrNull()

    private fun count(accountType: String?): Int {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0
        val uri = if (accountType == null) ContactsContract.Contacts.CONTENT_URI else ContactsContract.RawContacts.CONTENT_URI
        val where = accountType?.let { "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?" }
        return runCatching {
            context.contentResolver.query(uri, arrayOf("_id"), where, accountType?.let { arrayOf(it) }, null)?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

    private fun sims(): Int = runCatching {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return 0
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoCount ?: 0
    }.getOrDefault(0)

    private fun miui(): String? = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", "ro.miui.ui.version.name")).inputStream.bufferedReader().readText().trim()
    }.getOrNull()?.ifEmpty { null }

    companion object {
        private const val DAY_MS = 24 * 3600_000L
        private const val WHATSAPP_ACCOUNT = "com.whatsapp"

        private val FEATURES = listOf(
            "nfc" to PackageManager.FEATURE_NFC, "fingerprint" to PackageManager.FEATURE_FINGERPRINT,
            "flashlight" to PackageManager.FEATURE_CAMERA_FLASH, "bluetooth" to PackageManager.FEATURE_BLUETOOTH,
            "gps" to PackageManager.FEATURE_LOCATION_GPS, "infrared" to PackageManager.FEATURE_CONSUMER_IR,
        )

        /** Role → an intent only apps with that ability handle. */
        private val ABILITY_INTENTS: Map<String, () -> Intent> = mapOf(
            "messaging" to { Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:1")) },
            "calls" to { Intent(Intent.ACTION_DIAL, Uri.parse("tel:1")) },
            "maps" to { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")) },
            "email" to { Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@b.c")) },
            "play from search" to { Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) },
            "browser" to { Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE) },
            "camera" to { Intent(MediaStore.ACTION_IMAGE_CAPTURE) },
            "notes" to { Intent(NoteApp.CREATE_NOTE).setType("text/plain") },
            "calendar" to { Intent(Intent.ACTION_INSERT, Uri.parse("content://com.android.calendar/events")) },
        )

        /** ApplicationInfo.category (set by the app's developer) → role. */
        private val CATEGORY_ROLES = mapOf(
            ApplicationInfo.CATEGORY_AUDIO to "music", ApplicationInfo.CATEGORY_VIDEO to "video",
            ApplicationInfo.CATEGORY_MAPS to "maps", ApplicationInfo.CATEGORY_SOCIAL to "social",
            ApplicationInfo.CATEGORY_NEWS to "news", ApplicationInfo.CATEGORY_IMAGE to "photos",
            ApplicationInfo.CATEGORY_GAME to "games",   // "productivity" left out: on MIUI it holds Play Store, banks, files…
        )

        /** Union per role, order kept, no duplicates. */
        internal fun merge(a: Map<String, List<String>>, b: Map<String, List<String>>): Map<String, List<String>> =
            (a.keys + b.keys).associateWith { k -> (a[k].orEmpty() + b[k].orEmpty()).distinct() }.filterValues { it.isNotEmpty() }

        /** Hand hints by package prefix, added to what Android reports (payments has no standard intent). */
        private val ROLE_PACKAGES = mapOf(
            "messaging" to listOf("com.whatsapp", "com.facebook.orca", "org.telegram", "com.imo.android", "com.viber",
                "com.google.android.apps.messaging", "com.android.mms", "com.discord", "com.instagram.android"),
            "calls" to listOf("com.whatsapp", "com.facebook.orca", "com.imo.android", "com.viber", "com.google.android.dialer",
                "com.android.contacts", "com.android.dialer", "us.zoom", "com.skype"),
            "music" to listOf("com.spotify", "com.google.android.apps.youtube.music", "com.miui.player", "com.soundcloud"),
            "video" to listOf("com.google.android.youtube", "com.netflix", "com.zhiliaoapp.musically", "com.ss.android.ugc",
                "com.facebook.katana", "com.mxtech"),
            "maps" to listOf("com.google.android.apps.maps", "com.ubercab", "com.pathao", "app.organicmaps"),
            "browser" to listOf("com.android.chrome", "org.mozilla", "com.opera", "com.brave", "com.mi.globalbrowser"),
            "email" to listOf("com.google.android.gm", "com.microsoft.office.outlook"),
            "payments" to listOf("com.bKash", "com.konasl.nagad", "com.google.android.apps.nbu.paisa"),
        )

        internal fun roles(installed: List<AppList.App>): Map<String, List<String>> = ROLE_PACKAGES.mapValues { (_, prefixes) ->
            installed.filter { a -> prefixes.any { a.pkg.startsWith(it, ignoreCase = true) } }.map { it.label }.distinct()
        }.filterValues { it.isNotEmpty() }

        /** True for banking/payment apps (ROADMAP: never auto-unlock for these). */
        internal fun isPaymentPackage(pkg: String): Boolean =
            ROLE_PACKAGES["payments"].orEmpty().any { pkg.startsWith(it, ignoreCase = true) }

        /** Short spoken summary. */
        fun say(p: Profile): String = buildString {
            append("This is a ${p.device} on ${p.android}. ")
            append("I know ${p.apps.size} apps and ${p.contacts} contacts")
            if (p.whatsappContacts > 0) append(", ${p.whatsappContacts} of them on WhatsApp")
            append(". ")
            p.roles["messaging"]?.let { append("You message with ${list(it)}. ") }
            p.roles["music"]?.let { append("Music: ${list(it)}.") }
        }.trim()

        /** Non-private part for the cloud tool prompt: app names and what they are for. No contacts. */
        fun cloudLine(p: Profile): String =
            "Installed apps: " + p.apps.take(150).joinToString(", ") +
                p.roles.entries.joinToString("") { (k, v) -> "\n$k apps: ${v.joinToString(", ")}" }

        private fun list(items: List<String>) = if (items.size <= 1) items.joinToString() else
            items.dropLast(1).joinToString(", ") + " and " + items.last()

        internal fun toJson(p: Profile): String = Json.write(
            mapOf(
                "scannedMs" to p.scannedMs, "device" to p.device, "android" to p.android, "apps" to p.apps, "roles" to p.roles,
                "contacts" to p.contacts, "whatsappContacts" to p.whatsappContacts, "sims" to p.sims, "features" to p.features,
            ),
        )

        internal fun fromJson(text: String): Profile? {
            val m = Json.parse(text) as? Map<*, *> ?: return null
            fun strings(v: Any?) = (v as? List<*>).orEmpty().filterIsInstance<String>()
            return Profile(
                scannedMs = (m["scannedMs"] as? Number)?.toLong() ?: return null,
                device = m["device"] as? String ?: "",
                android = m["android"] as? String ?: "",
                apps = strings(m["apps"]),
                roles = m.obj("roles").orEmpty().entries.associate { (k, v) -> k.toString() to strings(v) },
                contacts = (m["contacts"] as? Number)?.toInt() ?: 0,
                whatsappContacts = (m["whatsappContacts"] as? Number)?.toInt() ?: 0,
                sims = (m["sims"] as? Number)?.toInt() ?: 0,
                features = strings(m["features"]),
            )
        }
    }
}

/** "What do you know about my phone" / "scan my phone" / "how many apps do I have" / "what can you do". */
fun phoneProfileTool(profile: PhoneProfile, toolCount: () -> Int) = SimpleTool(
    "phone_profile", "What Jimi knows about this phone (apps, contacts count); scan=true to rescan; ask=apps for the app count, ask=abilities for what Jimi can do",
    listOf(
        arg("scan", "rescan the phone now", required = false, choices = listOf("true", "false")),
        arg("ask", "apps or abilities", required = false, choices = listOf("apps", "abilities")),
    ),
) { c, _ ->
    val p = if (c.arg("scan") == "true") profile.scan() else profile.current() ?: profile.refreshIfOld()
    when (c.arg("ask")) {
        "apps" -> ToolResult.ok("You have ${p.apps.size} apps.")
        "abilities" -> ToolResult.ok(abilitiesLine(toolCount(), p.apps.size))
        else -> ToolResult.ok(PhoneProfile.say(p), PhoneProfile.cloudLine(p))
    }
}

/** Real list, built from the tool count and the phone scan, not a fixed sentence. */
fun abilitiesLine(tools: Int, apps: Int): String =
    "I have $tools tools. I can open and use your $apps apps, call and message, set alarms, timers and reminders, " +
        "take notes, change settings, play music, read your screen, notifications and messages, and search the web."
