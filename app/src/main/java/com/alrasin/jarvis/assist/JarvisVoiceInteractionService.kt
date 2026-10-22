package com.alrasin.jarvis.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Makes Jimi eligible for selection as the phone's default digital assistant.
 * Android routes the configured assist gesture here only when the user selects Jimi in system settings.
 * Android needs three parts: this service, a session service, and a recognition service (stub).
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        JarvisLog.i("Jimi is the default assistant")
    }
}

@AndroidEntryPoint
class JarvisSessionService : VoiceInteractionSessionService() {
    @Inject lateinit var settings: Settings
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this, settings)
}

/** No window of its own: it hands over to the core service, which opens the panel and listens. */
private class JarvisSession(
    private val service: VoiceInteractionSessionService,
    private val settings: Settings,
) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !runBlocking { settings.setupComplete.first() }) {
            startAssistantActivity(Intent(service, MainActivity::class.java))
        } else {
            JarvisCoreService.listen(service)
        }
        hide()
    }
}

/** Required by Android for an assistant app. Jimi does its own speech-to-text, so other apps get "not available". */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
