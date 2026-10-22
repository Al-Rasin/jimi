package com.alrasin.jarvis.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.RootSetup
import com.alrasin.jarvis.actions.ToolExecutor
import com.alrasin.jarvis.actions.ToolRegistry
import com.alrasin.jarvis.brain.ModelBrain
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Secrets
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.data.AppRule
import com.alrasin.jarvis.data.Backup
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.ReminderDao
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.TurnDao
import com.alrasin.jarvis.actions.ReminderScheduler
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.voice.SpeakerVerifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Data for the Chat / History / Notifications / Notes / Settings tabs. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val app: Application,
    private val settings: Settings,
    private val turns: TurnDao,
    private val notes: NoteDao,
    private val reminders: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val notifications: NotificationDao,
    private val memory: MemoryDao,
    private val secrets: Secrets,
    private val backup: Backup,
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val models: ModelBrain,
    private val speaker: SpeakerVerifier,
) : AndroidViewModel(app) {
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val turnList = turns.recent().state(emptyList())
    val noteList = notes.all().state(emptyList())
    val reminderList = reminders.all().state(emptyList())
    val notificationList = notifications.recent().state(emptyList())
    val rules = notifications.rulesFlow().state(emptyList())
    val facts = memory.flow().state(emptyList())
    val followUp = settings.followUp.state(true)
    val showPanel = settings.showPanel.state(true)
    val autoClear = settings.autoClearNotifications.state(false)
    val voiceId = settings.voiceId.state(false)
    val speakerThreshold = settings.speakerThreshold.state(Settings.DEFAULT_SPEAKER_THRESHOLD)
    val enrollStatus = JarvisCoreService.enrollStatus
    val lastVoiceScore = JarvisCoreService.lastVoiceScore
    val voiceSamples = MutableStateFlow(speaker.samples)
    val cloudEnabled = settings.cloudEnabled.state(false)
    val cloudForChat = settings.cloudForChat.state(false)
    val cloudSendFacts = settings.cloudSendFacts.state(false)
    val cloudKeySet = MutableStateFlow(secrets.has(Secrets.CLOUD_KEY))
    val homeCity = settings.homeCity.state("Dhaka")
    val toolNames: List<String> get() = registry.all.map { it.name }.sorted()

    private val _message = MutableStateFlow("")
    /** Result line of the last button (setup, export, tool run). */
    val message: StateFlow<String> = _message
    val pinSet = MutableStateFlow(secrets.has(Secrets.PIN))
    val modelStatus = MutableStateFlow(models.status())
    private var player: MediaPlayer? = null

    fun ask(text: String) = JarvisCoreService.ask(app, text)

    fun listen() = JarvisCoreService.listen(app)

    fun rate(id: Long, ok: Boolean) = viewModelScope.launch { turns.rate(id, ok, null) }

    fun fix(id: Long, text: String) = viewModelScope.launch {
        turns.rate(id, false, text)
        JarvisCoreService.ask(app, text, source = "fix")
    }

    fun play(path: String) {
        player?.release()
        player = runCatching { MediaPlayer().apply { setDataSource(path); prepare(); start() } }
            .onFailure { JarvisLog.e("play failed", it) }.getOrNull()
    }

    fun label(id: Long, important: Boolean) = viewModelScope.launch {
        notifications.setLabel(id, if (important) "important" else "noise")
    }

    /** [mode]: keep | clear | null (no rule). */
    fun setRule(pkg: String, mode: String?) = viewModelScope.launch {
        if (mode == null) notifications.clearRule(pkg) else notifications.setRule(AppRule(pkg, mode))
    }

    fun deleteNote(id: Long) = viewModelScope.launch { notes.delete(id) }

    fun deleteReminder(id: Long) = viewModelScope.launch {
        scheduler.cancel(id)
        reminders.delete(id)
    }

    fun forget(id: Long) = viewModelScope.launch { memory.delete(id) }

    fun setFollowUp(v: Boolean) = viewModelScope.launch { settings.setFollowUp(v) }
    fun setShowPanel(v: Boolean) = viewModelScope.launch { settings.setShowPanel(v) }
    fun setAutoClear(v: Boolean) = viewModelScope.launch { settings.setAutoClearNotifications(v) }
    fun setVoiceId(v: Boolean) = viewModelScope.launch { settings.setVoiceId(v) }
    fun setSpeakerThreshold(v: Float) = viewModelScope.launch { settings.setSpeakerThreshold(v) }
    fun setCloudEnabled(v: Boolean) = viewModelScope.launch { settings.setCloudEnabled(v) }
    fun setCloudForChat(v: Boolean) = viewModelScope.launch { settings.setCloudForChat(v) }
    fun setCloudSendFacts(v: Boolean) = viewModelScope.launch { settings.setCloudSendFacts(v) }

    fun setCloudKey(key: String) {
        secrets.put(Secrets.CLOUD_KEY, key.trim().ifEmpty { null })
        cloudKeySet.value = secrets.has(Secrets.CLOUD_KEY)
        _message.value = if (cloudKeySet.value) "API key saved (encrypted)." else "API key removed."
    }

    fun enrollVoice() = JarvisCoreService.enroll(app)
    fun refreshVoice() { voiceSamples.value = speaker.samples }

    fun resetVoice() {
        speaker.reset()
        voiceSamples.value = 0
        _message.value = "Voice ID recordings deleted."
    }
    fun setHomeCity(v: String) = viewModelScope.launch { settings.setHomeCity(v.trim().ifEmpty { "Dhaka" }) }

    fun setPin(pin: String) {
        secrets.put(Secrets.PIN, pin.trim().ifEmpty { null })
        pinSet.value = secrets.has(Secrets.PIN)
        _message.value = if (pinSet.value) "PIN saved (encrypted)." else "PIN removed."
    }

    fun rootSetup() = viewModelScope.launch(Dispatchers.IO) {
        _message.value = "Setting up…"
        val lines = RootSetup.run(app)
        val failed = lines.filter { it.startsWith("fail") }
        _message.value = if (failed.isEmpty()) "Setup done (${lines.size} steps)." else failed.joinToString("\n")
    }

    fun export() = viewModelScope.launch(Dispatchers.IO) {
        _message.value = runCatching { "Saved: ${backup.export().path}" }.getOrElse { "Export failed: ${it.message}" }
    }

    fun refreshModels() { modelStatus.value = models.status() }

    /** Debug: run any tool with JSON args, no voice. Risky tools still ask (confirmed = false). */
    fun runTool(name: String, argsJson: String) = viewModelScope.launch {
        val args = runCatching {
            (Json.parse(argsJson.ifBlank { "{}" }) as Map<*, *>).entries.associate { (k, v) -> k.toString() to v.toString() }
        }.getOrElse { _message.value = "Bad JSON: ${it.message}"; return@launch }
        val o = executor.execute(ToolCall(name, args), ToolContext(app, "debug", false), confirmed = true, voiceOk = true)
        _message.value = when (o) {
            is Outcome.Done -> (if (o.result.ok) "ok: " else "fail: ") + o.result.say + o.result.data.take(500).let { if (it.isEmpty()) "" else "\n$it" }
            is Outcome.NeedConfirm -> "asks: ${o.question}"
            is Outcome.Denied -> "denied: ${o.why}"
        }
    }
}
