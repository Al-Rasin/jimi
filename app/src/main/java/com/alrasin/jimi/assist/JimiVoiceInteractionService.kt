package com.alrasin.jimi.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.data.Settings
import com.alrasin.jimi.service.JimiCoreService
import com.alrasin.jimi.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Makes Jimi eligible for selection as the phone's default digital assistant.
 * Android routes the configured assist gesture here only when the user selects Jimi in system settings.
 * Android needs three parts: this service, a session service, and a recognition service (stub).
 */
class JimiVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        JimiLog.i("Jimi is the default assistant")
    }
}

@AndroidEntryPoint
class JimiSessionService : VoiceInteractionSessionService() {
    @Inject lateinit var settings: Settings
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JimiSession(this, settings)
}

/** No window of its own: it hands over to the core service, which opens the panel and listens. */
private class JimiSession(
    private val service: VoiceInteractionSessionService,
    private val settings: Settings,
) : VoiceInteractionSession(service) {
    private val scope = MainScope()

    // Settings are read off the main thread's blocking path, so the assist gesture never freezes the UI.
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        scope.launch {
            if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                !settings.setupComplete.first()) {
                startAssistantActivity(Intent(service, MainActivity::class.java))
            } else {
                JimiCoreService.listen(service)
            }
            hide()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Required by Android for an assistant app. Jimi does its own speech-to-text, so other apps get "not available". */
class JimiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
