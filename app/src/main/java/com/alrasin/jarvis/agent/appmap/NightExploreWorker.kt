package com.alrasin.jarvis.agent.appmap

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.ToolRegistry
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.isLocked
import com.alrasin.jarvis.data.Settings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Night learning (ROADMAP §J.5): only with the user's OK in Settings, 1–5 am, charging, screen off,
 * no call. Unlocks, explores up to 2 least-known apps (not again for 7 days), then locks.
 */
class NightExploreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun settings(): Settings
        fun explorer(): AppExplorer
        fun registry(): ToolRegistry
        fun planner(): Planner
        fun dao(): AppMapDao
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val deps = EntryPointAccessors.fromApplication(ctx, Deps::class.java)
        if (!deps.settings().nightExplore.first()) return Result.success()
        if (!NightPlan.nightHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) || !phoneIdle(ctx)) return Result.success()
        if (JarvisAccessibilityService.instance == null || deps.explorer().busy) return Result.success()

        val registry = deps.registry()
        val apps = registry.apps
        val now = System.currentTimeMillis()
        val explored = NightPlan.parse(deps.settings().nightExplored.first())
        val candidates = apps.all().map { it.pkg }.distinct().filter { pkg ->
            !ExplorePolicy.skipPackage(pkg, ctx.packageName) && !PhoneProfile.isPaymentPackage(pkg) &&
                deps.planner().forPackage(pkg, apps).card?.readOnly != true
        }.map { it to deps.dao().screensFor(it).size }
        val picks = NightPlan.pick(candidates, explored, now)
        if (picks.isEmpty()) return Result.success()

        val wake = screenWakeLock(ctx)
        try {
            if (ctx.isLocked()) {
                val r = registry.get("unlock_phone")?.run(ToolCall("unlock_phone"), ToolContext(ctx, "night learn", true))
                if (r?.ok != true) {
                    JarvisLog.i("night explore: unlock failed")
                    return Result.success()
                }
            }
            val done = explored.toMutableMap()
            for (pkg in picks) {
                val report = deps.explorer().explore(pkg, apps) ?: break
                done[pkg] = System.currentTimeMillis()
                JarvisLog.i("night explore: ${report.say()}")
                if (report.stop == "touch") break   // the user is awake — leave the phone to them
            }
            deps.settings().setNightExplored(NightPlan.format(done, now))
        } finally {
            wake.release()
            registry.get("lock_phone")?.run(ToolCall("lock_phone"), ToolContext(ctx, "night learn", false))
        }
        return Result.success()
    }

    /** Charging, screen off, no call or VoIP. */
    private fun phoneIdle(ctx: Context): Boolean {
        val charging = ctx.getSystemService(BatteryManager::class.java).isCharging
        val screenOff = !ctx.getSystemService(PowerManager::class.java).isInteractive
        val mode = ctx.getSystemService(AudioManager::class.java).mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        return charging && screenOff && !inCall
    }

    /** Explorer taps don't count as user activity, so keep the screen on for the run. */
    @Suppress("DEPRECATION")
    private fun screenWakeLock(ctx: Context): PowerManager.WakeLock =
        ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:night_explore")
            .apply { acquire(WAKE_MS) }

    companion object {
        private const val WORK_NAME = "night_explore"
        private const val WAKE_MS = 10 * 60 * 1000L

        /** Hourly check while charging; the worker itself checks the setting and the hour. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightExploreWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Pure parts of night learning, unit-tested. */
object NightPlan {
    const val PER_NIGHT = 2
    const val AGAIN_AFTER_MS = 7 * 24 * 60 * 60 * 1000L

    fun nightHour(hour: Int): Boolean = hour in 1..4

    /** Apps with the fewest learned screens first, skipping ones explored in the last 7 days. */
    fun pick(candidates: List<Pair<String, Int>>, explored: Map<String, Long>, now: Long, n: Int = PER_NIGHT): List<String> =
        candidates.filter { (pkg, _) -> now - (explored[pkg] ?: 0L) >= AGAIN_AFTER_MS }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(n)
            .map { it.first }

    /** "pkg=millis;pkg=millis" */
    fun parse(s: String): Map<String, Long> = s.split(';').mapNotNull { part ->
        val pkg = part.substringBefore('=', "").trim()
        val at = part.substringAfter('=', "").trim().toLongOrNull()
        if (pkg.isBlank() || at == null) null else pkg to at
    }.toMap()

    /** Drops entries older than 7 days so the setting stays small. */
    fun format(explored: Map<String, Long>, now: Long): String =
        explored.filter { now - it.value < AGAIN_AFTER_MS }.entries.joinToString(";") { "${it.key}=${it.value}" }
}
