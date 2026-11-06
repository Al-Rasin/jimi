package com.alrasin.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.Fuzzy
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.RootInput
import com.alrasin.jarvis.actions.a11y.findAll
import com.alrasin.jarvis.actions.a11y.findFirst
import com.alrasin.jarvis.actions.a11y.replaceNodeText
import com.alrasin.jarvis.actions.a11y.scroll
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.pollUntil
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Screen reader + doer core (ROADMAP §H.3). Finds UI elements by ordered selectors, waits for
 * them, and does tap/type/enter/scroll with fallbacks. Later skills/recipes (§H.4-H.6) call into
 * this instead of each hand-rolling node lookups. Reuses pollUntil (core/Poll.kt), findAll/
 * findFirst/replaceNodeText/scroll (actions/a11y/NodeQuery.kt), Fuzzy.best (actions/Fuzzy.kt) and
 * RootInput (actions/RootShell.kt). Never shells out to `uiautomator dump` — that pauses our own
 * accessibility service; all reads go through the live AccessibilityNodeInfo tree.
 */
object UiQuery {

    /** One matching rule, tried in the order given to [find]. */
    sealed class Selector {
        data class Id(val resourceId: String) : Selector()
        data class Desc(val text: String) : Selector()
        data class Text(val text: String, val min: Double = 0.6) : Selector()
        data class Hint(val text: String) : Selector()
        data class ClassAt(val className: String, val position: Int) : Selector()
    }

    /** Plain-data view of one visible+enabled node, built for matching (and for unit tests). */
    data class Candidate(
        val resourceId: String = "",
        val text: String = "",
        val desc: String = "",
        val hint: String = "",
        val className: String = "",
        val position: Int = 0,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val node: AccessibilityNodeInfo? = null,
    )

    /** Builds the common id→desc→text→hint→class-at-position cascade for a single label. */
    fun forLabel(label: String, resourceId: String? = null, className: String? = null, position: Int? = null, min: Double = 0.6): List<Selector> =
        buildList {
            resourceId?.let { add(Selector.Id(it)) }
            add(Selector.Desc(label))
            add(Selector.Text(label, min))
            add(Selector.Hint(label))
            if (className != null && position != null) add(Selector.ClassAt(className, position))
        }

    /** English label → other-language aliases it should also match (ROADMAP §H.3: English + Bangla). */
    private val WORD_ALIASES: Map<String, List<String>> = mapOf(
        "search" to listOf("search", "খুঁজুন", "অনুসন্ধান", "সার্চ"),
        "next" to listOf("next", "পরবর্তী"),
        "done" to listOf("done", "সম্পন্ন"),
        "send" to listOf("send", "পাঠান"),
        "cancel" to listOf("cancel", "বাতিল"),
        "ok" to listOf("ok", "ঠিক আছে"),
        "back" to listOf("back", "পেছনে"),
        "close" to listOf("close", "বন্ধ"),
        "skip" to listOf("skip", "এড়িয়ে যান"),
        "not now" to listOf("not now", "এখন না"),
        "later" to listOf("later", "পরে"),
        "no thanks" to listOf("no thanks", "না ধন্যবাদ"),
        "allow" to listOf("allow", "অনুমতি দিন"),
        "settings" to listOf("settings", "সেটিংস"),
    )

    /** Harmless popups [dismissPopups] may auto-tap. English + Bangla. Never add a risky word here. */
    private val POPUP_DISMISS = listOf(
        "not now", "skip", "later", "no thanks", "close",
        "এখন না", "এড়িয়ে যান", "পরে", "না ধন্যবাদ", "বন্ধ করুন",
    )

    /**
     * Words [dismissPopups] must NEVER auto-tap, even if they show up on a "harmless" looking
     * dialog. HARD SAFETY RULE: NEVER auto-tap "Allow", "Pay", "Send", "Delete" or similar risky
     * words — these must never be in the auto-dismiss list, check twice.
     */
    private val NEVER_AUTOTAP = listOf(
        "allow", "pay", "send", "delete", "buy", "purchase", "confirm", "submit", "remove",
        "transfer", "book", "request", "accept", "install", "uninstall", "subscribe", "follow",
        "sign out", "log out", "logout", "call", "share", "post", "order",
    )

    init {
        check(POPUP_DISMISS.none { word -> NEVER_AUTOTAP.any { risky -> word.contains(risky, ignoreCase = true) } }) {
            "popup auto-dismiss list overlaps a risky word"
        }
    }

    // ---------------------------------------------------------------- Find

