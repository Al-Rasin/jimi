package com.alrasin.jarvis.agent.appmap

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learn apps by watching the user (ROADMAP §J.2). Jimi never taps here — it only saves the screen
 * the user is on, the structural element they tapped, and the screen that came next (an edge).
 * Structure only (see [ScreenStructure]); skips keyboard, Jimi, lock screen, payment apps.
 */
@Singleton
class AppLearner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppMapDao,
) {
    private val lock = Mutex()
    private val recent = RecentScreens()
    private var lastPkg: String? = null
    private var lastScreen: Long? = null
    private var pendingClick: ScreenStructure.Element? = null

    /** Called after a window change or a click settled. */
    suspend fun onScreen(pkg: String, windowClass: String, nodes: List<ScreenStructure.RawNode>) = lock.withLock {
        runCatching {
            val elements = ScreenStructure.elements(nodes)
            if (elements.isEmpty()) return@withLock
            val fp = AppFingerprint.compute(windowClass, ScreenStructure.parts(elements))
            val now = System.currentTimeMillis()
            val id = recent.idIfFresh(pkg, fp, now) ?: saveScreen(pkg, windowClass, fp, elements, now).also {
                recent.put(pkg, fp, it, now)
            }
            val from = lastScreen
            val click = pendingClick
            if (click != null && from != null && lastPkg == pkg && from != id) saveEdge(from, click, id, now)
            pendingClick = null
            lastPkg = pkg
            lastScreen = id
        }.onFailure { JarvisLog.e("app learn screen failed", it) }
    }

    /** User tapped something. Only structural elements count (list rows return null and are ignored). */
    suspend fun onClick(pkg: String, node: ScreenStructure.RawNode) = lock.withLock {
        pendingClick = if (pkg == lastPkg) ScreenStructure.element(node) else null
    }

    private suspend fun saveScreen(
        pkg: String, windowClass: String, fp: String, elements: List<ScreenStructure.Element>, now: Long,
    ): Long {
        ensureInfo(pkg, now)
        val match = dao.screensFor(pkg).firstOrNull { AppFingerprint.sameScreen(it.fingerprint, fp) }
        val id = if (match != null) {
            dao.updateScreen(match.copy(lastSeen = now))
            match.id
        } else {
            dao.insertScreen(AppScreen(pkg = pkg, fingerprint = fp, name = windowClass.substringAfterLast('.'), lastSeen = now))
        }
        val known = dao.elementsFor(id).map { it.role to it.label }.toSet()
        elements.filter { (it.role to it.label) !in known }.forEach { dao.insertElement(toEntity(id, it)) }
        return id
    }

    private suspend fun saveEdge(from: Long, click: ScreenStructure.Element, to: Long, now: Long) {
        val elementId = dao.elementsFor(from).firstOrNull { it.role == click.role && it.label == click.label }?.id
            ?: dao.insertElement(toEntity(from, click))
        val old = dao.edge(from, elementId)
        when {
            old == null -> dao.insertEdge(AppEdge(fromScreen = from, element = elementId, toScreen = to, timesSeen = 1, lastOk = now, failedCount = 0))
            old.toScreen == to -> dao.updateEdge(old.copy(timesSeen = old.timesSeen + 1, lastOk = now, failedCount = 0))
            // Same tap now leads somewhere else (app changed) — newest wins.
            else -> dao.updateEdge(old.copy(toScreen = to, timesSeen = 1, lastOk = now, failedCount = 0))
        }
    }

    private suspend fun ensureInfo(pkg: String, now: Long) {
        val old = dao.info(pkg)
        if (old != null) {
            if (now - old.lastLearned > INFO_REFRESH_MS) dao.putInfo(old.copy(lastLearned = now))
            return
        }
        val pm = context.packageManager
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
        val ai = pi?.applicationInfo
        dao.putInfo(
            AppInfo(
                pkg = pkg, versionCode = pi?.longVersionCode ?: 0, label = ai?.loadLabel(pm)?.toString() ?: pkg,
                category = ai?.let { categoryName(it.category) } ?: "unknown", source = "learned", lastLearned = now,
            )
        )
    }

    private fun toEntity(screen: Long, e: ScreenStructure.Element) = AppElement(
        screen = screen, selectorsJson = selectorsJson(e), role = e.role, label = e.label, risk = e.risk,
    )

    companion object {
        private const val INFO_REFRESH_MS = 60 * 60 * 1000L

        /** Selectors in the order UiQuery.forLabel tries them: id → desc → text. */
        internal fun selectorsJson(e: ScreenStructure.Element): String = Json.write(
            buildList {
                if (e.resourceId.isNotBlank()) add(mapOf("type" to "id", "value" to e.resourceId))
                add(mapOf("type" to "desc", "value" to e.label))
                add(mapOf("type" to "text", "value" to e.label))
            }
        )

        private fun categoryName(c: Int): String = when (c) {
            ApplicationInfo.CATEGORY_SOCIAL -> "social"
            ApplicationInfo.CATEGORY_AUDIO -> "audio"
            ApplicationInfo.CATEGORY_VIDEO -> "video"
            ApplicationInfo.CATEGORY_IMAGE -> "image"
            ApplicationInfo.CATEGORY_NEWS -> "news"
            ApplicationInfo.CATEGORY_MAPS -> "maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
            ApplicationInfo.CATEGORY_GAME -> "game"
            else -> "unknown"
        }

        /** Never watch: Jimi, keyboard, lock screen / system UI, payment apps. */
        fun shouldWatch(pkg: String, ownPkg: String, keyboardPkg: String?, locked: Boolean): Boolean =
            !locked && pkg.isNotBlank() && pkg != ownPkg && pkg != keyboardPkg &&
                pkg != "com.android.systemui" && !PhoneProfile.isPaymentPackage(pkg)
    }
}

/** Battery rule: same fingerprint seen in the last 10 s → reuse its id, skip DB work. */
internal class RecentScreens(private val windowMs: Long = 10_000L) {
    private val seen = HashMap<String, Pair<Long, Long>>()

    fun idIfFresh(pkg: String, fp: String, now: Long): Long? =
        seen["$pkg|$fp"]?.takeIf { now - it.second < windowMs }?.first

    fun put(pkg: String, fp: String, id: Long, now: Long) {
        if (seen.size > 200) seen.entries.removeAll { now - it.value.second >= windowMs }
        seen["$pkg|$fp"] = id to now
    }
}
