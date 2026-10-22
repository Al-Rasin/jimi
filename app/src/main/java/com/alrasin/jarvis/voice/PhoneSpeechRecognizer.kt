package com.alrasin.jarvis.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Owns a live microphone session. The caller must hand off its recorder before invoking this. */
class PhoneSpeechRecognizer(context: Context) {
    private val context = context.applicationContext

    suspend fun recognize(hotwords: List<String> = emptyList(), timeoutMillis: Long = 20_000): String? =
        withContext(Dispatchers.Main.immediate) {
            var recognizer: SpeechRecognizer? = null
            try {
                withTimeoutOrNull(timeoutMillis) {
                    val phone = create() ?: return@withTimeoutOrNull null
                    recognizer = phone
                    suspendCancellableCoroutine { continuation ->
                        fun finish(text: String?) {
                            if (continuation.isActive) continuation.resume(text)
                        }
                        phone.setRecognitionListener(object : RecognitionListener {
                            override fun onResults(results: Bundle?) = finish(
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull { it.isNotBlank() },
                            )
                            override fun onError(error: Int) = finish(null)
                            override fun onReadyForSpeech(params: Bundle?) = Unit
                            override fun onBeginningOfSpeech() = Unit
                            override fun onRmsChanged(rmsdB: Float) = Unit
                            override fun onBufferReceived(buffer: ByteArray?) = Unit
                            override fun onEndOfSpeech() = Unit
                            override fun onPartialResults(partialResults: Bundle?) = Unit
                            override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        })
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            if (Build.VERSION.SDK_INT >= 33) {
                                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                                    ArrayList(hotwords.take(SttHotwords.MAX_PHRASES)))
                            }
                        }
                        phone.startListening(intent)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            } finally {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
            }
        }

    private fun create(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= 31) {
            return if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        }
        // The default recognition service may be Jimi itself; explicitly select Google's provider.
        val google = ComponentName("com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        @Suppress("DEPRECATION")
        val providers = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
        if (providers.none { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == google }) return null
        return SpeechRecognizer.createSpeechRecognizer(context, google)
    }
}