    /** Convenience: finds against the current foreground screen. */
    fun find(selectors: List<Selector>): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        return find(root, selectors)
    }

    fun find(root: AccessibilityNodeInfo, selectors: List<Selector>): AccessibilityNodeInfo? =
        matchCandidate(selectors, buildCandidates(root))?.node

    /** All visible+enabled nodes on the live screen, for self-heal scoring (§H.7). */
    fun candidates(): List<Candidate> =
        JarvisAccessibilityService.instance?.rootInActiveWindow?.let(::buildCandidates).orEmpty()

    private fun buildCandidates(root: AccessibilityNodeInfo): List<Candidate> {
        var i = 0
        return root.findAll(maxDepth = 40) { it.isVisibleToUser && it.isEnabled }.map { n ->
            Candidate(
                resourceId = n.viewIdResourceName.orEmpty(),
                text = n.text?.toString().orEmpty(),
                desc = n.contentDescription?.toString().orEmpty(),
                hint = n.hintText?.toString().orEmpty(),
                className = n.className?.toString().orEmpty(),
                position = i++,
                clickable = n.isClickable,
                editable = n.isEditable,
                node = n,
            )
        }
    }

    /** Pure matching logic: no AccessibilityNodeInfo needed, so it is unit-testable on fixtures. */
    fun matchCandidate(selectors: List<Selector>, candidates: List<Candidate>): Candidate? {
        for (sel in selectors) {
            val hits = when (sel) {
                is Selector.Id -> candidates.filter { sel.resourceId.isNotBlank() && it.resourceId == sel.resourceId }
                is Selector.Desc -> candidates.filter { matchesLabel(sel.text, it.desc) }
                is Selector.Text -> {
                    val exact = candidates.filter { matchesLabel(sel.text, it.text) || matchesLabel(sel.text, it.desc) }
                    exact.ifEmpty { Fuzzy.best(sel.text, candidates, { it.text.ifBlank { it.desc } }, sel.min)?.let(::listOf).orEmpty() }
                }
                is Selector.Hint -> candidates.filter { matchesLabel(sel.text, it.hint) }
                is Selector.ClassAt -> candidates.filter { it.className == sel.className && it.position == sel.position }
            }
            if (hits.isNotEmpty()) return hits.first()
        }
        return null
    }

    private fun matchesLabel(query: String, value: String): Boolean {
        if (value.isBlank() || query.isBlank()) return false
        if (value.equals(query, ignoreCase = true)) return true
        val aliases = WORD_ALIASES[query.trim().lowercase()] ?: return false
        return aliases.any { value.equals(it, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- Wait

    /** Polls for [selectors] on the live screen, up to [timeoutMs]. Built on core/Poll.kt's pollUntil. */
    suspend fun waitFor(selectors: List<Selector>, timeoutMs: Long = 4000, pauseMs: Long = 250): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        val tries = (timeoutMs / pauseMs).toInt().coerceAtLeast(1)
        pollUntil(tries, pauseMs) {
            val root = JarvisAccessibilityService.instance?.rootInActiveWindow
            found = root?.let { find(it, selectors) }
            found != null
        }
        return found
    }

    /** Waits until the screen stops changing (two equal reads in a row), like ScreenAgent's settle(). */
    suspend fun settle(service: JarvisAccessibilityService?, tries: Int = 8, pauseMs: Long = 500) {
        val svc = service ?: return
        var last = svc.screenText()
        pollUntil(tries, pauseMs, delayFirst = true) {
            val now = svc.screenText()
            (now == last && now.isNotEmpty()).also { if (!it) last = now }
        }
    }

    // ------------------------------------------------------------------ Do

    /** ACTION_CLICK → nearest clickable parent → gesture tap at bounds → root `input tap`. */
    suspend fun tap(node: AccessibilityNodeInfo): Boolean {
        if (node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isEnabled && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        val rect = Rect().also(node::getBoundsInScreen)
        val service = JarvisAccessibilityService.instance
        if (service != null && gestureTap(service, rect.centerX(), rect.centerY())) return true
        return RootInput.tap(rect.centerX(), rect.centerY()).ok
    }

    /** Single-point tap gesture via dispatchGesture. Instrumented-only: needs a live AccessibilityService. */
    private suspend fun gestureTap(service: AccessibilityService, x: Int, y: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    /** ACTION_SET_TEXT → clipboard paste → root `input text`. */
    suspend fun type(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isEnabled && node.isEditable && node.replaceNodeText(text)) return true
        if (pasteViaClipboard(context, node, text)) return true
        return RootInput.text(text).ok
    }

    private fun pasteViaClipboard(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("jarvis", text))
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** ACTION_IME_ENTER → root keyevent 66 (KEYCODE_ENTER). */
    suspend fun enter(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        return RootInput.key(66).ok
    }

    /** Scrolls (max 8 times) looking for [selectors]; stops early once the screen stops changing. */
    suspend fun scrollUntilFound(service: JarvisAccessibilityService, selectors: List<Selector>, maxScrolls: Int = 8): AccessibilityNodeInfo? {
        var lastText: String? = null
        repeat(maxScrolls) {
            val root = service.rootInActiveWindow ?: return null
            find(root, selectors)?.let { return it }
            val nowText = service.screenText()
            if (lastText == nowText) return null
            lastText = nowText
            val scrollable = root.findFirst { it.isScrollable && it.isVisibleToUser } ?: return null
            if (!scrollable.scroll(true)) return null
            delay(300)
        }
        val root = service.rootInActiveWindow ?: return null
        return find(root, selectors)
    }

    // --------------------------------------------------------- Popup guard

    /** Taps the first harmless popup button it finds ("Not now", "Skip", ...). Never taps a risky word. */
    fun dismissPopups(service: JarvisAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val hit = root.findAll(maxDepth = 20) { it.isVisibleToUser && it.isEnabled && it.isClickable }
            .firstOrNull { node -> isSafePopupDismiss(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty()) }
            ?: return false
        val tapped = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (tapped) JarvisLog.i("dismissed popup: ${hit.text ?: hit.contentDescription}")
        return tapped
    }

    /** Pure rule behind [dismissPopups], testable without a real node: safe to tap, never a risky word. */
    fun isSafePopupDismiss(text: String, desc: String): Boolean {
        val label = text.ifBlank { desc }.trim()
        if (label.isEmpty()) return false
        val isKnownSafe = POPUP_DISMISS.any { it.equals(label, ignoreCase = true) }
        val isRisky = NEVER_AUTOTAP.any { label.lowercase().contains(it) }
        return isKnownSafe && !isRisky
    }
}
