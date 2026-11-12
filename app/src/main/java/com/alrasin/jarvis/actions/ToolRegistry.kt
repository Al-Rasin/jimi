package com.alrasin.jarvis.actions

import android.content.Context
import com.alrasin.jarvis.agent.appmap.AppExplorer
import com.alrasin.jarvis.agent.appmap.Planner
import com.alrasin.jarvis.agent.appmap.learnTool
import com.alrasin.jarvis.agent.appmap.mapTools
import com.alrasin.jarvis.agent.RecipeStore
import com.alrasin.jarvis.agent.TeachSession
import com.alrasin.jarvis.agent.teachTools
import com.alrasin.jarvis.agent.recipeTools
import com.alrasin.jarvis.agent.skillTools
import com.alrasin.jarvis.core.Secrets
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.ReminderDao
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.notify.dailySummaryTool
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
    planner: Planner,
    recipes: RecipeStore,
    explorer: AppExplorer,
    teach: TeachSession,
) {
    val apps = AppList(context)
    val contacts = Contacts(context)
    val location = CurrentLocation(context)
    val profile = PhoneProfile(context, apps)

    val all: List<Tool> = buildList {
        addAll(appTools(context, apps))
        addAll(timeTools(context))
        addAll(calendarTools(context))
        addAll(commsTools(context, contacts))
        addAll(callTools(context, contacts))
        add(chatReadTool(context, contacts, notifications))
        addAll(mediaTools(context))
        addAll(systemTools(context, Torch(context)))
        add(unlockTool(context) { secrets.get(Secrets.PIN) })
        addAll(screenTools(context))
        addAll(clipboardTools(context))
        addAll(dataTools(notes, reminders, scheduler, memory, noteApp = NoteApp(context)))
        addAll(notificationTools(notifications))
        add(dailySummaryTool(context, contacts, notifications))
        val web = Web()
        addAll(webTools(web, FastWeather(context), { location.get() }, homeCity = { settings.homeCity.first() }))
        addAll(freeApiTools(web, { location.get() }, homeCity = { settings.homeCity.first() }))
        add(storeSearchTool(context))
        add(navigationTool(context, location))
        add(myLocationTool(location))
        add(phoneProfileTool(profile) { all.size })
        add(settingTool(SettingsPages(context)))
        addAll(mapTools(planner, apps))
        addAll(skillTools(context, planner, apps))
        addAll(recipeTools(context, planner, apps, recipes))
        add(learnTool(context, explorer, planner, apps))
        addAll(teachTools(context, teach, recipes))
    }

    private val byName = all.associateBy { it.name }.also {
        check(it.size == all.size) { "duplicate tool names" }
    }

    fun get(name: String): Tool? = byName[name]
}
