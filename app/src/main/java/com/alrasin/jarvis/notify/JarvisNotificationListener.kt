package com.alrasin.jarvis.notify

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.NotificationLog
import com.alrasin.jarvis.data.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Reads every notification, logs it, and clears junk when auto-clear is on (PLAN 7). */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {
    @Inject lateinit var dao: NotificationDao
    @Inject lateinit var settings: Settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        instance = this
        JarvisLog.i("notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        scope.launch {
            val rule = dao.rules().firstOrNull { it.pkg == sbn.packageName }?.mode
            val v = Triage.classify(sbn.packageName, title, text, n.category, rule)
            val clear = !v.important && settings.autoClearNotifications.first()
            if (clear) cancelNotification(sbn.key)
            dao.insert(
                NotificationLog(
                    timeMs = sbn.postTime, pkg = sbn.packageName, appName = appName(sbn.packageName), title = title, text = text.take(500),
                    important = v.important, reason = v.reason, status = if (clear) "cleared" else "kept",
                ),
            )
        }
    }

    fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(pkg)

    /** Active notifications from other apps, important first. */
    fun current(): List<Item> = activeNotifications.orEmpty()
        .filter { it.packageName != packageName && !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        .map {
            val e = it.notification.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            Item(appName(it.packageName), title, text, Triage.classify(it.packageName, title, text, it.notification.category, null).important)
        }
        .filter { it.title.isNotBlank() || it.text.isNotBlank() }
        .sortedByDescending { it.important }

    fun clearAll() = cancelAllNotifications()

    override fun onDestroy() {
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
