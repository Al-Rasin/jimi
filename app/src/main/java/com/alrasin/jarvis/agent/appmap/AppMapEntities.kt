package com.alrasin.jarvis.agent.appmap

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** [source]: card (built-in, ROADMAP J.3) | learned (watched/explored, J.2/J.5). */
@Entity(tableName = "app_info")
data class AppInfo(
    @PrimaryKey val pkg: String,
    val versionCode: Long,
    val label: String,
    val category: String,
    val source: String,
    val lastLearned: Long,
)

/** [fingerprint]: see [AppFingerprint]. Two screens >=80% same parts are treated as one. */
@Entity(tableName = "app_screen", indices = [Index("pkg")])
data class AppScreen(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val fingerprint: String,
    val name: String,
    val lastSeen: Long,
)

/** [selectorsJson]: JSON array of UiQuery.Selector. [role]: tab|drawer|menu|button|field|list|switch.
 *  [risk]: from AgentSafety.riskLevel — low|high|secret. */
@Entity(tableName = "app_element", indices = [Index("screen")])
data class AppElement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val screen: Long,
    val selectorsJson: String,
    val role: String,
    val label: String,
    val risk: String,
)

/** One observed transition: tapping [element] on [fromScreen] led to [toScreen]. */
@Entity(tableName = "app_edge", indices = [Index("fromScreen")])
data class AppEdge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromScreen: Long,
    val element: Long,
    val toScreen: Long,
    val timesSeen: Int,
    val lastOk: Long,
    val failedCount: Int,
)

/** Permission queue (ROADMAP §J.7): a step Jimi did not try alone while learning [pkg].
 *  [status]: pending | approved | denied. Denied rows stay so the same step is never asked again. */
@Entity(tableName = "app_ask", indices = [Index(value = ["pkg", "role", "label"], unique = true)])
data class AppAsk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val label: String,
    val role: String,
    val screen: String,
    val status: String,
    val createdAt: Long,
)

/** Row for [AppMapDao.screenCounts]. */
data class PkgCount(val pkg: String, val n: Int)
