package com.alrasin.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestorePlanTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private val file = """{"version":1,"time":1,
        "note":[{"time":10,"text":"Buy milk"},{"time":11,"text":"  "},{"time":12,"text":"Call Rafi"}],
        "memory_fact":[{"time":5,"text":"I live in Dhaka"},{"time":6,"text":"My car is blue"}],
        "reminder":[{"due":${now + day},"text":"Pay rent","repeat":"none"},
                    {"due":${now - day},"text":"Old one","repeat":"none"},
                    {"due":${now - 2 * day},"text":"Take pill","repeat":"daily"}],
        "app_rule":[{"pkg":"com.a","mode":"keep"},{"pkg":"com.b","mode":"weird"},{"pkg":"com.c","mode":"clear"}],
        "routine":[{"name":"good_night","steps":[{"tool":"say","args":{"text":"Night"}}],"enabled":false}],
        "notification_log":[{"time":1,"pkg":"x","title":"t","text":"y"}]}"""

    @Test fun freshPhoneGetsEverythingValid() {
        val p = RestorePlan.from(file, RestorePlan.Current(), now)
        assertEquals(listOf("Buy milk", "Call Rafi"), p.notes.map { it.text })
        assertEquals(2, p.facts.size)
        assertEquals(listOf("Pay rent", "Take pill"), p.reminders.map { it.text })
        val pill = p.reminders[1]
        assertEquals("daily", pill.repeat)
        assert(pill.dueMs > now && pill.dueMs <= now + day) { pill.dueMs }
        assertEquals(listOf("com.a", "com.c"), p.rules.map { it.pkg })
        assertEquals(false, p.routines.single().enabled)
        assertEquals("""[{"tool":"say","args":{"text":"Night"}}]""", p.routines.single().stepsJson)
        assertEquals("Restored 2 notes, 2 reminders, 2 facts, 2 app rules, 1 routine.", p.summary())
    }

    @Test fun restoringTwiceAddsNothing() {
        val first = RestorePlan.from(file, RestorePlan.Current(), now)
        val have = RestorePlan.Current(first.notes, first.reminders, first.facts.map { it.copy(text = it.text.uppercase()) }, first.rules, first.routines)
        val second = RestorePlan.from(file, have, now)
        assertEquals(0, second.total)
        assertEquals("Nothing new to restore.", second.summary())
    }

    @Test fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { RestorePlan.from("not json", RestorePlan.Current(), now) }
        assertThrows(IllegalArgumentException::class.java) { RestorePlan.from("""{"note":[]}""", RestorePlan.Current(), now) }
    }
}
