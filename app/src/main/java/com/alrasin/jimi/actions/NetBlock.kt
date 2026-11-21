package com.alrasin.jimi.actions

import android.content.Context
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.core.Risk
import com.alrasin.jimi.core.Tool
import com.alrasin.jimi.core.ToolResult

/**
 * Cuts Wi-Fi + mobile data for chosen apps (D-084): root iptables/ip6tables REJECT by app UID in our own
 * chain. Rules are lost on reboot, so the boot receiver and the core service re-apply the saved list (D-101).
 */
object NetBlock {
    const val CHAIN = "jimi_netblock"

    /** Settings toggles: one switch can cover the normal + lite/business app. */
    val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")
    val MESSENGER = listOf("com.facebook.orca", "com.facebook.mlite")

    /** Owner (0), MIUI Second Space (10, 11) and MIUI Dual apps (999): each copy of an app has its own UID. */
    private val USERS = listOf(0, 10, 11, 999)
    private const val PER_USER = 100_000

    /** Every UID the app can run as on this phone; unused UIDs in a rule do no harm. */
    fun allUserUids(uid: Int): List<Int> = USERS.map { it * PER_USER + uid % PER_USER }

    /** Shell lines that make the chain hold exactly [uids] (pure, unit-tested). */
    fun commands(uids: Collection<Int>): List<String> = listOf("iptables", "ip6tables").flatMap { t ->
        listOf(
            "$t -w -N $CHAIN 2>/dev/null; $t -w -F $CHAIN",
            "$t -w -C OUTPUT -j $CHAIN 2>/dev/null || $t -w -I OUTPUT 1 -j $CHAIN",
        ) + uids.distinct().sorted().map { "$t -w -A $CHAIN -m owner --uid-owner $it ! -o lo -j REJECT" }
    }

    fun uidOf(context: Context, pkg: String): Int? =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()

    /** Make the firewall match [pkgs]; apps not installed are skipped. */
    suspend fun apply(context: Context, pkgs: Set<String>): Boolean {
        val uids = pkgs.mapNotNull { uidOf(context, it) }.flatMap(::allUserUids)
        val r = RootShell.run(*commands(uids).toTypedArray(), timeoutMs = 10_000)
        JimiLog.i("net block: ${pkgs.size} apps, ${uids.size} uids → ${if (r.ok) "ok" else "fail"}")
        return r.ok
    }

    /** "messenger" → its packages; else the launcher app the name matches. */
    fun packagesFor(name: String, apps: AppList): Pair<String, List<String>>? {
        val n = name.lowercase().trim().removePrefix("the ")
        return when {
            n.startsWith("whats") -> "WhatsApp" to WHATSAPP
            n.startsWith("messenger") || n == "facebook messenger" -> "Messenger" to MESSENGER
            else -> apps.find(name)?.let { it.label to listOf(it.pkg) }
        }
    }
}

/** [blocked]/[save] read and write the saved list in Settings. */
fun netBlockTool(
    context: Context,
    apps: AppList,
    blocked: suspend () -> Set<String>,
    save: suspend (Set<String>) -> Unit,
): Tool = SimpleTool(
    "app_internet",
    "Turn an app's internet (Wi-Fi and mobile data) off or back on, e.g. stop Messenger internet",
    listOf(arg("app", "app name, e.g. messenger, whatsapp"), arg("state", "off or on", choices = listOf("off", "on"))),
    risk = Risk.SAFE,
) { c, _ ->
    val name = c.require("app", "Which app?")
    val (label, pkgs) = NetBlock.packagesFor(name, apps) ?: return@SimpleTool ToolResult.fail("I can't find $name.")
    val off = c.arg("state")?.lowercase() != "on"
    val now = blocked().let { if (off) it + pkgs else it - pkgs.toSet() }
    save(now)
    if (!NetBlock.apply(context, now)) return@SimpleTool ToolResult.fail("Could not change $label internet. Is root on?")
    ToolResult.ok(if (off) "$label internet is off." else "$label internet is back on.")
}
