package com.alrasin.jimi.service

import android.content.Context
import com.alrasin.jimi.actions.RootShell
import com.alrasin.jimi.core.JimiLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Root fixes that keep the always-on service alive on MIUI. */
object ProcessGuard {
    /** -1000 = never kill; -800 keeps us above ordinary apps but still killable if the phone is desperate. */
    private const val OOM_ADJ = -800

    /**
     * MIUI's low-memory killer took the service down when another app opened (signal 9 on the phone).
     * With root, lower our oom_score_adj (and the Google voice's). Android rewrites it when the process state changes, so repeat.
     */
    suspend fun run(context: Context) {
        if (!RootShell.available) return
        val path = "/proc/${android.os.Process.myPid()}/oom_score_adj"
        while (currentCoroutineContext().isActive) {
            RootShell.run("[ \$(cat $path) -gt $OOM_ADJ ] && echo $OOM_ADJ > $path || true")
            // The Google voice runs in its own process; when MIUI killed it mid-answer, phrases timed out and were lost.
            RootShell.run("for p in \$(pidof com.google.android.tts); do f=/proc/\$p/oom_score_adj; [ \$(cat \$f) -gt $OOM_ADJ ] && echo $OOM_ADJ > \$f; done; true")
            keepOverlayAllowed(context)
            delay(30_000)
        }
    }

    /**
     * MIUI sometimes turns "display over other apps" off (seen 2026-10-23); then Android blocks the
     * wake panel from opening in the background. Root turns it back on.
     */
    private suspend fun keepOverlayAllowed(context: Context) {
        keepMiuiPopupsAllowed(context.packageName)
        if (android.provider.Settings.canDrawOverlays(context)) return
        JimiLog.i("overlay permission lost; granting again")
        val pkg = context.packageName
        RootShell.run("appops set $pkg SYSTEM_ALERT_WINDOW allow", "appops set --uid $pkg SYSTEM_ALERT_WINDOW allow")
    }

    /**
     * MIUI's own ops 10021 ("pop-up windows while running in background") and 10020 ("show on lock screen") were
     * "ignore" on 2026-11-20: the wake panel START was logged but MIUI dropped it, so "Hey Jimi" showed nothing.
     */
    private suspend fun keepMiuiPopupsAllowed(pkg: String) {
        val fixed = RootShell.run(
            "for op in 10020 10021; do appops get $pkg \$op 2>/dev/null | grep -q ignore && appops set $pkg \$op allow && echo \$op; done; true",
            log = false,
        ).out.trim()
        if (fixed.isNotEmpty()) JimiLog.i("MIUI pop-up permission was off (op ${fixed.replace("\n", ",")}); allowed again")
    }
}
