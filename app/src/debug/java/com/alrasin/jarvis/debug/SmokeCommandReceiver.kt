package com.alrasin.jarvis.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.SmokeSession

class SmokeCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val request = runCatching {
            SmokeRequest.parse(intent.action, mapOf(
                "id" to extras?.get("id"), "text" to extras?.get("text"),
                "read_only" to extras?.get("read_only"),
            ))
        }.getOrElse {
            val trace = SmokeSession(true).apply { ok = false; route = "error" }
            JarvisLog.i("SMOKE " + trace.record((extras?.get("id") as? String).orEmpty().take(80), "", it.message.orEmpty(), 0))
            return
        }
        runCatching {
            context.startForegroundService(Intent(context, SmokeCommandService::class.java)
                .setAction(SmokeRequest.ACTION).putExtra("id", request.id)
                .putExtra("text", request.text).putExtra("read_only", request.readOnly))
        }.onFailure {
            val trace = SmokeSession(request.readOnly).apply { ok = false; route = "error" }
            JarvisLog.i("SMOKE " + trace.record(request.id, request.text, "Cannot start smoke service: ${it.message}", 0))
        }
    }
}
