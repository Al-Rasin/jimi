package com.alrasin.jimi.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.alrasin.jimi.core.JimiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Owns a live microphone session. The caller must hand off its recorder before invoking this. */
class PhoneSpeechRecognizer(context: Context) {
    private val context = context.applicationContext
    @Volatile private var warm: ServiceConnection? = null

    /**
     * Keeps Google's recognition service bound while the Phone engine is picked. Android 11 binds it inside
     * startListening and unbinds on destroy, so after a quiet spell MIUI has killed it and the next listen
     * waits ~4 s for the process to start (seen: "ready in 4044 ms", then 180 ms). Binding alone never
     * starts listening, so the mic stays free for the sherpa wake loop. Safe to call again with the same value.
     */
    @Synchronized
    fun keepWarm(on: Boolean) {
        if (on == (warm != null)) return
        if (!on) {
            warm?.let { runCatching { context.unbindService(it) } }
            warm = null
            JimiLog.i("STT engine=Phone keep-warm off")
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) =
                JimiLog.i("STT engine=Phone keep-warm connected")
            override fun onServiceDisconnected(name: ComponentName?) =
                JimiLog.i("STT engine=Phone keep-warm lost; Android rebinds when it can")
        }
        val bound = runCatching {
            context.bindService(Intent(RECOGNITION_SERVICE).setComponent(GOOGLE), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { JimiLog.e("STT engine=Phone keep-warm failed", it) }.getOrDefault(false)
        if (bound) warm = connection else runCatching { context.unbindService(connection) }
        JimiLog.i("STT engine=Phone keep-warm on bound=$bound")
    }

    suspend fun recognize(hotwords: List<String> = emptyList(), timeoutMillis: Long = 20_000, onReady: () -> Unit = {}): String? =
        withContext(Dispatchers.Main.immediate) {
            var recognizer: SpeechRecognizer? = null
            try {
                withTimeoutOrNull(timeoutMillis) {
                    val online = isOnline()
                    val phone = create(online) ?: run {
                        JimiLog.i("STT engine=Phone provider unavailable online=$online")
                        return@withTimeoutOrNull null
                    }
                    val started = android.os.SystemClock.elapsedRealtime()
                    JimiLog.i("STT engine=Phone start online=$online language=$LANGUAGE")
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
                                JimiLog.i("STT engine=Phone error=$error online=$online at $ms ms " +
                                    "speech=${speechAt > 0} peak=${peakDb.toInt()} dB partial=${partial != null}")
                                // NO_MATCH after real speech: the last partial guess is better than nothing.
                                finish(if (error == SpeechRecognizer.ERROR_NO_MATCH) partial else null)
                            }
                            override fun onReadyForSpeech(params: Bundle?) {
                                JimiLog.i("STT engine=Phone ready in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                                if (continuation.isActive) onReady()
                            }
                            override fun onBeginningOfSpeech() {
                                speechAt = android.os.SystemClock.elapsedRealtime()
                                JimiLog.i("STT engine=Phone speech began at ${speechAt - started} ms")
                            }
                            override fun onRmsChanged(rmsdB: Float) { if (rmsdB > peakDb) peakDb = rmsdB }
                            override fun onBufferReceived(buffer: ByteArray?) = Unit
                            override fun onEndOfSpeech() {
                                JimiLog.i("STT engine=Phone speech ended at ${android.os.SystemClock.elapsedRealtime() - started} ms")
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
                            // D-093: Bangla too, so "tumi ki bangla bujho" comes back in Bangla script, like Assistant's
                            // "additional languages". Google's offline pack is English only, so this only works online.
                            if (online) {
                                // D-103: Google reads this hidden extra as String[]; an ArrayList was ignored (Bangla came back as English).
                                putExtra(EXTRA_ADDITIONAL_LANGUAGES, arrayOf(BANGLA))
                                if (Build.VERSION.SDK_INT >= 34) {
                                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
                                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf(LANGUAGE, BANGLA))
                                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf(LANGUAGE, BANGLA))
                                }
                            }
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
                JimiLog.e("STT engine=Phone start failed", error)
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
        @Suppress("DEPRECATION")
        val providers = context.packageManager.queryIntentServices(Intent(RECOGNITION_SERVICE), 0)
        if (providers.any { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == GOOGLE }) {
            return SpeechRecognizer.createSpeechRecognizer(context, GOOGLE)
        }
        if (!online && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return null
    }

    private companion object {
        const val LANGUAGE = "en-IN"
        const val BANGLA = "bn-BD"
        const val RECOGNITION_SERVICE = "android.speech.RecognitionService"
        val GOOGLE = ComponentName("com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        /** Hidden Google extra (used by Assistant's bilingual mode); ignored by recognizers that don't know it. */
        const val EXTRA_ADDITIONAL_LANGUAGES = "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"
    }
}
