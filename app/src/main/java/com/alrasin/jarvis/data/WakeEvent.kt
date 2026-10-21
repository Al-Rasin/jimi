package com.alrasin.jarvis.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One wake-word event for the feedback loop (D-023).
 * verdict: none (not answered) | yes | no | missed (user pressed "Missed it") | near (score just under threshold).
 */
@Entity(tableName = "wake_event")
data class WakeEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val score: Float,
    val threshold: Float,
    val clipPath: String,
    val verdict: String = VERDICT_NONE,
) {
    companion object {
        const val VERDICT_NONE = "none"
        const val VERDICT_YES = "yes"
        const val VERDICT_NO = "no"
        const val VERDICT_MISSED = "missed"
        const val VERDICT_NEAR = "near"
    }
}

@Dao
interface WakeEventDao {
    @Insert
    suspend fun insert(event: WakeEvent): Long

    @Query("SELECT * FROM wake_event WHERE id = :id")
    suspend fun get(id: Long): WakeEvent?

    @Query("UPDATE wake_event SET verdict = :verdict, clipPath = :clipPath WHERE id = :id")
    suspend fun setVerdict(id: Long, verdict: String, clipPath: String)

    @Query("SELECT * FROM wake_event ORDER BY timeMs DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<WakeEvent>>

    @Query("SELECT verdict, COUNT(*) AS n FROM wake_event GROUP BY verdict")
    fun counts(): Flow<List<VerdictCount>>
}

data class VerdictCount(val verdict: String, val n: Int)
