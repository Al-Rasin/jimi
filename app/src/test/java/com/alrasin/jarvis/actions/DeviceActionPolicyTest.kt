package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionPolicyTest {
    @Test fun manufacturerSpecificSettingsStayOnCompatiblePhones() {
        assertTrue(DeviceActionPolicy.hasMiuiSettings("Xiaomi"))
        assertTrue(DeviceActionPolicy.hasMiuiSettings(" REDMI "))
        assertTrue(DeviceActionPolicy.hasMiuiSettings("POCO"))
        listOf("Google", "Samsung", "Motorola", "", "not-xiaomi").forEach {
            assertFalse(DeviceActionPolicy.hasMiuiSettings(it))
        }
    }

    @Test fun discoversVendorTorchNamesWithoutTouchingOtherLeds() {
        assertEquals(listOf("/sys/class/leds/flashlight", "/sys/class/leds/led:torch_0", "/sys/class/leds/torch-light0"),
            DeviceActionPolicy.torchDirectories(listOf(
                "/sys/class/leds/torch-light0", "/sys/class/leds/led:torch_0",
                "/sys/class/leds/red", "/sys/class/leds/lcd-backlight", "/sys/class/leds/flash0",
                "/sys/class/leds/flashlight", "/sys/class/leds/torch-light0",
            )))
    }

    @Test fun invalidPathsCannotBecomeShellCommands() {
        assertTrue(DeviceActionPolicy.torchDirectories(listOf(
            "/tmp/torch", "/sys/class/leds/torch/../red", "/sys/class/leds/torch; reboot",
            "/sys/class/leds/torch\$(id)", "Permission denied", "/sys/class/leds/",
        )).isEmpty())
    }

    @Test fun usesTheDriverBrightnessRangeAndRejectsUnknownRanges() {
        assertEquals(1, DeviceActionPolicy.torchBrightness("1\n"))
        assertEquals(255, DeviceActionPolicy.torchBrightness("255"))
        listOf("0", "-1", "", "255\nPermission denied", "2147483648").forEach {
            assertNull(DeviceActionPolicy.torchBrightness(it))
        }
    }
}
