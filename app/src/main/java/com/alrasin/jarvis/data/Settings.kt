package com.alrasin.jarvis.data

import com.alrasin.jarvis.brain.ContextModes
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alrasin.jarvis.voice.Voices
import com.alrasin.jarvis.llm.ChatModels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class Settings @Inject constructor(@ApplicationContext private val context: Context) {
    private val thresholdKey = floatPreferencesKey("wake_threshold")
    private val enabledKey = booleanPreferencesKey("listening_enabled")
    private val spokenWakeKey = booleanPreferencesKey("spoken_wake")
    private val followUpKey = booleanPreferencesKey("follow_up")
    private val sttSecondPassKey = booleanPreferencesKey("stt_second_pass")
    private val autoClearKey = booleanPreferencesKey("auto_clear_notifications")
    private val nightExploreKey = booleanPreferencesKey("night_explore")
    private val nightExploredKey = stringPreferencesKey("night_explored")
    private val cityKey = stringPreferencesKey("home_city")
    private val panelKey = booleanPreferencesKey("show_panel")
    private val voiceIdKey = booleanPreferencesKey("voice_id")
    private val speakerThresholdKey = floatPreferencesKey("speaker_threshold")
    private val cloudKey = booleanPreferencesKey("cloud_enabled")
    private val cloudChatKey = booleanPreferencesKey("cloud_for_chat")
    private val cloudFactsKey = booleanPreferencesKey("cloud_send_facts")
    private val personaNotesKey = stringPreferencesKey("persona_notes")
    private val voiceKey = stringPreferencesKey("voice")
    private val voiceSpeedKey = floatPreferencesKey("voice_speed")
    private val onlineVoiceKey = booleanPreferencesKey("online_voice")
    private val assistantModeKey = stringPreferencesKey("assistant_mode")
    private val setupCompleteKey = booleanPreferencesKey("setup_complete")
    private val carModeKey = booleanPreferencesKey("car_mode")
    private val headphonesModeKey = booleanPreferencesKey("headphones_mode")
    private val bedtimeModeKey = booleanPreferencesKey("bedtime_mode")
    private val bedtimeHoursKey = stringPreferencesKey("bedtime_hours")
    private val proactiveKey = booleanPreferencesKey("proactive_talk")
    private val readImportantKey = booleanPreferencesKey("read_important_alerts")

    val assistantMode: Flow<AssistantMode> = context.dataStore.data.map { AssistantMode.fromStored(it[assistantModeKey]) }
    val setupComplete: Flow<Boolean> = read(setupCompleteKey, false)

    suspend fun setAssistantMode(mode: AssistantMode) = context.dataStore.edit { it[assistantModeKey] = mode.name }
    suspend fun setSetupComplete(complete: Boolean) = context.dataStore.edit {
        if (complete && it[setupCompleteKey] != true) {
            val speed = VoiceDefaults.speed(it[voiceSpeedKey])
            it[voiceKey] = VoiceDefaults.voice(it[voiceKey])
            it[voiceSpeedKey] = speed
        }
        it[setupCompleteKey] = complete
    }
    private val chatModelKey = stringPreferencesKey("chat_model")

    val wakeThreshold: Flow<Float> = read(thresholdKey, DEFAULT_THRESHOLD)
    val listeningEnabled: Flow<Boolean> = read(enabledKey, false)

    /** Listen again for ~6 s after a reply, no wake word needed (round 3). */
    val spokenWake: Flow<Boolean> = read(spokenWakeKey, true)
    suspend fun setSpokenWake(value: Boolean) = write(spokenWakeKey, value)

    val followUp: Flow<Boolean> = read(followUpKey, true)
    val sttSecondPass: Flow<Boolean> = read(sttSecondPassKey, false)
    val autoClearNotifications: Flow<Boolean> = read(autoClearKey, false)
    /** User OK for learning apps at night while charging (§J.5). Off by default. */
    val nightExplore: Flow<Boolean> = read(nightExploreKey, false)
    /** When each app was last explored at night: "pkg=millis;pkg=millis". */
    val nightExplored: Flow<String> = read(nightExploredKey, "")
    val homeCity: Flow<String> = read(cityKey, "Dhaka")
    /** Assistant panel over the lock screen / apps after a wake. */
    val showPanel: Flow<Boolean> = read(panelKey, true)
    /** Owner voice check for risky tools while locked (needs enrolment). */
    val voiceId: Flow<Boolean> = read(voiceIdKey, false)
    /** Voice ID match needs cosine similarity ≥ this (higher = stricter). */
    val speakerThreshold: Flow<Float> = read(speakerThresholdKey, DEFAULT_SPEAKER_THRESHOLD)

    /** Optional cloud (D-005): off by default; also needs an API key in Secrets. */
    val cloudEnabled: Flow<Boolean> = read(cloudKey, false)
    /** All chat goes to the cloud when online (else only "think hard …"). */
    val cloudForChat: Flow<Boolean> = read(cloudChatKey, true)
    /** Privacy (PLAN 14.3): remembered facts are sent only if this is on. Notes, notifications, screen text never are. */
    val cloudSendFacts: Flow<Boolean> = read(cloudFactsKey, false)
    /** How Jimi should talk (tone, reply length), added to the chat prompt (ROADMAP #43, §K 10.3). */
    val personaNotes: Flow<String> = read(personaNotesKey, "")

    /** Jimi's voice id (see Voices, D-038). */
    val voice: Flow<String> = context.dataStore.data.map { Voices.byId(VoiceDefaults.voice(it[voiceKey])).id }
    /** Speaking speed, 1.0 = normal. */
    val voiceSpeed: Flow<Float> = context.dataStore.data.map { VoiceDefaults.speed(it[voiceSpeedKey]) }
    /** Google's online voice when the internet works (D-074). Off by default: it sends spoken text to Google. */
    val onlineVoice: Flow<Boolean> = read(onlineVoiceKey, false)
    val chatModel: Flow<String> = context.dataStore.data.map {
        if (it[chatModelKey] == ChatModels.LFM) ChatModels.LFM else ChatModels.DEFAULT
    }

    suspend fun setChatModel(v: String) = write(chatModelKey, v)
    suspend fun setVoice(v: String) = context.dataStore.edit { it[voiceKey] = Voices.byId(v).id }
    suspend fun setVoiceSpeed(v: Float) = write(voiceSpeedKey, v)
    suspend fun setOnlineVoice(v: Boolean) = write(onlineVoiceKey, v)
    suspend fun setCloudEnabled(v: Boolean) = write(cloudKey, v)
    suspend fun setCloudForChat(v: Boolean) = write(cloudChatKey, v)
    suspend fun setCloudSendFacts(v: Boolean) = write(cloudFactsKey, v)
    suspend fun setPersonaNotes(v: String) = write(personaNotesKey, v)
    suspend fun setFollowUp(v: Boolean) = write(followUpKey, v)

    /** Context modes (ROADMAP B §I.11): car and headphones on by default, bedtime off until the user picks it. */
    val carMode: Flow<Boolean> = read(carModeKey, true)
    val headphonesMode: Flow<Boolean> = read(headphonesModeKey, true)
    val bedtimeMode: Flow<Boolean> = read(bedtimeModeKey, false)
    val bedtimeHours: Flow<String> = read(bedtimeHoursKey, ContextModes.DEFAULT_HOURS)
    suspend fun setCarMode(v: Boolean) = write(carModeKey, v)
    suspend fun setHeadphonesMode(v: Boolean) = write(headphonesModeKey, v)
    suspend fun setBedtimeMode(v: Boolean) = write(bedtimeModeKey, v)
    suspend fun setBedtimeHours(v: String) = write(bedtimeHoursKey, v)

    /** Jimi speaks on its own (ROADMAP #44): late-night nudge and "while you were away". Read-out of alerts is opt-in. */
    val proactive: Flow<Boolean> = read(proactiveKey, true)
    val readImportant: Flow<Boolean> = read(readImportantKey, false)
    suspend fun setProactive(v: Boolean) = write(proactiveKey, v)
    suspend fun setReadImportant(v: Boolean) = write(readImportantKey, v)
    suspend fun setSttSecondPass(v: Boolean) = write(sttSecondPassKey, v)
    suspend fun setAutoClearNotifications(v: Boolean) = write(autoClearKey, v)
    suspend fun setNightExplore(v: Boolean) = write(nightExploreKey, v)
    suspend fun setNightExplored(v: String) = write(nightExploredKey, v)
    suspend fun setHomeCity(v: String) = write(cityKey, v)
    suspend fun setShowPanel(v: Boolean) = write(panelKey, v)
    suspend fun setVoiceId(v: Boolean) = write(voiceIdKey, v)
    suspend fun setSpeakerThreshold(v: Float) = write(speakerThresholdKey, v)
    suspend fun setWakeThreshold(value: Float) = write(thresholdKey, value)
    suspend fun setListeningEnabled(value: Boolean) = write(enabledKey, value)

    private fun <T> read(key: Preferences.Key<T>, default: T): Flow<T> = context.dataStore.data.map { it[key] ?: default }

    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_SPEAKER_THRESHOLD = 0.5f
    }
}
