package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.IrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class IrRuleTest {
    private val now = LocalDateTime.of(2026, 11, 12, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)

    private fun check(said: String, device: String, button: String, times: String? = null) {
        val c = m(said)
        assertEquals(said, "ir_remote", c?.tool)
        assertEquals(said, device, c?.arg("device"))
        assertEquals(said, button, c?.arg("button"))
        assertEquals(said, times, c?.arg("times"))
    }

    @Test fun tv() {
        check("tv off", "tv", IrCodes.POWER)
        check("turn on the TV", "tv", IrCodes.POWER)
        check("volume up on the tv", "tv", IrCodes.VOL_UP)
        check("turn the tv volume down by 5", "tv", IrCodes.VOL_DOWN, "5")
        check("mute the tv", "tv", IrCodes.MUTE)
        check("next channel on tv", "tv", IrCodes.CH_UP)
        check("change tv input", "tv", IrCodes.INPUT)
    }

    @Test fun ac() {
        check("turn on the ac", "ac", IrCodes.POWER)
        check("AC off", "ac", IrCodes.POWER)
    }

    @Test fun notIr() {
        assertNotEquals("ir_remote", m("is the tv on")?.tool)
        assertNotEquals("ir_remote", m("play friends on tv")?.tool)
        assertNotEquals("ir_remote", m("turn on wifi")?.tool)
        assertNotEquals("ir_remote", m("volume up")?.tool)
    }

    @Test fun codes() {
        val nec = IrCodes.nec(0x04, 0xFB, 0x08, header = 9000)
        assertEquals(38_000, nec.freq)
        assertEquals(2 + 32 * 2 + 1, nec.pattern.size)
        assertEquals(9000, nec.pattern[0])
        // LG power cmd 0x08: bit 3 is 1 → 4th data bit of the cmd byte is a long space.
        assertEquals(1690, nec.pattern[2 + (16 + 3) * 2 + 1])
        val sony = IrCodes.sony(1, 21)
        assertEquals(40_000, sony.freq)
        assertEquals(3 * 26 - 1, sony.pattern.size)
        assertEquals(3, IrCodes.frames(IrCodes.POWER).size)
        assertEquals(1, IrCodes.frames(IrCodes.MUTE, "lg").size)
        assertNull(IrCodes.frames(IrCodes.POWER, "walton").firstOrNull())
    }
}
