package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockPackagePickerTest {
    @Test fun thirdPartyFirstDoesNotOverrideMiuiClock() {
        val handlers = listOf(
            ClockHandler("theoaktroop.appoframadan", false),
            ClockHandler("com.android.deskclock", true),
        )
        assertEquals("com.android.deskclock", pickClockPackage(handlers))
        assertEquals("com.android.deskclock", pickClockPackage(handlers, "theoaktroop.appoframadan"))
    }

    @Test fun knownClocksFollowPreferenceOrderRatherThanHandlerOrder() {
        val packages = listOf(
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.coloros.alarmclock",
            "com.miui.clock",
        )
        packages.indices.forEach { index ->
            val available = packages.drop(index).reversed().map { ClockHandler(it, true) }
            assertEquals(packages[index], pickClockPackage(available))
        }
    }

    @Test fun absentKnownClockIsNeverSelected() {
        assertEquals("com.vendor.clock", pickClockPackage(listOf(
            ClockHandler("com.vendor.clock", true),
        ), "com.android.deskclock"))
    }

    @Test fun validThirdPartyDefaultWinsBeforeSystemFallback() {
        assertEquals("com.example.clock", pickClockPackage(listOf(
            ClockHandler("com.vendor.clock", true),
            ClockHandler("com.example.clock", false),
        ), "com.example.clock"))
    }

    @Test fun resolverDefaultOutsideHandlersIsIgnored() {
        assertEquals("com.vendor.clock", pickClockPackage(listOf(
            ClockHandler("com.example.assistant", false),
            ClockHandler("com.vendor.clock", true),
        ), "android"))
    }

    @Test fun firstSystemHandlerIsFallback() {
        assertEquals("com.vendor.first", pickClockPackage(listOf(
            ClockHandler("com.example.assistant", false),
            ClockHandler("com.vendor.first", true),
            ClockHandler("com.vendor.second", true),
        )))
    }

    @Test fun thirdPartyHandlersWithoutDefaultLeaveChoiceToAndroid() {
        assertNull(pickClockPackage(listOf(
            ClockHandler("com.example.first", false),
            ClockHandler("com.example.second", false),
        )))
    }

    @Test fun emptyHandlersLeaveChoiceToAndroid() {
        assertNull(pickClockPackage(emptyList()))
        assertNull(pickClockPackage(emptyList(), "com.android.deskclock"))
    }
}
