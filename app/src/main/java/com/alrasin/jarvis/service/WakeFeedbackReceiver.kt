package com.alrasin.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.WakeEventDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles ✓ / ✗ taps on the "Was that you?" notification. */
@AndroidEntryPoint
class WakeFeedbackReceiver : BroadcastReceiver() {
    @Inject lateinit var dao: WakeEventDao

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val verdict = intent.getStringExtra(EXTRA_VERDICT) ?: return
        if (id < 0) return
        Notifications.cancelWakeCheck(context, id)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WakeFeedback.setVerdict(context.applicationContext, dao, id, verdict)
                JarvisLog.i("wake #$id → $verdict")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_VERDICT = "verdict"
    }
}
