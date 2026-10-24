package com.alrasin.jarvis.agent.appmap

import android.content.Context
import com.alrasin.jarvis.core.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App map export/import, separate from [com.alrasin.jarvis.data.Backup]: only structure
 * (fingerprints, selectors, risk, roles) — never list rows, chat text, or typed values — so it
 * carries no personal text and can be restored on a fresh install instead of relearning.
 */
@Singleton
class AppMapBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppMapDao,
) {
    suspend fun export(): File {
        val info = dao.allInfoOnce()
        val screens = info.flatMap { dao.screensFor(it.pkg) }
        val elements = screens.flatMap { dao.elementsFor(it.id) }
        val edges = screens.flatMap { dao.edgesFrom(it.id) }
        val data = mapOf(
            "version" to 1,
            "time" to System.currentTimeMillis(),
            "app_info" to info.map {
                mapOf(
                    "pkg" to it.pkg, "versionCode" to it.versionCode, "label" to it.label,
                    "category" to it.category, "source" to it.source, "lastLearned" to it.lastLearned,
                )
            },
            "app_screen" to screens.map {
                mapOf("id" to it.id, "pkg" to it.pkg, "fingerprint" to it.fingerprint, "name" to it.name, "lastSeen" to it.lastSeen)
            },
            "app_element" to elements.map {
                mapOf(
                    "id" to it.id, "screen" to it.screen, "selectors" to Json.parse(it.selectorsJson),
                    "role" to it.role, "label" to it.label, "risk" to it.risk,
                )
            },
            "app_edge" to edges.map {
                mapOf(
                    "id" to it.id, "fromScreen" to it.fromScreen, "element" to it.element, "toScreen" to it.toScreen,
                    "timesSeen" to it.timesSeen, "lastOk" to it.lastOk, "failedCount" to it.failedCount,
                )
            },
        )
        val dir = File(context.getExternalFilesDir(null), "export").apply { mkdirs() }
        val f = File(dir, "jarvis_appmap_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json")
        f.writeText(Json.write(data))
        return f
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun import(file: File) {
        val data = Json.parse(file.readText()) as Map<String, Any?>
        val screenIdMap = HashMap<Long, Long>()

        (data["app_info"] as List<Map<String, Any?>>).forEach {
            dao.putInfo(
                AppInfo(
                    pkg = it["pkg"] as String, versionCode = (it["versionCode"] as Number).toLong(),
                    label = it["label"] as String, category = it["category"] as String,
                    source = it["source"] as String, lastLearned = (it["lastLearned"] as Number).toLong(),
                )
            )
        }
        (data["app_screen"] as List<Map<String, Any?>>).forEach {
            val oldId = (it["id"] as Number).toLong()
            val newId = dao.insertScreen(
                AppScreen(
                    pkg = it["pkg"] as String, fingerprint = it["fingerprint"] as String,
                    name = it["name"] as String, lastSeen = (it["lastSeen"] as Number).toLong(),
                )
            )
            screenIdMap[oldId] = newId
        }
        val elementIdMap = HashMap<Long, Long>()
        (data["app_element"] as List<Map<String, Any?>>).forEach {
            val oldId = (it["id"] as Number).toLong()
            val newId = dao.insertElement(
                AppElement(
                    screen = screenIdMap[(it["screen"] as Number).toLong()] ?: return@forEach,
                    selectorsJson = Json.write(it["selectors"]), role = it["role"] as String,
                    label = it["label"] as String, risk = it["risk"] as String,
                )
            )
            elementIdMap[oldId] = newId
        }
        (data["app_edge"] as List<Map<String, Any?>>).forEach {
            dao.insertEdge(
                AppEdge(
                    fromScreen = screenIdMap[(it["fromScreen"] as Number).toLong()] ?: return@forEach,
                    element = elementIdMap[(it["element"] as Number).toLong()] ?: return@forEach,
                    toScreen = screenIdMap[(it["toScreen"] as Number).toLong()] ?: return@forEach,
                    timesSeen = (it["timesSeen"] as Number).toInt(), lastOk = (it["lastOk"] as Number).toLong(),
                    failedCount = (it["failedCount"] as Number).toInt(),
                )
            )
        }
    }
}
