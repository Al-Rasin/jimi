package com.alrasin.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    val wakeThreshold: Flow<Float> = context.dataStore.data.map { it[thresholdKey] ?: DEFAULT_THRESHOLD }
    val listeningEnabled: Flow<Boolean> = context.dataStore.data.map { it[enabledKey] ?: false }

    suspend fun setWakeThreshold(value: Float) = context.dataStore.edit { it[thresholdKey] = value }
    suspend fun setListeningEnabled(value: Boolean) = context.dataStore.edit { it[enabledKey] = value }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
    }
}
