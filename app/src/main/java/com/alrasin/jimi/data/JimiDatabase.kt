package com.alrasin.jimi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alrasin.jimi.agent.appmap.AppAsk
import com.alrasin.jimi.agent.appmap.AppEdge
import com.alrasin.jimi.agent.appmap.AppElement
import com.alrasin.jimi.agent.appmap.AppInfo
import com.alrasin.jimi.agent.appmap.AppMapDao
import com.alrasin.jimi.agent.appmap.AppScreen
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        WakeEvent::class, ConversationTurn::class, Note::class, Reminder::class, NotificationLog::class,
        AppRule::class, MemoryFact::class, ToolCallLog::class, Routine::class,
        AppInfo::class, AppScreen::class, AppElement::class, AppEdge::class, RecipeRow::class, AppAsk::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class JimiDatabase : RoomDatabase() {
    abstract fun wakeEventDao(): WakeEventDao
    abstract fun turnDao(): TurnDao
    abstract fun noteDao(): NoteDao
    abstract fun reminderDao(): ReminderDao
    abstract fun notificationDao(): NotificationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun toolLogDao(): ToolLogDao
    abstract fun routineDao(): RoutineDao
    abstract fun appMapDao(): AppMapDao
    abstract fun recipeDao(): RecipeDao

    companion object {
        /** v1 had only wake_event (kept as is). SQL copied from app/schemas/…/2.json. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_SQL_1_2.forEach(db::execSQL)
            }
        }

        /** v3 adds the app map tables (ROADMAP J.1). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_SQL_2_3.forEach(db::execSQL)
            }
        }

        /** v4 adds saved recipes (ROADMAP §H.5). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_SQL_3_4.forEach(db::execSQL)
            }
        }

        /** v5 declares the app map indexes (they made migrated DBs fail Room's check). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_SQL_4_5.forEach(db::execSQL)
            }
        }

        /** v6 adds the permission queue (ROADMAP §J.7). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_SQL_5_6.forEach(db::execSQL)
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): JimiDatabase =
        Room.databaseBuilder(context, JimiDatabase::class.java, "jimi.db")
            .addMigrations(JimiDatabase.MIGRATION_1_2, JimiDatabase.MIGRATION_2_3, JimiDatabase.MIGRATION_3_4, JimiDatabase.MIGRATION_4_5, JimiDatabase.MIGRATION_5_6)
            .build()

    @Provides fun wakeEventDao(db: JimiDatabase): WakeEventDao = db.wakeEventDao()
    @Provides fun turnDao(db: JimiDatabase): TurnDao = db.turnDao()
    @Provides fun noteDao(db: JimiDatabase): NoteDao = db.noteDao()
    @Provides fun reminderDao(db: JimiDatabase): ReminderDao = db.reminderDao()
    @Provides fun notificationDao(db: JimiDatabase): NotificationDao = db.notificationDao()
    @Provides fun memoryDao(db: JimiDatabase): MemoryDao = db.memoryDao()
    @Provides fun toolLogDao(db: JimiDatabase): ToolLogDao = db.toolLogDao()
    @Provides fun routineDao(db: JimiDatabase): RoutineDao = db.routineDao()
    @Provides fun appMapDao(db: JimiDatabase): AppMapDao = db.appMapDao()
    @Provides fun recipeDao(db: JimiDatabase): RecipeDao = db.recipeDao()
}
