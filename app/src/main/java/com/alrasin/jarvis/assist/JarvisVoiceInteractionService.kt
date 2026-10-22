package com.alrasin.jarvis.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.service.JarvisCoreService

/**
 * Makes Jimi the phone's "Default digital assistant app" in place of Google Assistant / Gemini (D-036).
 * Then long-press home, the assist gesture and MIUI's power-button assistant all open Jimi's panel.
 * Android needs three parts: this service, a session service, and a recognition service (stub).
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        JarvisLog.i("Jimi is the default assistant")
    }
}

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}

/** No window of its own: it hands over to the core service, which opens the panel and listens. */
private class JarvisSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        JarvisCoreService.listen(service)
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
