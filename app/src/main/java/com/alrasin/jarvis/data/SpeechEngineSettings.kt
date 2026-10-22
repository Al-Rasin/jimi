package com.alrasin.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alrasin.jarvis.voice.SpeechEngine
import kotlinx.coroutines.flow.map

private val Context.speechEngineStore by preferencesDataStore("speech_engine")

class SpeechEngineSettings(context: Context) {
    private val store = context.applicationContext.speechEngineStore
    private val key = stringPreferencesKey("engine")
    val engine = store.data.map { SpeechEngine.fromStored(it[key]) }

    suspend fun setEngine(engine: SpeechEngine) {
        store.edit { it[key] = engine.name }
    }
}
