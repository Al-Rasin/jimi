package com.alrasin.jarvis

import android.app.Application
import com.alrasin.jarvis.service.Notifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
