package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactTextTest {
    @Test fun spokenEmail() {
        assertEquals("rahim@gmail.com", ContactText.email("rahim at gmail dot com"))
        assertEquals("rahim.uddin@gmail.com", ContactText.email("Rahim dot Uddin at Gmail dot com."))
        assertEquals("rahim_99@yahoo.co.uk", ContactText.email("rahim underscore 99 at yahoo dot co dot uk"))
        assertEquals("cat@mail.com", ContactText.email("cat at mail dot com"))
        assertEquals("rahim@gmail.com", ContactText.email("Rahim@Gmail.com"))
        assertNull(ContactText.email("rahim gmail"))
        assertNull(ContactText.email("at gmail dot com"))
    }

    @Test fun nameAndCard() {
        assertEquals("Rahim Uddin", ContactText.name(" rahim  uddin "))
        assertEquals("Rahim: 01712345678", ContactText.card("Rahim", "01712345678"))
        assertEquals("Rahim: 017\nr@x.com", ContactText.card("Rahim", "017", "r@x.com"))
    }
}
