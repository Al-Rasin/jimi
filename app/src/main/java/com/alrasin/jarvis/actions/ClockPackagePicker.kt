package com.alrasin.jarvis.actions

internal data class ClockHandler(val packageName: String, val systemApp: Boolean)

internal fun pickClockPackage(handlers: List<ClockHandler>, defaultPackage: String? = null): String? {
    val knownClocks = listOf(
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.oneplus.deskclock",
        "com.coloros.alarmclock",
        "com.miui.clock",
    )
    return knownClocks.firstOrNull { clock -> handlers.any { it.packageName == clock } }
        ?: defaultPackage?.takeIf { preferred -> handlers.any { it.packageName == preferred } }
        ?: handlers.firstOrNull { it.systemApp }?.packageName
}
