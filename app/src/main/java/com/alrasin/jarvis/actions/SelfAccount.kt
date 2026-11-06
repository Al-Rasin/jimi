package com.alrasin.jarvis.actions

/**
 * The phone owner's own chat ("send a WhatsApp to myself"). WhatsApp: the logged-in number, read with root from
 * WhatsApp's settings. Messenger: the logged-in account id, from a Messenger settings file name.
 * Nothing is stored; values are read when needed and never logged.
 */
internal object SelfAccount {
    private val SELF = Regex(
        "^(?:me|myself|my ?self|self|yourself|my own|message yourself|" +
            "my (?:own )?(?:number|account|chat|inbox|whats ?app|messenger)(?: (?:number|account|chat))?)$",
    )

    fun isSelf(who: String?): Boolean = who != null && SELF.matches(who.trim().trimEnd('.', '!', '?').lowercase())

    /** "+<country><number>" of the WhatsApp account on this phone, or null. */
    suspend fun whatsappNumber(): String? {
        val xml = RootShell.run("cat /data/data/com.whatsapp/shared_prefs/com.whatsapp_preferences_light.xml", log = false)
            .takeIf { it.ok }?.out ?: return null
        return whatsappNumber(xml)
    }

    internal fun whatsappNumber(xml: String): String? {
        fun pref(name: String) = Regex("name=\"$name\">(\\d+)<").find(xml)?.groupValues?.get(1)
        val cc = pref("cc") ?: return null
        val ph = pref("ph") ?: return null
        return "+$cc$ph"
    }

    /** Messenger account id, from settings file names like "..._100001234567.xml". */
    suspend fun messengerId(): String? {
        val ls = RootShell.run("ls /data/data/com.facebook.orca/shared_prefs/", log = false).takeIf { it.ok }?.out ?: return null
        return messengerId(ls.lines())
    }

    internal fun messengerId(files: List<String>): String? = files.firstNotNullOfOrNull {
        Regex("_(\\d{8,20})\\.xml$").find(it.trim())?.groupValues?.get(1)
    }
}
