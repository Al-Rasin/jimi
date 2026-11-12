package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.CameraText
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CameraRuleTest {
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)
    private fun c(tool: String, vararg args: Pair<String, String>) = ToolCall(tool, args.toMap())

    @Test fun photoRules() {
        assertEquals(c("take_photo", "mode" to "photo"), m("take a photo"))
        assertEquals(c("take_photo", "mode" to "photo"), m("Take a picture."))
        assertEquals(c("take_photo", "mode" to "selfie"), m("take a selfie"))
        assertEquals(c("take_photo", "mode" to "selfie", "delay" to "5"), m("take a selfie in 5 seconds"))
        assertEquals(c("take_photo", "mode" to "video"), m("record a video"))
        assertEquals(c("take_photo", "mode" to "video"), m("take a video"))
        assertEquals(c("take_photo", "mode" to "stop"), m("stop recording"))
    }

    @Test fun qrRules() {
        assertEquals(c("scan_qr", "source" to "camera"), m("scan a QR code"))
        assertEquals(c("scan_qr", "source" to "camera"), m("scan barcode"))
        assertEquals(c("scan_qr", "source" to "screen"), m("scan the qr code on the screen"))
        assertEquals(c("open_qr_link"), m("open the link"))
    }

    @Test fun delays() {
        assertEquals(3, CameraText.delayFor("photo", null))
        assertEquals(0, CameraText.delayFor("video", null))
        assertEquals(10, CameraText.delayFor("selfie", "60"))
        assertEquals(listOf("3", "2", "1"), CameraText.countdown(3))
        assertTrue(CameraText.countdown(0).isEmpty())
    }

    @Test fun newestFile() {
        assertEquals("IMG_1.jpg", CameraText.newest(".pending-x.jpg\nIMG_1.jpg\nIMG_0.jpg"))
        assertNull(CameraText.newest(""))
    }

    @Test fun describeCodes() {
        assertEquals("It's a link to example.com. Say 'open the link' to open it.", CameraText.describe("https://www.example.com/a?b=1"))
        val wifi = CameraText.describe("WIFI:T:WPA;S:Home Net;P:secret123;;")
        assertEquals("It's a Wi-Fi code for the network Home Net.", wifi)
        assertFalse("secret123" in wifi)
        assertEquals("It's a product barcode: 8901234567890.", CameraText.describe("8901234567890"))
        assertEquals("It's a contact card for Sam.", CameraText.describe("BEGIN:VCARD\nFN:Sam\nEND:VCARD"))
        assertEquals("The code says: hello world", CameraText.describe("hello\nworld"))
        assertEquals("https://www.x.org", CameraText.link("www.x.org"))
        assertNull(CameraText.link("just text"))
    }
}
