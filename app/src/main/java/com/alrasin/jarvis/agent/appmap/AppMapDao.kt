package com.alrasin.jarvis.agent.appmap

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putInfo(i: AppInfo)
    @Query("SELECT * FROM app_info WHERE pkg = :pkg") suspend fun info(pkg: String): AppInfo?
    @Query("SELECT * FROM app_info ORDER BY pkg") fun allInfo(): Flow<List<AppInfo>>
    @Query("SELECT * FROM app_info ORDER BY pkg") suspend fun allInfoOnce(): List<AppInfo>

    @Insert suspend fun insertScreen(s: AppScreen): Long
    @Update suspend fun updateScreen(s: AppScreen)
    @Query("SELECT * FROM app_screen WHERE pkg = :pkg") suspend fun screensFor(pkg: String): List<AppScreen>
    @Query("SELECT * FROM app_screen WHERE id = :id") suspend fun screen(id: Long): AppScreen?

    @Insert suspend fun insertElement(e: AppElement): Long
    @Query("SELECT * FROM app_element WHERE screen = :screen") suspend fun elementsFor(screen: Long): List<AppElement>

    @Insert suspend fun insertEdge(e: AppEdge): Long
    @Update suspend fun updateEdge(e: AppEdge)
    @Query("SELECT * FROM app_edge WHERE fromScreen = :fromScreen") suspend fun edgesFrom(fromScreen: Long): List<AppEdge>
    @Query("SELECT * FROM app_edge WHERE fromScreen = :fromScreen AND element = :element LIMIT 1")
    suspend fun edge(fromScreen: Long, element: Long): AppEdge?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAsk(a: AppAsk): Long
    @Query("SELECT * FROM app_ask WHERE status = 'pending' ORDER BY createdAt, id") fun pendingAsks(): Flow<List<AppAsk>>
    @Query("SELECT * FROM app_ask WHERE status = 'pending' ORDER BY createdAt, id") suspend fun pendingAsksOnce(): List<AppAsk>
    @Query("SELECT * FROM app_ask WHERE pkg = :pkg ORDER BY createdAt, id") suspend fun asksFor(pkg: String): List<AppAsk>
    @Query("SELECT * FROM app_ask ORDER BY createdAt, id") fun allAsks(): Flow<List<AppAsk>>
    @Query("UPDATE app_ask SET status = :status WHERE id = :id") suspend fun setAskStatus(id: Long, status: String)
    @Query("SELECT * FROM app_ask WHERE id = :id") suspend fun ask(id: Long): AppAsk?
    @Query("SELECT pkg, COUNT(*) AS n FROM app_screen GROUP BY pkg") fun screenCounts(): Flow<List<PkgCount>>
}
