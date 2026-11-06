package com.alrasin.jarvis.agent.appmap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.alrasin.jarvis.core.JarvisLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keep app maps fresh (ROADMAP §J.6). Install → add the app; update → "check again" (version saved,
 * lastLearned = 0, every edge gets one doubt so one more failure makes it stale); uninstall → delete its map.
 * [reconcile] catches changes that happened while Jimi was not running.
 */
@Singleton
class AppMapFreshness @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppMapDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                // An update sends REMOVED(replacing) → ADDED(replacing) → REPLACED; only REPLACED matters then.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
                val pending = goAsync()
                scope.launch {
                    try { check(pkg, added = intent.action == Intent.ACTION_PACKAGE_ADDED) } finally { pending.finish() }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(r, filter)
        receiver = r
        scope.launch { reconcile() }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** Compares every saved app with what is installed now. */
    suspend fun reconcile() = runCatching {
        dao.allInfoOnce().forEach { check(it.pkg, added = false) }
    }.onFailure { JarvisLog.e("app-map: reconcile failed: ${it.message}") }

    private suspend fun check(pkg: String, added: Boolean) = runCatching {
        val pm = context.packageManager
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val old = dao.info(pkg)
        if (old == null) {
            if (added && pi != null && pm.getLaunchIntentForPackage(pkg) != null) {
                val label = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
                dao.putInfo(AppInfo(pkg, pi.longVersionCode, label, "unknown", source = "installed", lastLearned = 0))
                JarvisLog.i("app-map: new app $pkg, not learned yet")
            }
            return@runCatching
        }
        when (SelfHeal.freshness(old.versionCode, pi?.longVersionCode)) {
            SelfHeal.Fresh.DELETE -> { dao.forgetApp(pkg); JarvisLog.i("app-map: $pkg removed, map deleted") }
            SelfHeal.Fresh.CHECK_AGAIN -> {
                dao.markCheckAgain(pkg, pi!!.longVersionCode)
                dao.doubtEdges(pkg, SelfHeal.CHECK_AGAIN_FAILS)
                JarvisLog.i("app-map: $pkg updated ${old.versionCode} → ${pi.longVersionCode}, check again")
            }
            SelfHeal.Fresh.SAME -> if (old.versionCode == 0L && pi != null) dao.putInfo(old.copy(versionCode = pi.longVersionCode))
        }
    }.onFailure { JarvisLog.e("app-map: fresh check $pkg failed: ${it.message}") }
}
