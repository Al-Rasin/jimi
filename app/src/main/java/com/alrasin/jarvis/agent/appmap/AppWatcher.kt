package com.alrasin.jarvis.agent.appmap

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.core.isLocked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Accessibility glue for [AppLearner] (ROADMAP §J.2). Window change or user click → wait for the
 * screen to settle → one snapshot. Rapid events are debounced, so at most one snapshot per change.
 */
class AppWatcher(private val service: AccessibilityService, private val learner: AppLearner) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val windowClass = ConcurrentHashMap<String, String>()
    private val snapshot = Runnable { snapshot() }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.className?.toString()?.takeIf { it.isNotBlank() }?.let { windowClass[pkg] = it }
                schedule()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!watching(pkg)) return
                val node = event.source?.let(::rawNode) ?: return
                scope.launch { learner.onClick(pkg, node) }
                schedule()
            }
        }
    }

    /** Fingerprint of the screen shown now, built like the learner's (for the map planner, §J.4). */
    fun liveScreen(): LiveScreen? {
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val nodes = ArrayList<ScreenStructure.RawNode>()
        collect(root, parentClass = "", inList = false, nodes)
        val parts = ScreenStructure.parts(ScreenStructure.elements(nodes))
        return LiveScreen(pkg, AppFingerprint.compute(windowClass[pkg] ?: pkg, parts))
    }

    data class LiveScreen(val pkg: String, val fingerprint: String)

    fun stop() {
        main.removeCallbacks(snapshot)
        scope.cancel()
    }

    private fun schedule() {
        main.removeCallbacks(snapshot)
        main.postDelayed(snapshot, SETTLE_MS)
    }

    private fun snapshot() {
        val root = service.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!watching(pkg)) return
        val nodes = ArrayList<ScreenStructure.RawNode>()
        collect(root, parentClass = "", inList = false, nodes)
        val cls = windowClass[pkg] ?: pkg
        scope.launch { learner.onScreen(pkg, cls, nodes) }
    }

    private fun collect(n: AccessibilityNodeInfo?, parentClass: String, inList: Boolean, out: MutableList<ScreenStructure.RawNode>) {
        if (n == null || out.size >= MAX_NODES) return
        if (n.isVisibleToUser) out += rawNode(n, parentClass, inList)
        val cls = n.className?.toString().orEmpty()
        val childInList = inList || LIST_CLASSES.any { cls.endsWith(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), cls, childInList, out)
    }

    private fun rawNode(n: AccessibilityNodeInfo): ScreenStructure.RawNode {
        var p = n.parent
        var inList = false
        while (p != null && !inList) {
            inList = LIST_CLASSES.any { p!!.className?.toString().orEmpty().endsWith(it) }
            p = p.parent
        }
        return rawNode(n, n.parent?.className?.toString().orEmpty(), inList)
    }

    private fun rawNode(n: AccessibilityNodeInfo, parentClass: String, inList: Boolean) = ScreenStructure.RawNode(
        resourceId = n.viewIdResourceName.orEmpty(),
        // Editable text is the user's typing — never read it (ScreenStructure ignores it too).
        text = if (n.isEditable || n.isPassword) "" else n.text?.toString().orEmpty(),
        desc = n.contentDescription?.toString().orEmpty(),
        hint = n.hintText?.toString().orEmpty(),
        className = n.className?.toString().orEmpty(),
        parentClass = parentClass,
        clickable = n.isClickable,
        editable = n.isEditable,
        password = n.isPassword,
        checkable = n.isCheckable,
        inList = inList,
    )

    private fun watching(pkg: String): Boolean {
        val keyboard = Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return AppLearner.shouldWatch(pkg, service.packageName, keyboard, service.isLocked())
    }

    private companion object {
        const val SETTLE_MS = 700L
        const val MAX_NODES = 400
        val LIST_CLASSES = listOf("RecyclerView", "ListView", "GridView")
    }
}
