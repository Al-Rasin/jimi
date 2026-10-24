package com.alrasin.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alrasin.jarvis.agent.appmap.AppEdge
import com.alrasin.jarvis.agent.appmap.AppElement
import com.alrasin.jarvis.agent.appmap.AppInfo
import com.alrasin.jarvis.agent.appmap.AppMapDao
import com.alrasin.jarvis.agent.appmap.AppScreen
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
        AppInfo::class, AppScreen::class, AppElement::class, AppEdge::class, RecipeRow::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class JarvisDatabase : RoomDatabase() {
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
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): JarvisDatabase =
        Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
            .addMigrations(JarvisDatabase.MIGRATION_1_2, JarvisDatabase.MIGRATION_2_3, JarvisDatabase.MIGRATION_3_4)
            .build()

    @Provides fun wakeEventDao(db: JarvisDatabase): WakeEventDao = db.wakeEventDao()
    @Provides fun turnDao(db: JarvisDatabase): TurnDao = db.turnDao()
    @Provides fun noteDao(db: JarvisDatabase): NoteDao = db.noteDao()
    @Provides fun reminderDao(db: JarvisDatabase): ReminderDao = db.reminderDao()
    @Provides fun notificationDao(db: JarvisDatabase): NotificationDao = db.notificationDao()
    @Provides fun memoryDao(db: JarvisDatabase): MemoryDao = db.memoryDao()
    @Provides fun toolLogDao(db: JarvisDatabase): ToolLogDao = db.toolLogDao()
    @Provides fun routineDao(db: JarvisDatabase): RoutineDao = db.routineDao()
    @Provides fun appMapDao(db: JarvisDatabase): AppMapDao = db.appMapDao()
    @Provides fun recipeDao(db: JarvisDatabase): RecipeDao = db.recipeDao()
}
