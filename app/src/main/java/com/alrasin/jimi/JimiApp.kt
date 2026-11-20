package com.alrasin.jimi

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.ComponentCallbacks2
import com.alrasin.jimi.actions.PlaceReminders
import com.alrasin.jimi.actions.RootSettings
import com.alrasin.jimi.core.JimiLog
import android.os.StrictMode
import com.alrasin.jimi.agent.appmap.NightExploreWorker
import com.alrasin.jimi.brain.ModelBrain
import com.alrasin.jimi.service.Notifications
import dagger.hilt.android.HiltAndroidApp
import dagger.Lazy
import javax.inject.Inject

@HiltAndroidApp
class JimiApp : Application() {
    @Inject lateinit var models: Lazy<ModelBrain>

    override fun onCreate() {
        super.onCreate()
        JimiLog.init(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls()
                .penaltyLog().build())
        }
        RootSettings.init(this)
        Notifications.createChannels(this)
        NightExploreWorker.schedule(this)
        Thread { runCatching { PlaceReminders(this).restoreAll() } }.start()   // proximity alerts die on app update
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // BACKGROUND (40) fires for this UI-less service all the time; unloading then cost a 3 s reload per command.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            JimiLog.i("memory trim level $level: unloading LLMs")
            models.get().releaseModels(force = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        models.get().releaseModels(force = true)
    }
}
