package com.alrasin.jarvis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TurnDao {
    @Insert suspend fun insert(t: ConversationTurn): Long
    @Update suspend fun update(t: ConversationTurn)
    @Query("SELECT * FROM conversation_turn WHERE id = :id") suspend fun get(id: Long): ConversationTurn?
    @Query("SELECT * FROM conversation_turn ORDER BY timeMs DESC LIMIT :limit") fun recent(limit: Int = 200): Flow<List<ConversationTurn>>
    @Query("SELECT * FROM conversation_turn ORDER BY timeMs DESC LIMIT :limit") suspend fun last(limit: Int): List<ConversationTurn>
    @Query("UPDATE conversation_turn SET textOk = :ok, correction = :correction WHERE id = :id")
    suspend fun rate(id: Long, ok: Boolean?, correction: String?)
    @Query("SELECT * FROM conversation_turn ORDER BY timeMs") suspend fun all(): List<ConversationTurn>
}

@Dao
interface NoteDao {
    @Insert suspend fun insert(n: Note): Long
    @Query("DELETE FROM note WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM note ORDER BY timeMs DESC") fun all(): Flow<List<Note>>
    @Query("SELECT * FROM note ORDER BY timeMs DESC, id DESC") suspend fun snapshot(): List<Note>
    @Query("SELECT * FROM note ORDER BY timeMs DESC LIMIT :limit") suspend fun last(limit: Int): List<Note>
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: Reminder): Long
    @Update suspend fun update(r: Reminder)
    @Query("SELECT * FROM reminder WHERE id = :id") suspend fun get(id: Long): Reminder?
    @Query("DELETE FROM reminder WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM reminder WHERE done = 0 ORDER BY dueMs") suspend fun pending(): List<Reminder>
    @Query("SELECT * FROM reminder ORDER BY done, dueMs") fun all(): Flow<List<Reminder>>
}

@Dao
interface NotificationDao {
    @Insert suspend fun insert(n: NotificationLog): Long
    @Query("UPDATE notification_log SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: String)
    @Query("UPDATE notification_log SET label = :label WHERE id = :id") suspend fun setLabel(id: Long, label: String)
    @Query("SELECT * FROM notification_log ORDER BY timeMs DESC LIMIT :limit") fun recent(limit: Int = 300): Flow<List<NotificationLog>>
    @Query("SELECT * FROM notification_log WHERE timeMs >= :since ORDER BY timeMs DESC") suspend fun since(since: Long): List<NotificationLog>
    @Query("SELECT * FROM notification_log ORDER BY timeMs") suspend fun all(): List<NotificationLog>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setRule(r: AppRule)
    @Query("DELETE FROM app_rule WHERE pkg = :pkg") suspend fun clearRule(pkg: String)
    @Query("SELECT * FROM app_rule") suspend fun rules(): List<AppRule>
    @Query("SELECT * FROM app_rule") fun rulesFlow(): Flow<List<AppRule>>
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(f: MemoryFact): Long
    @Query("DELETE FROM memory_fact WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM memory_fact ORDER BY timeMs DESC") suspend fun all(): List<MemoryFact>
    @Query("SELECT * FROM memory_fact ORDER BY timeMs DESC") fun flow(): Flow<List<MemoryFact>>
}

@Dao
interface ToolLogDao {
    @Insert suspend fun insert(l: ToolCallLog): Long
    @Query("SELECT * FROM tool_call_log ORDER BY timeMs") suspend fun all(): List<ToolCallLog>
}

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(r: Routine)
    @Query("SELECT * FROM routine WHERE name = :name") suspend fun get(name: String): Routine?
    @Query("SELECT * FROM routine ORDER BY name") fun all(): Flow<List<Routine>>
    @Query("DELETE FROM routine WHERE name = :name") suspend fun delete(name: String)
}

@Dao
interface RecipeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(r: RecipeRow): Long
    @Query("SELECT * FROM recipes ORDER BY timeMs DESC") suspend fun all(): List<RecipeRow>
    @Query("DELETE FROM recipes WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM recipes WHERE pkg = :pkg AND say = :say") suspend fun deleteSame(pkg: String, say: String)
}
