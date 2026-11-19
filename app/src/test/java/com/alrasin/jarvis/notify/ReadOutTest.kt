package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationLog
import com.alrasin.jarvis.notify.ReadOut.AppMode
import com.alrasin.jarvis.notify.ReadOut.Kind
import com.alrasin.jarvis.notify.ReadOut.Phone
import com.alrasin.jarvis.notify.ReadOut.Prefs
import com.alrasin.jarvis.notify.ReadOut.What
import com.alrasin.jarvis.notify.ReadOut.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOutTest {
    private fun row(pkg: String = "com.whatsapp", app: String = "WhatsApp", title: String = "Rahim", text: String = "On my way") =
        NotificationLog(timeMs = 0, pkg = pkg, appName = app, title = title, text = text, important = true, reason = "", status = "kept")

    private val always = Prefs(whenRead = When.ALWAYS)
    private val car = Phone(car = true)

    private fun say(r: NotificationLog, prefs: Prefs = always, phone: Phone = Phone(), rule: AppMode? = null, category: String? = null) =
        ReadOut.decide(r, ReadOut.kind(r, category, chat = false), rule, prefs, phone)

    @Test fun kinds() {
        assertEquals(Kind.PERSON, ReadOut.kind(row(), null, false))
        assertEquals(Kind.CODE, ReadOut.kind(row(pkg = "com.x", title = "Your OTP", text = "4821 is your code"), null, false))
        assertEquals(Kind.IMPORTANT, ReadOut.kind(row(pkg = "com.x", title = "Order", text = "Out for delivery today"), null, false))
        assertEquals(Kind.IMPORTANT, ReadOut.kind(row(pkg = "com.x", title = "Hi", text = "x"), "alarm", false))
        assertEquals(Kind.OTHER, ReadOut.kind(row(pkg = "com.x", title = "Sale", text = "50% off"), null, false))
        assertEquals(Kind.PERSON, ReadOut.kind(row(pkg = "com.x"), null, chat = true))
    }

    @Test fun personLineSaysWhoAndText() =
        assertEquals("Boss, Rahim says: On my way", say(row()).line)

    @Test fun lockedSaysOnlyWho() {
        assertEquals("Boss, message from Rahim on WhatsApp.", say(row(), phone = Phone(locked = true)).line)
        assertEquals("Boss, Rahim says: On my way", say(row(), always.copy(fullWhenLocked = true), Phone(locked = true)).line)
        assertEquals("Boss, alert from Shop.", say(row(pkg = "com.x", app = "Shop", title = "Order", text = "Delivered"), phone = Phone(locked = true)).line)
    }

    @Test fun codeNeverRead() {
        val d = say(row(pkg = "com.x", app = "Google", title = "Verification code", text = "G-582913"))
        assertEquals("Boss, a code from Google.", d.line)
        assertFalse(d.line!!.contains("582913"))
    }

    @Test fun moneyAppSilent() = assertNull(say(row(pkg = "com.bKash.customerapp", title = "Payment", text = "Received")).line)

    @Test fun whenSetting() {
        assertEquals("read-out off", say(row(), Prefs(whenRead = When.OFF), car).why)
        assertEquals("not in car or headphones", say(row(), Prefs(whenRead = When.CAR_HEADPHONES)).why)
        assertTrue(say(row(), Prefs(whenRead = When.CAR_HEADPHONES), Phone(headphones = true)).line != null)
    }

    @Test fun quietReasons() {
        assertEquals("bedtime", say(row(), phone = Phone(bedtime = true, car = true)).why)
        assertEquals("in a call", say(row(), phone = Phone(inCall = true)).why)
        assertEquals("Jimi busy", say(row(), phone = Phone(jimiBusy = true)).why)
        assertEquals("do not disturb", say(row(), phone = Phone(doNotDisturb = true)).why)
        assertEquals("phone on silent", say(row(), phone = Phone(ringerSilent = true)).why)
        // Car / headphones: only the user hears it, so silent / DND do not block.
        assertTrue(say(row(), phone = Phone(headphones = true, ringerSilent = true, doNotDisturb = true)).line != null)
    }

    @Test fun whatSetting() {
        val delivery = row(pkg = "com.x", app = "Shop", title = "Order", text = "Delivered")
        assertEquals("Boss, Shop: Order: Delivered", say(delivery).line)
        assertNull(say(delivery, always.copy(what = What.PEOPLE)).line)
        assertNull(say(row(pkg = "com.x", title = "Sale", text = "50% off")).line)
    }

    @Test fun appRules() {
        assertEquals("app set to silent", say(row(), rule = AppMode.SILENT).why)
        assertEquals("app set to clear", say(row(), rule = AppMode.CLEAR).why)
        assertTrue(say(row(pkg = "com.x", title = "Sale", text = "50% off"), rule = AppMode.READ).line != null)
        assertEquals(AppMode.READ, AppMode.of("keep"))
        assertNull(AppMode.of(null))
    }

    @Test fun migrate() {
        assertEquals(When.ALWAYS, ReadOut.migrateWhen(readImportant = true, readMessages = false))
        assertEquals(When.CAR_HEADPHONES, ReadOut.migrateWhen(readImportant = false, readMessages = true))
        assertEquals(When.OFF, ReadOut.migrateWhen(readImportant = false, readMessages = false))
        assertEquals(What.PEOPLE_IMPORTANT, What.of("junk"))
    }

    @Test fun queueDropsRepeatsAndCountsOverflow() {
        val q = ReadQueue(max = 2, repeatMs = 1000)
        assertTrue(q.add("a", 0))
        assertFalse(q.add("a", 10))
        assertTrue(q.add("b", 20))
        assertTrue(q.add("c", 30))
        assertTrue(q.add("d", 40))
        assertEquals("a", q.next())
        assertEquals("b And 2 more.", q.next())
        assertNull(q.next())
        assertTrue(q.isEmpty())
        assertFalse(q.add("a", 500))
        assertTrue(q.add("a", 2000))
    }
}
