package com.alrasin.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    private val followUpKey = booleanPreferencesKey("follow_up")
    private val autoClearKey = booleanPreferencesKey("auto_clear_notifications")
    private val cityKey = stringPreferencesKey("home_city")
    private val panelKey = booleanPreferencesKey("show_panel")
    private val voiceIdKey = booleanPreferencesKey("voice_id")
    private val speakerThresholdKey = floatPreferencesKey("speaker_threshold")
    private val cloudKey = booleanPreferencesKey("cloud_enabled")
    private val cloudChatKey = booleanPreferencesKey("cloud_for_chat")
    private val cloudFactsKey = booleanPreferencesKey("cloud_send_facts")

    val wakeThreshold: Flow<Float> = context.dataStore.data.map { it[thresholdKey] ?: DEFAULT_THRESHOLD }
    val listeningEnabled: Flow<Boolean> = context.dataStore.data.map { it[enabledKey] ?: false }

    /** Listen again for ~6 s after a reply, no wake word needed (round 3). */
    val followUp: Flow<Boolean> = context.dataStore.data.map { it[followUpKey] ?: true }
    val autoClearNotifications: Flow<Boolean> = context.dataStore.data.map { it[autoClearKey] ?: false }
    val homeCity: Flow<String> = context.dataStore.data.map { it[cityKey] ?: "Dhaka" }
    /** Assistant panel over the lock screen / apps after a wake. */
    val showPanel: Flow<Boolean> = context.dataStore.data.map { it[panelKey] ?: true }
    /** Owner voice check for risky tools while locked (needs enrolment). */
    val voiceId: Flow<Boolean> = context.dataStore.data.map { it[voiceIdKey] ?: false }
    /** Voice ID match needs cosine similarity ≥ this (higher = stricter). */
    val speakerThreshold: Flow<Float> = context.dataStore.data.map { it[speakerThresholdKey] ?: DEFAULT_SPEAKER_THRESHOLD }

    /** Optional cloud (D-005): off by default; also needs an API key in Secrets. */
    val cloudEnabled: Flow<Boolean> = context.dataStore.data.map { it[cloudKey] ?: false }
    /** All chat goes to the cloud when online (else only "think hard …"). */
    val cloudForChat: Flow<Boolean> = context.dataStore.data.map { it[cloudChatKey] ?: false }
    /** Privacy (PLAN 14.3): remembered facts are sent only if this is on. Notes, notifications, screen text never are. */
    val cloudSendFacts: Flow<Boolean> = context.dataStore.data.map { it[cloudFactsKey] ?: false }

    suspend fun setCloudEnabled(v: Boolean) = context.dataStore.edit { it[cloudKey] = v }
    suspend fun setCloudForChat(v: Boolean) = context.dataStore.edit { it[cloudChatKey] = v }
    suspend fun setCloudSendFacts(v: Boolean) = context.dataStore.edit { it[cloudFactsKey] = v }
    suspend fun setFollowUp(v: Boolean) = context.dataStore.edit { it[followUpKey] = v }
    suspend fun setAutoClearNotifications(v: Boolean) = context.dataStore.edit { it[autoClearKey] = v }
    suspend fun setHomeCity(v: String) = context.dataStore.edit { it[cityKey] = v }
    suspend fun setShowPanel(v: Boolean) = context.dataStore.edit { it[panelKey] = v }
    suspend fun setVoiceId(v: Boolean) = context.dataStore.edit { it[voiceIdKey] = v }
    suspend fun setSpeakerThreshold(v: Float) = context.dataStore.edit { it[speakerThresholdKey] = v }
    suspend fun setWakeThreshold(value: Float) = context.dataStore.edit { it[thresholdKey] = value }
    suspend fun setListeningEnabled(value: Boolean) = context.dataStore.edit { it[enabledKey] = value }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_SPEAKER_THRESHOLD = 0.5f
    }
}
