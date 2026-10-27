package com.alrasin.jarvis.agent.appmap

import android.content.Context
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
 * Built-in app cards (ROADMAP §J.3). Reads `assets/apps/<package>.json`, keeps installed apps' cards in memory
 * for chat answers and tasks, and puts their screens/elements/edges into the app map as the starting map.
 * Learning (§J.2, §J.5) adds to it later. A card is loaded into the DB again only when its cardVersion grows.
 */
@Singleton
class AppCards @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppMapDao,
) {
    private val lock = Mutex()
    private val prefs by lazy { context.getSharedPreferences("app_cards", Context.MODE_PRIVATE) }
    @Volatile private var byPkg: Map<String, AppCard> = emptyMap()

    /** Card for an installed app (any of its packages), or null. Call [loadInstalled] first. */
    fun card(pkg: String): AppCard? = byPkg[pkg]

    /** Card whose name or alias matches what the user said ("whatsapp", "yt"). */
    fun byName(name: String): AppCard? {
        val n = name.trim().lowercase()
        return byPkg.values.firstOrNull { c -> c.name.lowercase() == n || c.aliases.any { it.lowercase() == n } }
    }

    /** Every loaded card once (cards of installed apps only). */
    fun all(): List<AppCard> = byPkg.values.distinct()

    suspend fun loadInstalled() = lock.withLock {
        val cards = readAll()
        val pm = context.packageManager
        val map = HashMap<String, AppCard>()
        // D-072: Jimi does nothing with money apps, so their cards are not loaded.
        cards.filterNot { c -> c.packages.any(PhoneProfile::isPaymentPackage) }.forEach { card ->
            val installed = card.packages.firstOrNull { installed(pm, it) } ?: return@forEach
            card.packages.forEach { map[it] = card }
            runCatching { store(card, installed, pm) }.onFailure { JarvisLog.e("app card ${card.name} failed", it) }
        }
        byPkg = map
        JarvisLog.i("app cards: ${map.values.distinct().size} of ${cards.size} installed")
    }

    private fun readAll(): List<AppCard> {
        val assets = context.assets
        val files = runCatching { assets.list(DIR) }.getOrNull().orEmpty().filter { it.endsWith(".json") }
        return files.mapNotNull { f ->
            runCatching { AppCard.parse(assets.open("$DIR/$f").bufferedReader().use { it.readText() }) }
                .onFailure { JarvisLog.e("bad app card $f", it) }.getOrNull()
        }
    }

    private fun installed(pm: PackageManager, pkg: String) = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

    private suspend fun store(card: AppCard, pkg: String, pm: PackageManager) {
        val key = "v:$pkg"
        if (prefs.getInt(key, 0) >= card.cardVersion) return
        val now = System.currentTimeMillis()
        if (dao.info(pkg) == null) {
            val pi = pm.getPackageInfo(pkg, 0)
            dao.putInfo(AppInfo(pkg, pi.longVersionCode, card.name, "unknown", source = "card", lastLearned = now))
        }
        val ids = card.screens.associate { it.name to saveScreen(card, pkg, it, now) }
        card.screens.forEach { s ->
            val from = ids.getValue(s.name)
            val known = dao.elementsFor(from)
            s.elements.filter { it.to.isNotBlank() }.forEach { e ->
                val to = ids[e.to] ?: return@forEach
                val elementId = known.firstOrNull { it.role == e.role && it.label == ScreenStructure.clean(e.label) }?.id ?: return@forEach
                if (dao.edge(from, elementId) == null) {
                    dao.insertEdge(AppEdge(fromScreen = from, element = elementId, toScreen = to, timesSeen = 0, lastOk = 0, failedCount = 0))
                }
            }
        }
        prefs.edit().putInt(key, card.cardVersion).apply()
    }

    /** Same merge rule as the learner: a ≥80% matching screen is reused, missing elements added. */
    private suspend fun saveScreen(card: AppCard, pkg: String, s: AppCard.Screen, now: Long): Long {
        val fp = AppCard.fingerprint(card, s)
        val match = dao.screensFor(pkg).firstOrNull { AppFingerprint.sameScreen(it.fingerprint, fp) }
        val id = match?.id ?: dao.insertScreen(AppScreen(pkg = pkg, fingerprint = fp, name = s.name, lastSeen = now))
        val known = dao.elementsFor(id).map { it.role to it.label }.toSet()
        s.elements.forEach { e ->
            val label = ScreenStructure.clean(e.label)
            if (label.isNotEmpty() && (e.role to label) !in known) {
                dao.insertElement(AppElement(screen = id, selectorsJson = Json.write(e.selectors), role = e.role, label = label, risk = e.risk))
            }
        }
        return id
    }

    private companion object {
        const val DIR = "apps"
    }
}
