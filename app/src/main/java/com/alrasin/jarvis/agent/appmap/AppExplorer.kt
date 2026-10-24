package com.alrasin.jarvis.agent.appmap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.AppList
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.arg
import com.alrasin.jarvis.actions.a11y.findFirst
import com.alrasin.jarvis.agent.UiQuery
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.service.Notifications
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Learn this app" (ROADMAP §J.5): taps only tabs, drawer, menu, More and settings rows, presses
 * Back, never types. Depth ≤ 3, ≤ 40 taps, ≤ 3 min. Stops on user touch, screen off, login,
 * permission dialog, secure screen or app closed. Always ends with force-stop + home.
 * What it sees is saved by [AppLearner] like the user's own use, so app_go can use it.
 */
@Singleton
class AppExplorer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planner: Planner,
    private val learner: AppLearner,
) {
    data class Report(val app: String, val screens: Int, val taps: Int, val risky: Int, val stop: String) {
        fun say(): String {
            val why = when (stop) {
                "done" -> ""
                "touch" -> " Stopped because you touched the phone."
                "login" -> " Stopped at a login screen."
                "permission" -> " Stopped at a permission dialog."
                "secure" -> " Stopped: the screen is private."
                "taps", "time" -> " Stopped at my limit."
                else -> " Stopped: $stop."
            }
            return "Learned $app: $screens screens, $taps taps, skipped $risky risky buttons.$why"
        }
    }

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val busy: Boolean get() = running.get()

    /** Runs in the background; false when a run is already going. */
    fun start(pkg: String, apps: AppList, onDone: (Report) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch {
            try {
                onDone(run(pkg, apps))
            } finally {
                running.set(false)
            }
        }
        return true
    }

    /** Waits for the run (night worker). Null when a run is already going. */
    suspend fun explore(pkg: String, apps: AppList): Report? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            run(pkg, apps)
        } finally {
            running.set(false)
        }
    }

    private suspend fun run(pkg: String, apps: AppList): Report {
        val app = planner.forPackage(pkg, apps)
        val service = JarvisAccessibilityService.instance
            ?: return Report(app.name, 0, 0, 0, "accessibility is off").also { JarvisLog.i("explore $pkg: $it") }
        val s = Session(service, pkg, apps)
        JarvisLog.i("explore $pkg: start")
        try {
            val finished = withTimeoutOrNull(ExplorePolicy.MAX_MS + SAFETY_MS) {
                if (planner.openIfNeeded(service, pkg, apps)) s.visit(emptyList()) else s.stop = "couldn't open it"
            }
            if (finished == null) s.stop = s.stop ?: "time"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JarvisLog.e("explore $pkg failed", e)
            s.stop = "error"
        } finally {
            withContext(NonCancellable) {
                RootShell.run("am force-stop $pkg")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
        return Report(app.name, s.screens.size, s.taps, s.risky, s.stop ?: "done")
            .also { JarvisLog.i("explore $pkg: $it") }
    }

    private inner class Session(
        val service: JarvisAccessibilityService,
        val pkg: String,
        val apps: AppList,
    ) {
        val startedAt = System.currentTimeMillis()
        val screens = ArrayList<String>()
        var taps = 0
        var risky = 0
        var relaunches = 0
        var stop: String? = null

        /** Depth-first: learn this screen, then each safe target, coming back here between them. */
        suspend fun visit(path: List<ExplorePolicy.Target>) {
            val live = look() ?: return
            val fp = fingerprint(live)
            JarvisLog.i("explore $pkg: screen ${screens.size + 1} depth ${path.size}, taps $taps")
            screens += fp
            learner.onScreen(pkg, live.windowClass, live.nodes)
            val plan = ExplorePolicy.plan(live.nodes, live.windowClass)
            learner.noteRisky(pkg, plan.risky)
            risky += plan.risky.size
            if (path.size >= ExplorePolicy.MAX_DEPTH) return
            for (t in plan.targets) {
                if (shouldStop() || !ensureAt(fp, path)) return
                if (!tap(t)) continue
                var now = afterTap() ?: if (stop != null) return else continue
                if (AppFingerprint.sameScreen(fingerprint(now), fp)) {
                    // A new activity can take longer than settle() to draw; look once more.
                    delay(SLOW_SCREEN_MS)
                    now = afterTap() ?: if (stop != null) return else continue
                }
                val nowFp = fingerprint(now)
                if (AppFingerprint.sameScreen(nowFp, fp) || screens.any { AppFingerprint.sameScreen(it, nowFp) }) continue
                visit(path + t)
                if (stop != null) return
            }
        }

        /** Find [t] again on the live screen and tap it, if it is still safe. */
        private suspend fun tap(t: ExplorePolicy.Target): Boolean {
            val root = service.agentRoot() ?: return false
            val selectors = buildList {
                if (t.element.resourceId.isNotBlank()) add(UiQuery.Selector.Id(t.element.resourceId))
                add(UiQuery.Selector.Desc(t.raw))
                add(UiQuery.Selector.Text(t.raw, min = FIND_MIN))
            }
            val node = UiQuery.find(root, selectors) ?: return false
            if (!safe(node)) return false
            learner.onOwnTap(pkg, t.element)
            if (!UiQuery.tap(node)) return false
            taps++
            UiQuery.settle(service, tries = 4, pauseMs = 300)
            return true
        }

        /** After a tap: still in the app → its screen. Another app opened → Back once, else stop. */
        private suspend fun afterTap(): AppWatcher.LiveNodes? {
            look()?.let { return it }
            if (stop != null) return null
            back()
            if (foreground() != pkg) stop = "left the app"
            return null
        }

        /** Get back to screen [fp]: Back up to twice, else reopen the app and replay [path]. */
        private suspend fun ensureAt(fp: String, path: List<ExplorePolicy.Target>): Boolean {
            if (foreground() != pkg) {
                stop = "app closed"
                return false
            }
            if (at(fp)) return true
            repeat(2) {
                back()
                if (stop != null) return false
                if (at(fp)) return true
            }
            if (relaunches++ >= MAX_RELAUNCHES) return false
            RootShell.run("am force-stop $pkg")
            if (!planner.openIfNeeded(service, pkg, apps)) {
                stop = "app closed"
                return false
            }
            for (t in path) if (shouldStop() || !tap(t)) return false
            return at(fp)
        }

        private fun at(fp: String): Boolean {
            val live = service.liveNodes()?.takeIf { it.pkg == pkg } ?: return false
            return AppFingerprint.sameScreen(fingerprint(live), fp)
        }

        private suspend fun back() {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            UiQuery.settle(service, tries = 3, pauseMs = 300)
        }

        /** The app's live screen, or null (and [stop] set when it is a stop screen). */
        private suspend fun look(): AppWatcher.LiveNodes? {
            var live = service.liveNodes()
            // An empty tree for a moment is normal while a screen draws; only a lasting one means secure.
            repeat(2) {
                if (live?.nodes?.isNotEmpty() == true) return@repeat
                delay(EMPTY_RETRY_MS)
                live = service.liveNodes()
            }
            val l = live ?: return null
            ExplorePolicy.stopReason(l.pkg, l.nodes)?.let { if (l.pkg == pkg || it == "permission") stop = it }
            return l.takeIf { stop == null && it.pkg == pkg }
        }

        fun shouldStop(): Boolean {
            val now = System.currentTimeMillis()
            stop = stop ?: when {
                learner.userClickAt > startedAt -> "touch"
                !context.getSystemService(PowerManager::class.java).isInteractive -> "touch"
                taps >= ExplorePolicy.MAX_TAPS -> "taps"
                now - startedAt >= ExplorePolicy.MAX_MS -> "time"
                else -> null
            }
            return stop != null
        }

        private fun foreground(): String? = service.agentRoot()?.packageName?.toString()

        private fun fingerprint(live: AppWatcher.LiveNodes) =
            AppFingerprint.compute(live.windowClass, ScreenStructure.parts(ScreenStructure.elements(live.nodes)))
    }

    /** The node that really gets clicked holds no switch, field or risky word. */
    private fun safe(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val t = target ?: node
        if (t.isCheckable || t.isEditable) return false
        return t.findFirst(maxDepth = SAFE_DEPTH) { n ->
            n.isCheckable || n.isEditable || n.isPassword ||
                ExplorePolicy.risky("${n.text ?: ""} ${n.contentDescription ?: ""}")
        } == null
    }

    private companion object {
        const val SAFETY_MS = 30_000L
        const val MAX_RELAUNCHES = 5
        const val FIND_MIN = 0.85
        const val EMPTY_RETRY_MS = 600L
        const val SLOW_SCREEN_MS = 1000L
        const val SAFE_DEPTH = 6
    }
}

/** "learn this app" / "learn Mi Remote" (ROADMAP §J.5). Runs in the background; the result is a notification. */
fun learnTool(context: Context, explorer: AppExplorer, planner: Planner, apps: AppList): Tool = SimpleTool(
    "learn_app",
    "Explore an app safely (only tabs, menus and settings) so Jarvis learns its screens.",
    listOf(arg("app", "app name; empty = the app on screen", required = false)),
) { call, ctx ->
    val name = call.arg("app").orEmpty()
    val app = if (name.isBlank()) {
        JarvisAccessibilityService.instance?.agentRoot()?.packageName?.toString()?.let { planner.forPackage(it, apps) }
    } else {
        planner.resolve(name, apps)
    }
    when {
        ctx.locked -> ToolResult.fail("Unlock the phone first, then ask me to learn it.")
        JarvisAccessibilityService.instance == null -> ToolResult.fail("Turn on Jarvis accessibility first.")
        app == null -> ToolResult.fail(if (name.isBlank()) "Open the app first." else "I can't find an app called $name.")
        PhoneProfile.isPaymentPackage(app.pkg) || app.card?.readOnly == true ->
            ToolResult.fail("${app.name} is a payment app. I don't tap in it.")
        ExplorePolicy.skipPackage(app.pkg, context.packageName) -> ToolResult.fail("I don't explore ${app.name}.")
        explorer.busy -> ToolResult.fail("I'm already learning an app.")
        !explorer.start(app.pkg, apps) { Notifications.showReply(context, "learn ${app.name}", it.say()) } ->
            ToolResult.fail("I'm already learning an app.")
        else -> ToolResult.ok("Learning ${app.name} now. I'll stop if you touch the phone.")
    }
}
