package com.alrasin.jarvis

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.ComponentCallbacks2
import com.alrasin.jarvis.core.JarvisLog
import android.os.StrictMode
import com.alrasin.jarvis.agent.appmap.NightExploreWorker
import com.alrasin.jarvis.brain.ModelBrain
import com.alrasin.jarvis.service.Notifications
import dagger.hilt.android.HiltAndroidApp
import dagger.Lazy
import javax.inject.Inject

@HiltAndroidApp
class JarvisApp : Application() {
    @Inject lateinit var models: Lazy<ModelBrain>

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls()
                .penaltyLog().build())
        }
        Notifications.createChannels(this)
        NightExploreWorker.schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // BACKGROUND (40) fires for this UI-less service all the time; unloading then cost a 3 s reload per command.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            JarvisLog.i("memory trim level $level: unloading LLMs")
            models.get().releaseModels()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        models.get().releaseModels()
    }
}
