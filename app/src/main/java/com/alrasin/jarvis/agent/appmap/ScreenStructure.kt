package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.agent.AgentSafety

// Regexes built once, not on every call (ROADMAP §L.4).
private val CLEAN_RE = Regex("[\\p{N}]+")
private val CLEAN_RE_2 = Regex("[^\\p{L}\\p{M} ]")
private val CLEAN_RE_3 = Regex("\\s+")

/**
 * Turns a screen into structure only (ROADMAP §J.2 privacy): tabs, drawer, menu, buttons,
 * switches, field hints. Never list rows, typed text, passwords, or a clickable parent's child
 * text (a chat header's child text is the contact's name). Pure — no Android types — so it is
 * unit-tested; the accessibility glue builds [RawNode]s.
 */
object ScreenStructure {

    /** One visible node as plain data. [inList] = inside a RecyclerView/ListView/GridView row. */
    data class RawNode(
        val resourceId: String = "",
        val text: String = "",
        val desc: String = "",
        val hint: String = "",
        val className: String = "",
        val parentClass: String = "",
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val password: Boolean = false,
        val checkable: Boolean = false,
        val inList: Boolean = false,
    )

    /** A structural element ready for [AppElement]. [label] is digit-free (badges like "Chats 3"). */
    data class Element(
        val label: String,
        val role: String,
        val resourceId: String,
        val className: String,
        val risk: String,
    )

    private const val MAX_LABEL = 30
    private const val MAX_WORDS = 4

    fun elements(nodes: List<RawNode>): List<Element> =
        nodes.mapNotNull(::element).distinctBy { it.role to it.label }

    /** Fingerprint parts for [AppFingerprint.compute]: "role:label". */
    fun parts(elements: List<Element>): List<String> = elements.map { "${it.role}:${it.label}" }

    fun element(n: RawNode): Element? {
        if (n.inList) return null
        if (!n.clickable && !n.editable) return null
        val label = label(n) ?: return null
        val role = role(n)
        return Element(
            label = label, role = role, resourceId = n.resourceId, className = n.className,
            risk = AgentSafety.riskLevel(label, n.password),
        )
    }

    /** Field → hint or id only (never its typed text). Others → desc, own text, then id name. */
    internal fun label(n: RawNode): String? {
        val idName = n.resourceId.substringAfter(":id/", "").replace('_', ' ')
        val raw = if (n.editable || n.password) {
            n.hint.ifBlank { idName }
        } else {
            n.desc.ifBlank { n.text }.ifBlank { idName }
        }
        val clean = clean(raw)
        if (clean.isEmpty() || clean.length > MAX_LABEL || clean.split(' ').size > MAX_WORDS) return null
        return clean
    }

    /** Lowercase, digits and punctuation out, so "Chats, 3 unread" and "Chats, 5 unread" match. */
    internal fun clean(s: String): String =
        s.lowercase().replace(CLEAN_RE, " ").replace(CLEAN_RE_2, " ")
            .replace(CLEAN_RE_3, " ").trim()

    internal fun role(n: RawNode): String {
        val cls = n.className.substringAfterLast('.')
        val parent = n.parentClass.substringAfterLast('.')
        val id = n.resourceId.substringAfter(":id/", "").lowercase()
        return when {
            n.editable -> "field"
            n.checkable || cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("Toggle") -> "switch"
            cls.contains("Tab") || parent.contains("Tab") || parent.contains("BottomNavigation") ||
                parent.contains("NavigationBar") || id.contains("tab") || id.contains("bottom_nav") -> "tab"
            id.contains("drawer") || id.contains("nav_view") || parent.contains("NavigationView") -> "drawer"
            n.desc.equals("More options", ignoreCase = true) || id.contains("overflow") || id.contains("menu") -> "menu"
            else -> "button"
        }
    }
}
