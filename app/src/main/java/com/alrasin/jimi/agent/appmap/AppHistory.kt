package com.alrasin.jimi.agent.appmap

/**
 * The last two apps the user had open ("switch to the last app"). Fed by [AppWatcher] on window
 * changes; only real apps count (launcher, system UI, keyboard and Jimi itself are skipped by the caller).
 */
object AppHistory {
    @Volatile private var current: String? = null
    @Volatile private var previous: String? = null

    fun seen(pkg: String) {
        if (pkg == current) return
        previous = current
        current = pkg
    }

    /** The app to go back to: the one before [foreground], or the newest if the user is not in an app now. */
    fun lastApp(foreground: String?): String? = when (foreground) {
        null -> current
        current -> previous
        else -> current
    }

    internal fun reset() {
        current = null
        previous = null
    }
}
