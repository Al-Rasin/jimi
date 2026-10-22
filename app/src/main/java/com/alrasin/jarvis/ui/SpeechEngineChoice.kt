package com.alrasin.jarvis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.SpeechEngineSettings
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.alrasin.jarvis.voice.SpeechEngine

@Composable
internal fun SpeechEngineSetting() {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { SpeechEngineSettings(context) }
    val selected by settings.engine.collectAsStateWithLifecycle(initialValue = SpeechEngine.JIMI_OFFLINE)
    val scope = rememberCoroutineScope()
    SpeechEngineChoice(selected) { scope.launch { settings.setEngine(it) } }
}

@Composable
fun SpeechEngineChoice(selected: SpeechEngine, onSelect: (SpeechEngine) -> Unit) {
    Column {
        Text("Speech engine")
        SpeechEngine.entries.forEach { engine ->
            Row(
                modifier = Modifier.clickable { onSelect(engine) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == engine, onClick = null)
                Text(engine.label)
            }
        }
    }
}
