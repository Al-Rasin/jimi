package com.alrasin.jarvis.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.service.WakeFeedback
import com.alrasin.jarvis.voice.Wav
import com.alrasin.jarvis.voice.WakeModels
import com.alrasin.jarvis.voice.WakeWordDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    private val settings: Settings,
    private val dao: WakeEventDao,
) : AndroidViewModel(app) {
    val running = JarvisCoreService.running
    val lastScore = JarvisCoreService.lastScore
    val modelName = JarvisCoreService.modelName
    val events = dao.recent().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val counts = dao.counts().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val threshold = settings.wakeThreshold.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_THRESHOLD)
    val log = JarvisLog.lines
    private var player: MediaPlayer? = null

    fun start() = viewModelScope.launch {
        settings.setListeningEnabled(true)
        JarvisCoreService.start(app)
    }

    fun missed() {
        JarvisCoreService.missed(app)
        JarvisLog.i("missed: saving last 3 s")
    }

    fun stop() {
        JarvisCoreService.stop(app)
    }

    fun setThreshold(v: Float) = viewModelScope.launch { settings.setWakeThreshold(v) }

    fun setVerdict(id: Long, verdict: String) = viewModelScope.launch(Dispatchers.IO) {
        WakeFeedback.setVerdict(app, dao, id, verdict)
    }

    fun play(path: String) {
        player?.release()
        player = runCatching {
            MediaPlayer().apply { setDataSource(path); prepare(); start() }
        }.onFailure { JarvisLog.e("play failed", it) }.getOrNull()
    }

    /**
     * Debug: score every WAV (16 kHz mono) in [dir] and log the max score per file.
     * Used to check the Kotlin port against Python:
     * `adb shell am start -n com.alrasin.jarvis/.ui.MainActivity --es test_dir <dir>`
     */
    fun scoreDir(dir: String) = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val m = WakeModels.load(app)
            WakeWordDetector(m.mel, m.embedding, m.wake).use { det ->
                val files = File(dir).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
                for (f in files) {
                    det.reset()
                    val audio = Wav.read(f)
                    val t0 = System.nanoTime()
                    var max = 0f
                    var maxAt = 0
                    var i = 0
                    while (i + WakeWordDetector.CHUNK <= audio.size) {
                        val s = det.process(audio.copyOfRange(i, i + WakeWordDetector.CHUNK))
                        if (s > max) { max = s; maxAt = i }
                        i += WakeWordDetector.CHUNK
                    }
                    val cpu = (System.nanoTime() - t0) / 1e9f / (audio.size / 16000f)
                    JarvisLog.i(
                        "TEST ${m.wakeName} ${f.name} max=${"%.3f".format(max)} at=${"%.2f".format(maxAt / 16000f)}s " +
                            "cpu=${"%.0f".format(cpu * 100)}% of realtime",
                    )
                }
                JarvisLog.i("TEST done (${files.size} files)")
            }
        }.onFailure { JarvisLog.e("TEST failed", it) }
    }

    override fun onCleared() {
        player?.release()
    }
}
