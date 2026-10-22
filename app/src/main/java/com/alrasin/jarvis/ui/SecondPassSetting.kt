package com.alrasin.jarvis.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.Settings
import kotlinx.coroutines.launch

@Composable
internal fun SecondPassSetting() {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { Settings(context) }
    val enabled by settings.sttSecondPass.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Try second-pass speech recognition", Modifier.weight(1f))
        Switch(enabled, { scope.launch { settings.setSttSecondPass(it) } })
    }
    Text("Experimental; download Second-pass speech below. Keeps the original words if unavailable or slow. Restart listening to retry after a timeout.",
        style = MaterialTheme.typography.bodySmall)
}
