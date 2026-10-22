package com.alrasin.jarvis.actions

import org.junit.Assert.*
import org.junit.Test

class AppNameMatchTest {
    private val apps = listOf(
        AppList.App("YouTube", "com.google.android.youtube"),
        AppList.App("Gmail", "com.google.android.gm"),
        AppList.App("Chrome", "com.android.chrome"),
    )

    @Test fun exactAliasesAndCloseNamesMatch() {
        for (name in listOf("youtube", "you tube", "youtub", "com.google.android.youtube")) {
            assertEquals(name, "YouTube", AppList.matchName(name, apps)?.label)
        }
        assertEquals("Chrome", AppList.matchName("browser", apps)?.label)
    }

    @Test fun nonsenseAndPackageFragmentsDoNotMatch() {
        for (name in listOf("asium", "gm", "android", "", "what can you do")) {
            assertNull(name, AppList.matchName(name, apps))
        }
    }

    @Test fun ambiguousNearMatchesAreRejected() {
        val similar = listOf(AppList.App("Note One", "one"), AppList.App("Note Two", "two"))
        assertNull(AppList.matchName("note", similar))
    }
}
