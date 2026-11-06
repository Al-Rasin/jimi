package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.actions.PhoneProfile
import com.alrasin.jarvis.agent.AgentSafety
import com.alrasin.jarvis.agent.appmap.ScreenStructure.Element
import com.alrasin.jarvis.agent.appmap.ScreenStructure.RawNode

/**
 * What the app explorer may tap (ROADMAP §J.5). Pure, so it is unit-tested. Only tabs, drawer,
 * menu, More/settings buttons and plain rows on settings/menu screens. Never fields, switches or
 * anything with a risky word — those are saved with risk "high" instead.
 */
object ExplorePolicy {
    const val MAX_DEPTH = 3
    const val MAX_TAPS = 40
    const val MAX_MS = 180_000L
    private const val MAX_TARGETS = 10
    private const val MAX_ROWS = 12

    /** [raw] is the on-screen text, used to find the node again (the label is cleaned). */
    data class Target(val element: Element, val raw: String)

    data class Plan(val targets: List<Target>, val risky: List<Element>)

    private val neverTap = Regex(
        "(?i)\\b(pay|payment|buy|send|post|share|call|delete|remove|log ?out|log ?off|sign ?out|follow|like|" +
            "subscribe|install|uninstall|allow|accept|ok|okay|reset|clear|erase|block|report|exit|download|update)\\b",
    )
    private val openButton = Regex("(?i)^(more|more options|settings|menu|options|open navigation drawer|navigation drawer|me|my|profile)$")
    private val roleOrder = listOf("tab", "drawer", "menu", "button", "row")

    fun risky(label: String): Boolean =
        neverTap.containsMatchIn(label) || AgentSafety.riskLevel(label) != "low"

    fun plan(nodes: List<RawNode>, windowClass: String, pkg: String = ""): Plan {
        val risky = ArrayList<Element>()
        val safe = ArrayList<Target>()
        val ids = nodes.groupingBy { it.resourceId }.eachCount()
        // Chat rows open real chats (and can mark them read); tabs and menus are enough to learn a chat app (§M.11).
        val rowsAllowed = pkg !in CHAT_APPS && (settingsScreen(nodes, windowClass) || menuScreen(nodes, windowClass))
        for (n in nodes) {
            if (n.editable || n.password || n.checkable) continue
            val target = (if (n.inList) row(n, rowsAllowed) else structural(n, ids)) ?: continue
            if (risky(target.element.label) || risky(target.raw)) {
                risky += target.element.copy(risk = "high")
            } else if (target.element.role in roleOrder) {
                safe += target
            }
        }
        val unique = safe.distinctBy { it.element.label }
        val ordered = unique.sortedBy { roleOrder.indexOf(it.element.role) }
        val rows = ordered.filter { it.element.role == "row" }.take(MAX_ROWS)
        return Plan(ordered.filter { it.element.role != "row" }.take(MAX_TARGETS) + rows, risky.distinctBy { it.label })
    }

    private fun structural(n: RawNode, ids: Map<String, Int>): Target? {
        val e = ScreenStructure.element(n) ?: return plainLabel(n)
        val role = when {
            e.role == "button" && e.label.contains("drawer") -> "drawer"
            e.role == "button" && !openButton.matches(e.label) -> "skip"
            else -> e.role
        }
        // A shared id (every tab "tab_title") would find the wrong node; text/desc find it then.
        val id = if ((ids[n.resourceId] ?: 0) == 1) n.resourceId else ""
        return Target(e.copy(role = role, resourceId = id), n.desc.ifBlank { n.text })
    }

    /** A plain "Settings" / "More" text inside a clickable row with no text of its own (Mi Remote's "my" page). */
    private fun plainLabel(n: RawNode): Target? {
        if (n.clickable || n.text.isBlank()) return null
        val label = ScreenStructure.clean(n.text).takeIf { openButton.matches(it) } ?: return null
        return Target(Element(label, "button", "", n.className, "low"), n.text)
    }

    /** Rows only on settings or menu screens: their text is structure ("Privacy"), not user data. */
    private fun row(n: RawNode, allowed: Boolean): Target? {
        if (!allowed || n.text.isBlank()) return null
        val label = ScreenStructure.label(n.copy(desc = "", resourceId = "")) ?: return null
        return Target(Element(label, "row", "", n.className, "low"), n.text)
    }

    internal fun settingsScreen(nodes: List<RawNode>, windowClass: String): Boolean =
        Regex("(?i)setting|preference").containsMatchIn(windowClass.substringAfterLast('.')) ||
            nodes.any { !it.inList && !it.clickable && ScreenStructure.clean(it.text) == "settings" }

    internal fun menuScreen(nodes: List<RawNode>, windowClass: String): Boolean =
        Regex("Popup|Menu|Drawer").containsMatchIn(windowClass.substringAfterLast('.')) ||
            nodes.any { n -> MENU_CLASSES.any { n.className.contains(it) || n.parentClass.contains(it) } }

    /** Never explore: money, calls, camera, Jimi itself, system UI, launcher. */
    fun skipPackage(pkg: String, ownPkg: String): Boolean =
        pkg.isBlank() || pkg == ownPkg || PhoneProfile.isPaymentPackage(pkg) || pkg in SKIP ||
            SKIP_PARTS.any { pkg.contains(it, ignoreCase = true) }

    /** Why the explorer must stop on this screen, or null to go on. */
    fun stopReason(pkg: String, nodes: List<RawNode>): String? = when {
        nodes.isEmpty() -> "secure"
        pkg in PERMISSION_PACKAGES -> "permission"
        nodes.any { it.password } -> "login"
        nodes.any { it.editable } && nodes.any { LOGIN.containsMatchIn("${it.text} ${it.desc} ${it.hint}") } -> "login"
        else -> null
    }

    private val LOGIN = Regex("(?i)\\b(log ?in|sign ?in|sign ?up|phone number|email)\\b")
    private val MENU_CLASSES = listOf("NavigationMenu", "DropDownListView", "MenuPopup", "PopupWindow")
    private val SKIP = setOf(
        "com.android.systemui", "com.android.dialer", "com.google.android.dialer", "com.android.contacts",
        "com.google.android.contacts", "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera",
        "com.miui.home", "com.android.launcher3", "com.android.incallui", "com.android.phone",
    )
    private val CHAT_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "com.facebook.mlite", "org.telegram.messenger",
        "com.google.android.apps.messaging", "com.android.mms",
    )
    private val SKIP_PARTS = listOf("launcher", "camera", "dialer")
    private val PERMISSION_PACKAGES = setOf(
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.lbe.security.miui",
    )
}
