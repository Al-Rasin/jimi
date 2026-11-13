package com.alrasin.jarvis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    ToggleRow(
        "Second-pass speech recognition", enabled,
        "Experimental. Keeps your words if it is slow.",
    ) { scope.launch { settings.setSttSecondPass(it) } }
}

@Composable
internal fun BanglaModeSetting() {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { Settings(context) }
    val enabled by settings.sttBangla.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    ToggleRow(
        "Bangla mode", enabled,
        "Speak Bangla or mixed. Needs the Bangla speech model. Slower.",
    ) { scope.launch { settings.setSttBangla(it) } }
}
