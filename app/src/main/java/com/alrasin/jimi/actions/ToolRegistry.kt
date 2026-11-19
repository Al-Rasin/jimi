package com.alrasin.jimi.actions

import android.content.Context
import com.alrasin.jimi.knowledge.SharedEmbedder
import com.alrasin.jimi.knowledge.WikiOffline
import com.alrasin.jimi.agent.appmap.AppExplorer
import com.alrasin.jimi.agent.appmap.Planner
import com.alrasin.jimi.agent.appmap.learnTool
import com.alrasin.jimi.agent.appmap.mapTools
import com.alrasin.jimi.agent.RecipeStore
import com.alrasin.jimi.agent.TeachSession
import com.alrasin.jimi.agent.teachTools
import com.alrasin.jimi.agent.recipeTools
import com.alrasin.jimi.agent.skillTools
import com.alrasin.jimi.core.Secrets
import com.alrasin.jimi.core.Tool
import com.alrasin.jimi.core.ToolContext
import com.alrasin.jimi.core.ToolResult
import com.alrasin.jimi.data.MemoryDao
import com.alrasin.jimi.data.NoteDao
import com.alrasin.jimi.data.NotificationDao
import com.alrasin.jimi.data.ReminderDao
import com.alrasin.jimi.data.Settings
import com.alrasin.jimi.notify.dailySummaryTool
import com.alrasin.jimi.notify.notificationTools
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
        addAll(contactEditTools(context, contacts))
        addAll(callTools(context, contacts))
        add(chatReadTool(context, contacts, notifications))
        addAll(mediaTools(context))
        addAll(systemTools(context, Torch(context)))
        add(unlockTool(context) { secrets.get(Secrets.PIN) })
        addAll(screenTools(context))
        addAll(askScreenTools())
        addAll(songTools(context))
        add(translateTool())
        add(irTool(context))
        addAll(clipboardTools(context))
        addAll(cameraTools(context))
        val places = PlaceReminders(context)
        addAll(dataTools(notes, reminders, scheduler, memory, noteApp = NoteApp(context), places = places))
        addAll(placeTools(places, location))
        addAll(fileSearchTools(context, places))
        addAll(notificationTools(notifications))
        add(dailySummaryTool(context, contacts, notifications))
        val web = Web()
        addAll(webTools(web, FastWeather(context), { location.get() }, homeCity = { settings.homeCity.first() }, wikiOffline = WikiOffline.forPhone(context), similar = { q, t -> SharedEmbedder.similarities(context, q, t) }))
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
        add(selfTestTool(context) { c -> get(c.tool)?.run(c, ToolContext(context, "self test", locked = false)) ?: ToolResult.fail("no ${c.tool}") })
    }

    private val byName = all.associateBy { it.name }.also {
        check(it.size == all.size) { "duplicate tool names" }
    }

    fun get(name: String): Tool? = byName[name]
}
