package com.alrasin.jarvis.debug

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.alrasin.jarvis.brain.Assistant
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.SmokeSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@AndroidEntryPoint
class SmokeCommandService : Service() {
    @Inject lateinit var assistant: Assistant
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serial = Mutex()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("smoke", "Debug smoke test", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(8107, Notification.Builder(this, "smoke")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Jimi debug smoke test").setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        val request = SmokeRequest.parse(intent.action, mapOf(
            "id" to intent.getStringExtra("id"), "text" to intent.getStringExtra("text"),
            "read_only" to intent.getBooleanExtra("read_only", true),
        ))
        scope.launch {
            serial.withLock {
                val start = SystemClock.elapsedRealtime()
                val trace = SmokeSession(request.readOnly)
                val reply = try {
                    withTimeout(120_000) {
                        withContext(trace) {
                            assistant.handle(Assistant.Input(request.text, source = "typed",
                                locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
                                voiceOk = false)).text
                        }
                    }
                } catch (e: Exception) {
                    trace.ok = false
                    trace.route = "error"
                    e.message ?: e.javaClass.simpleName
                }
                JarvisLog.i("SMOKE " + trace.record(request.id, request.text, reply, SystemClock.elapsedRealtime() - start))
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
