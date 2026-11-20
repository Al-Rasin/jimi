package com.alrasin.jimi.brain

import com.alrasin.jimi.actions.NetBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NetRuleTest {
    private val now = LocalDateTime.of(2026, 11, 20, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)

    private fun check(said: String, app: String, state: String) {
        val c = m(said)
        assertEquals(said, "app_internet", c?.tool)
        assertEquals(said, app, c?.arg("app"))
        assertEquals(said, state, c?.arg("state"))
    }

    @Test fun off() {
        check("stop messenger internet connection", "messenger", "off")
        check("Stop Messenger internet.", "messenger", "off")
        check("block whatsapp internet", "whatsapp", "off")
        check("turn off internet for whatsapp", "whatsapp", "off")
        check("cut off data for messenger", "messenger", "off")
        check("disable wifi and data for whatsapp", "whatsapp", "off")
        check("turn whatsapp internet off", "whatsapp", "off")
        check("no internet for messenger", "messenger", "off")
        check("stop the messenger app internet", "messenger", "off")
    }

    @Test fun on() {
        check("turn on messenger internet", "messenger", "on")
        check("allow internet for whatsapp", "whatsapp", "on")
        check("give back whatsapp internet", "whatsapp", "on")
        check("turn messenger internet back on", "messenger", "on")
        check("unblock messenger data", "messenger", "on")
    }

    @Test fun phoneSwitchesStayPhoneSwitches() {
        for (s in listOf("turn off wifi", "turn on mobile data", "turn off mobile data", "turn on wifi", "turn off my wifi",
            "turn off internet", "turn on adb wifi", "turn on wireless network","turn off wifi for 10 minutes", "open whatsapp", "close messenger", "stop music")) {
            assertNotEquals(s, "app_internet", m(s)?.tool)
        }
    }

    @Test fun firewallCommands() {
        val cmds = NetBlock.commands(listOf(10123, 10050, 10123))
        assertEquals(2 * (2 + 2), cmds.size)
        assertTrue(cmds.any { it == "iptables -w -A ${NetBlock.CHAIN} -m owner --uid-owner 10050 ! -o lo -j REJECT" })
        assertTrue(cmds.any { it.startsWith("ip6tables -w -C OUTPUT -j ${NetBlock.CHAIN}") })
        assertEquals(4, NetBlock.commands(emptyList()).size)
    }
}
