package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.PdfText
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.Deflater

class FileSearchRuleTest {
    private val now = LocalDateTime.of(2026, 11, 12, 10, 0)
    private val today = now.toLocalDate()
    private fun m(text: String) = RuleMatcher.match(text, now)

    @Test fun photosByTime() {
        assertEquals(ToolCall("find_photos", mapOf("when" to "last week")), m("show photos from last week"))
        assertEquals("yesterday", m("yesterday's photos")?.arg("when"))
        assertEquals("march", m("show me my pictures from march")?.arg("when"))
    }

    @Test fun photosByPlace() {
        assertEquals(ToolCall("find_photos", mapOf("place" to "coxs bazar")), m("photos from cox's bazar"))
        val both = m("show photos from cox's bazar last month")
        assertEquals("find_photos", both?.tool)
        assertEquals("last month", both?.arg("when"))
        assertEquals("coxs bazar", both?.arg("place"))
    }

    @Test fun files() {
        assertEquals(ToolCall("find_file", mapOf("query" to "taxes", "type" to "pdf")), m("find my pdf about taxes"))
        val named = m("open the bank statement pdf")
        assertEquals("find_file", named?.tool)
        assertEquals("bank statement", named?.arg("query"))
    }

    @Test fun othersUntouched() {
        assertEquals("take_photo", m("take a photo")?.tool)
        assertNotEquals("find_file", m("open files")?.tool)
        assertNotEquals("find_file", m("open file manager")?.tool)
        assertNotEquals("find_photos", m("open gallery")?.tool)
        assertNotEquals("find_file", m("find my phone")?.tool)
    }

    @Test fun ranges() {
        assertEquals(today to today, PhotoWhen.range("today", today))
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 31), PhotoWhen.range("last month", today))
        assertEquals(LocalDate.of(2026, 3, 1) to LocalDate.of(2026, 3, 31), PhotoWhen.range("march", today))
        assertEquals(LocalDate.of(2025, 12, 1) to LocalDate.of(2025, 12, 31), PhotoWhen.range("december", today))
        assertEquals(today.minusDays(3) to today, PhotoWhen.range("last 3 days", today))
        assertNull(PhotoWhen.range("banana", today))
    }

    @Test fun score() {
        assertTrue(FileKind.score("taxes", "Tax_Return_2025") > 0)
        assertEquals(0, FileKind.score("taxes", "holiday plan"))
        assertEquals("pdf", FileKind.of("pdf"))
    }

    @Test fun pdfText() {
        val content = "BT /F1 12 Tf (Income tax return) Tj [(for ) -20 (2025)] TJ ET".toByteArray(Charsets.ISO_8859_1)
        val def = Deflater().apply { setInput(content); finish() }
        val buf = ByteArray(1024); val z = ByteArrayOutputStream()
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        val pdf = ByteArrayOutputStream().apply {
            write("%PDF-1.4\n1 0 obj << /Filter /FlateDecode >>\nstream\n".toByteArray())
            write(z.toByteArray()); write("\nendstream\nendobj\n".toByteArray())
        }.toByteArray()
        val text = PdfText.extract(pdf)
        assertTrue(text, text.contains("Income tax return") && text.contains("for 2025"))
    }
}
