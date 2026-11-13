package com.alrasin.jarvis.notify

import com.alrasin.jarvis.brain.ContextModes
import com.alrasin.jarvis.core.ContextNow
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.alrasin.jarvis.actions.CallText
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.NotificationLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.service.JarvisCoreService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Reads every notification, logs it, and clears junk when auto-clear is on (PLAN 7). */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {
    @Inject lateinit var dao: NotificationDao
    @Inject lateinit var settings: Settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val model by lazy {
        scope.async { NotificationModel.load(File(filesDir, "models/notifications/model.json")) }
    }
    private val operations = Mutex()
    private val history by lazy { NotificationClearHistory(dao, NotificationClearJournal(this)) }

    override fun onListenerConnected() {
        instance = this
        JarvisLog.i("notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        announceAppCall(sbn)
        scope.launch {
            try {
                operations.withLock {
                    val rule = dao.rules().firstOrNull { it.pkg == sbn.packageName }?.mode
                    val row = snapshot(sbn, rule).let { r ->
                        if (r.important) return@let r
                        val learned = model.await().classifyConservatively(
                            NotificationModel.Input(sbn.packageName, r.title, r.text.take(500), sbn.postTime), n.category, rule, r.text,
                        )
                        if (learned.important) r.copy(important = true, reason = learned.reason) else r
                    }
                    readAloud(sbn, row)
                    readImportant(sbn, row)
                    val clearable = sbn.isClearable && n.flags and Notification.FLAG_GROUP_SUMMARY == 0
                    if (clearable && !row.important && settings.autoClearNotifications.first()) {
                        val result = history.clear(listOf(row)) { cancelUnchanged(sbn, row) }
                        if (result.failed > 0 || result.errors.isNotEmpty()) JarvisLog.e("Notification clear incomplete; history retained")
                    } else {
                        dao.insert(row)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                JarvisLog.e("Notification history unavailable; notification left visible")
            }
        }
    }

    /** WhatsApp / Messenger ringing call (ROADMAP B §I.1): say who calls once per call notification. */
    private fun announceAppCall(sbn: StatusBarNotification) {
        val n = sbn.notification
        val app = CallText.APP_CALLS[sbn.packageName] ?: return
        if (n.category != Notification.CATEGORY_CALL || !CallText.isIncoming(n.actions.orEmpty().map { it.title?.toString() })) return
        if (sbn.key == lastCallKey) return
        lastCallKey = sbn.key
        val who = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        scope.launch {
            if (settings.listeningEnabled.first()) JarvisCoreService.say(this@JarvisNotificationListener, CallText.ringing(who, app))
        }
    }

    /** Car / headphones mode (ROADMAP B §I.11): say new chat messages out loud, once each. */
    private suspend fun readAloud(sbn: StatusBarNotification, row: NotificationLog) {
        val n = sbn.notification
        if (!ContextNow.mode.readMessages || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val chat = n.category == Notification.CATEGORY_MESSAGE || n.extras.containsKey(Notification.EXTRA_MESSAGES)
        if (!chat) return
        val line = ContextModes.messageLine(row.appName, row.title, row.text) ?: return
        if (line == lastReadLine || !settings.readMessages.first() || !settings.listeningEnabled.first()) return
        lastReadLine = line
        JarvisCoreService.say(this, line)
    }

    @Volatile private var lastReadLine: String? = null
    @Volatile private var lastImportantAt = 0L

    /** "Read important alerts" setting (ROADMAP #44): say important alerts out loud, at most one per 2 minutes. */
    private suspend fun readImportant(sbn: StatusBarNotification, row: NotificationLog) {
        if (sbn.isOngoing || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!settings.readImportant.first() || !settings.listeningEnabled.first()) return
        val mode = ContextNow.mode
        val now = System.currentTimeMillis()
        // Setting off = no chat is ever read aloud; mode on = readAloud already read it.
        val skipChats = mode.readMessages || !settings.readMessages.first()
        val n = sbn.notification
        val chat = n.category == Notification.CATEGORY_MESSAGE || n.extras.containsKey(Notification.EXTRA_MESSAGES)
        val line = Proactive.importantLine(row, mode.quiet, skipChats, now, lastImportantAt, chat) ?: return
        lastImportantAt = now
        JarvisCoreService.say(this, line)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == lastCallKey) lastCallKey = null
    }

    @Volatile private var lastCallKey: String? = null

    private fun snapshot(sbn: StatusBarNotification, rule: String? = null): NotificationLog {
        val n = sbn.notification
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val parts = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"),
            e.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { messages ->
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).joinToString("\n") {
                    "${it.senderPerson?.name ?: "Message"}: ${it.text}"
                }
            },
        ).filter { it.isNotBlank() }.distinct()
        val text = parts.joinToString("\n")
        val v = Triage.classify(
            sbn.packageName, title, text, n.category, rule,
            hasPeople = e.containsKey(Notification.EXTRA_PEOPLE_LIST) || e.containsKey(Notification.EXTRA_PEOPLE),
            isMessagingStyle = e.containsKey(Notification.EXTRA_MESSAGES) || e.containsKey(Notification.EXTRA_HISTORIC_MESSAGES),
            isConversation = n.shortcutId != null || n.bubbleMetadata != null,
        )
        return NotificationLog(
            timeMs = sbn.postTime, pkg = sbn.packageName, appName = appName(sbn.packageName),
            title = title, text = text, important = v.important, reason = v.reason, status = "kept",
        )
    }

    private fun cancelUnchanged(original: StatusBarNotification, saved: NotificationLog) {
        val active = activeNotifications?.firstOrNull { it.key == original.key }
            ?: error("Notification is no longer active")
        val latest = snapshot(active)
        check(active.postTime == original.postTime && latest.title == saved.title && latest.text == saved.text &&
            active.notification.category == original.notification.category && latest.important == saved.important) {
            "Notification changed before it could be cleared"
        }
        check(active.isClearable) { "Notification is no longer clearable" }
        cancelNotification(original.key)
    }

    fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(pkg)

    /** Active notifications from other apps, important first. */
    fun current(): List<Item> = activeNotifications.orEmpty()
        .filter { it.packageName != packageName && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        .map {
            val row = snapshot(it)
            Item(row.appName, row.title, row.text, row.important)
        }
        .filter { it.title.isNotBlank() || it.text.isNotBlank() }
        .sortedByDescending { it.important }

    suspend fun clearAll(): ClearResult = operations.withLock {
        val items = activeNotifications.orEmpty().filter {
            it.packageName != packageName && it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        val rows = items.map { snapshot(it).copy(reason = "you asked to clear notifications") }
        history.clear(rows) { index -> cancelUnchanged(items[index], rows[index]) }
    }

    suspend fun undoLastClear(): ClearResult = operations.withLock {
        history.undo { row ->
            val manager = getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled()) { "Jimi notification permission is off" }
            val channel = NotificationChannel("restored_notifications", "Restored notifications", NotificationManager.IMPORTANCE_LOW)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            manager.createNotificationChannel(channel)
            check(manager.getNotificationChannel(channel.id).importance != NotificationManager.IMPORTANCE_NONE) {
                "Restored notification channel is disabled"
            }
            val notification = Notification.Builder(this, channel.id)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Restored from ${row.appName}: ${row.title}")
                .setContentText(row.text)
                .setStyle(Notification.BigTextStyle().bigText(row.text))
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOnlyAlertOnce(true)
                .setSubText("Jimi history copy")
                .build()
            manager.notify("restored:${row.id}", 0, notification)
        }
    }

    suspend fun lastCleared(): List<NotificationLog> = history.lastCleared()

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    data class Item(val app: String, val title: String, val text: String, val important: Boolean)

    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
            private set
        const val COMPONENT = "com.alrasin.jarvis/com.alrasin.jarvis.notify.JarvisNotificationListener"
    }
}
