package com.alrasin.jimi.notify

import com.alrasin.jimi.core.ContextNow
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.alrasin.jimi.actions.CallText
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.data.NotificationDao
import com.alrasin.jimi.data.NotificationLog
import com.alrasin.jimi.data.Settings
import com.alrasin.jimi.service.CoreState
import com.alrasin.jimi.service.JimiCoreService
import com.alrasin.jimi.service.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Reads every notification, logs it, and clears junk when auto-clear is on (PLAN 7). */
@AndroidEntryPoint
class JimiNotificationListener : NotificationListenerService() {
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
        JimiLog.i("notification listener connected")
        runCatching { activeNotifications.orEmpty().forEach(::hideServiceNotification) }
    }

    /**
     * Android needs the "Jimi voice service" notification for background mic use, but the user does not
     * want it in the shade (D-089). Snoozing keeps the service in the foreground; updates stay snoozed.
     */
    private fun hideServiceNotification(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName || sbn.id != Notifications.ID_SERVICE) return
        runCatching { snoozeNotification(sbn.key, SERVICE_SNOOZE_MS) }
            .onFailure { JimiLog.e("could not hide service notification", it) }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName) return hideServiceNotification(sbn)
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
                    readOut(sbn, row, rule)
                    val clearable = sbn.isClearable && n.flags and Notification.FLAG_GROUP_SUMMARY == 0
                    if (clearable && !row.important && settings.autoClearNotifications.first()) {
                        val result = history.clear(listOf(row), undoable = false) { cancelUnchanged(sbn, row) }
                        if (result.failed > 0 || result.errors.isNotEmpty()) JimiLog.e("Notification clear incomplete; history retained")
                    } else if (firstPost(sbn.key, row)) {
                        dao.insert(row)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                JimiLog.e("Notification history unavailable; notification left visible")
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
            if (settings.listeningEnabled.first()) JimiCoreService.say(this@JimiNotificationListener, CallText.ringing(who, app))
        }
    }

    private val readQueue = ReadQueue()
    @Volatile private var reader: Job? = null

    /** Read-out (D-080, ROADMAP B §I.11 / #44): [ReadOut] picks what to say; lines wait in [readQueue]. */
    private suspend fun readOut(sbn: StatusBarNotification, row: NotificationLog, rule: String?) {
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_CALL && sbn.packageName in CallText.APP_CALLS) return // announceAppCall said it
        val chat = n.category == Notification.CATEGORY_MESSAGE || n.extras.containsKey(Notification.EXTRA_MESSAGES)
        val prefs = ReadOut.Prefs(settings.readWhat.first(), settings.readWhen.first(), settings.readLockedFull.first())
        val kind = ReadOut.kind(row, n.category, chat)
        val d = ReadOut.decide(row, kind, ReadOut.AppMode.of(rule), prefs, phoneNow())
        JimiLog.i("read-out ${row.appName}: ${if (d.line == null) "skip, ${d.why}" else d.why}")
        val line = d.line ?: return
        if (!settings.listeningEnabled.first() || !readQueue.add(line, System.currentTimeMillis())) return
        if (reader?.isActive != true) reader = scope.launch { drainReadQueue() }
    }

    private suspend fun drainReadQueue() {
        while (!readQueue.isEmpty()) {
            // Wait for Jimi to finish a reply; give up after a minute rather than talk over it.
            withTimeoutOrNull(BUSY_WAIT_MS) { while (jimiBusy()) delay(500) } ?: return
            val line = readQueue.next() ?: return
            JimiCoreService.say(this, line)
            delay(READ_GAP_MS)
        }
    }

    private fun jimiBusy() = CoreState.phase.value.let { it != CoreState.PHASE_WAKE && it != CoreState.PHASE_MIC_BUSY }

    private fun phoneNow(): ReadOut.Phone {
        val mode = ContextNow.mode
        val audio = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        return ReadOut.Phone(
            car = mode.car, headphones = mode.headphones, bedtime = mode.quiet,
            locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true,
            inCall = audio?.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE),
            doNotDisturb = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) > NotificationManager.INTERRUPTION_FILTER_ALL,
            ringerSilent = audio?.ringerMode?.let { it != AudioManager.RINGER_MODE_NORMAL } ?: false,
            jimiBusy = false, // the queue waits for Jimi instead of dropping the line
        )
    }

    /** Apps re-post the same notification (progress, re-sort); log each key + content only once. Runs under [operations]. */
    private val lastLogged = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_LOGGED_KEYS
    }

    private fun firstPost(key: String, row: NotificationLog): Boolean {
        val sig = "${row.title}\u0000${row.text}".hashCode()
        return lastLogged.put(key, sig) != sig
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
        @Volatile var instance: JimiNotificationListener? = null
            private set
        private const val MAX_LOGGED_KEYS = 200
        private const val SERVICE_SNOOZE_MS = 365L * 24 * 60 * 60_000   // re-snoozed on every reconnect
        private const val READ_GAP_MS = 6_000L
        private const val BUSY_WAIT_MS = 60_000L
        const val COMPONENT = "com.alrasin.jimi/com.alrasin.jimi.notify.JimiNotificationListener"
    }
}
