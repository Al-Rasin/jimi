package com.alrasin.jarvis.actions

import android.content.Context
import com.alrasin.jarvis.core.Secrets
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.ReminderDao
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.notify.notificationTools
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every phone tool by name (ARCHITECTURE §4). Talk tools (cancel, repeat_last, chat) and run_routine
 * are handled by the Assistant itself, because they need the conversation / the executor.
 */
@Singleton
class ToolRegistry @Inject constructor(
    @ApplicationContext context: Context,
    notes: NoteDao,
    reminders: ReminderDao,
    scheduler: ReminderScheduler,
    memory: MemoryDao,
    notifications: NotificationDao,
    settings: Settings,
    secrets: Secrets,
) {
    val apps = AppList(context)
    val contacts = Contacts(context)
    val location = CurrentLocation(context)

    val all: List<Tool> = buildList {
        addAll(appTools(context, apps))
        addAll(timeTools(context))
        addAll(commsTools(context, contacts))
        addAll(mediaTools(context))
        addAll(systemTools(context, Torch(context)))
        add(unlockTool(context) { secrets.get(Secrets.PIN) })
        addAll(screenTools(context))
        addAll(dataTools(notes, reminders, scheduler, memory))
        addAll(notificationTools(notifications))
        addAll(webTools(Web(), FastWeather(context), { location.get() }) { settings.homeCity.first() })
        add(myLocationTool(location))
    }

    private val byName = all.associateBy { it.name }.also {
        check(it.size == all.size) { "duplicate tool names" }
    }

    fun get(name: String): Tool? = byName[name]
}
