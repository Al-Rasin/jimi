package com.alrasin.jimi.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.alrasin.jimi.core.JimiLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "call mom on speaker": once the dialed call is off-hook, turn the speaker on.
 * AudioManager works on some builds; otherwise the dialer's own Speaker button is tapped.
 * Runs in the background so the call reply is not held up.
 */
object CallSpeaker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun after(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!waitOffHook(app)) return@launch JimiLog.i("speaker: call never went off-hook")
            delay(1500)
            val audio = app.getSystemService(AudioManager::class.java)
            @Suppress("DEPRECATION")
            runCatching { audio.isSpeakerphoneOn = true }
            @Suppress("DEPRECATION")
            if (audio.isSpeakerphoneOn) return@launch JimiLog.i("speaker: on via audio")
            val ok = JimiAccessibilityService.instance?.clickText("Speaker") == true
            JimiLog.i("speaker: dialer button tapped=$ok")
        }
    }

    private suspend fun waitOffHook(app: Context): Boolean {
        if (app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            delay(4000)
            return true
        }
        val phone = app.getSystemService(TelephonyManager::class.java)
        repeat(30) {
            @Suppress("DEPRECATION")
            if (phone.callState == TelephonyManager.CALL_STATE_OFFHOOK) return true
            delay(200)
        }
        return false
    }
}
