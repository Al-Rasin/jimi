package com.alrasin.jarvis.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Owns a live microphone session. The caller must hand off its recorder before invoking this. */
class PhoneSpeechRecognizer(context: Context) {
    private val context = context.applicationContext

    suspend fun recognize(hotwords: List<String> = emptyList(), timeoutMillis: Long = 20_000, onReady: () -> Unit = {}): String? =
        withContext(Dispatchers.Main.immediate) {
            var recognizer: SpeechRecognizer? = null
            try {
                withTimeoutOrNull(timeoutMillis) {
                    val online = isOnline()
                    val phone = create(online) ?: run {
                        JarvisLog.i("STT engine=Phone provider unavailable online=$online")
                        return@withTimeoutOrNull null
                    }
                    val started = android.os.SystemClock.elapsedRealtime()
                    JarvisLog.i("STT engine=Phone start online=$online language=$LANGUAGE")
                    recognizer = phone
                    suspendCancellableCoroutine { continuation ->
                        var partial: String? = null
                        var peakDb = -100f
                        var speechAt = 0L
                        fun finish(text: String?) {
                            if (continuation.isActive) continuation.resume(text)
                        }
                        phone.setRecognitionListener(object : RecognitionListener {
                            override fun onResults(results: Bundle?) = finish(
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull { it.isNotBlank() },
                            )
                            override fun onError(error: Int) {
                                val ms = android.os.SystemClock.elapsedRealtime() - started
                                JarvisLog.i("STT engine=Phone error=$error online=$online at $ms ms " +
                                    "speech=${speechAt > 0} peak=${peakDb.toInt()} dB partial=${partial != null}")
                                // NO_MATCH after real speech: the last partial guess is better than nothing.
                                finish(if (error == SpeechRecognizer.ERROR_NO_MATCH) partial else null)
                            }
                            override fun onReadyForSpeech(params: Bundle?) {
                                JarvisLog.i("STT engine=Phone ready in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                                if (continuation.isActive) onReady()
                            }
                            override fun onBeginningOfSpeech() {
                                speechAt = android.os.SystemClock.elapsedRealtime()
                                JarvisLog.i("STT engine=Phone speech began at ${speechAt - started} ms")
                            }
                            override fun onRmsChanged(rmsdB: Float) { if (rmsdB > peakDb) peakDb = rmsdB }
                            override fun onBufferReceived(buffer: ByteArray?) = Unit
                            override fun onEndOfSpeech() {
                                JarvisLog.i("STT engine=Phone speech ended at ${android.os.SystemClock.elapsedRealtime() - started} ms")
                            }
                            override fun onPartialResults(partialResults: Bundle?) {
                                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull { it.isNotBlank() }?.let { partial = it }
                            }
                            override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        })
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                            // D-043: Google's online recognizer when internet works, its offline pack otherwise.
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
            } catch (error: RuntimeException) {
                JarvisLog.e("STT engine=Phone start failed", error)
                null
            } finally {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
            }
        }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun create(online: Boolean): SpeechRecognizer? {
        // The default recognition service may be Jimi itself; explicitly select Google's provider.
        val google = ComponentName("com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        @Suppress("DEPRECATION")
        val providers = context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
        if (providers.any { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == google }) {
            return SpeechRecognizer.createSpeechRecognizer(context, google)
        }
        if (!online && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return null
    }

    private companion object {
        const val LANGUAGE = "en-IN"
    }
}
