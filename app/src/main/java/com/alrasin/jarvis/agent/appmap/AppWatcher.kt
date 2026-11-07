package com.alrasin.jarvis.agent.appmap

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.agent.TeachEvent
import com.alrasin.jarvis.agent.TeachNode
import com.alrasin.jarvis.agent.TeachSession
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

    /** Set by the service; while teaching, user taps and typing are passed on (ROADMAP §H.6). */
    var teach: TeachSession? = null

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        teach?.takeIf { it.active }?.let { t -> runCatching { teachEvent(event, pkg) }.getOrNull()?.let(t::onEvent) }
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
        return LiveScreen(pkg, AppFingerprint.compute(windowClass[pkg] ?: pkg, parts), texts(pkg, root))
    }

    /** [texts] = every visible label in all the app's windows, cleaned; card screens match on these. */
    data class LiveScreen(val pkg: String, val fingerprint: String, val texts: Set<String> = emptySet())

    /** Popup menus and tooltips are their own windows, often not the active one, so read every window of [pkg]. */
    private fun texts(pkg: String, active: AccessibilityNodeInfo): Set<String> {
        val roots = runCatching { service.windows }.getOrDefault(emptyList()).sortedByDescending { it.layer }
            .mapNotNull { w -> w.root?.takeIf { it.packageName?.toString() == pkg } }.ifEmpty { listOf(active) }
        val nodes = ArrayList<ScreenStructure.RawNode>()
        roots.forEach { collect(it, parentClass = "", inList = false, nodes) }
        return nodes.filterNot { it.editable || it.password }
            .flatMap { listOf(it.desc, it.text) }.map(ScreenStructure::clean).filter { it.isNotEmpty() }.toSet()
    }

    /** Raw nodes of [root] for the app explorer (§J.5). */
    fun liveNodes(root: AccessibilityNodeInfo): LiveNodes? {
        val pkg = root.packageName?.toString() ?: return null
        val nodes = ArrayList<ScreenStructure.RawNode>()
        collect(root, parentClass = "", inList = false, nodes)
        return LiveNodes(pkg, windowClass[pkg] ?: pkg, nodes)
    }

    data class LiveNodes(val pkg: String, val windowClass: String, val nodes: List<ScreenStructure.RawNode>)

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

    private fun teachEvent(event: AccessibilityEvent, pkg: String): TeachEvent? {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED && event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return null
        if (!watching(pkg)) return null
        val n = event.source ?: return null
        val raw = rawNode(n)
        val node = TeachNode(
            id = raw.resourceId, text = raw.text.ifBlank { if (raw.editable) "" else childLabel(n) }, desc = raw.desc,
            hint = raw.hint, className = raw.className, editable = raw.editable, password = raw.password, inList = raw.inList,
        )
        return if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) TeachEvent.Click(pkg, node)
        else if (raw.editable && !raw.password) TeachEvent.Typed(pkg, node, event.text.joinToString(" ").trim())
        else null
    }

    /** A clickable row often has no text itself; its first labelled child names it. */
    private fun childLabel(n: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 3) return ""
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.isEditable || c.isPassword) continue
            val own = c.text?.toString()?.trim().orEmpty().ifBlank { c.contentDescription?.toString()?.trim().orEmpty() }
            if (own.isNotBlank()) return own.take(80)
            childLabel(c, depth + 1).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
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
