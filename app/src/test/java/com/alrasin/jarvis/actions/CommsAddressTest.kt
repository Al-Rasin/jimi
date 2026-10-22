package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsAddressTest {
    @Test fun explicitInternationalNumber() {
        assertEquals("8801712345678", CommsAddress.international("+880 1712-345678"))
    }

    @Test fun internationalDialPrefix() {
        assertEquals("442079460123", CommsAddress.international("0044 (20) 7946 0123"))
    }

    @Test fun localNumberRequiresCountry() {
        assertNull(CommsAddress.international("01712345678"))
        assertNull(CommsAddress.international("2025550123"))
    }

    @Test fun rejectsEmbeddedPlusAndExtensions() {
        assertNull(CommsAddress.international("+880+1712345678"))
        assertNull(CommsAddress.international("+442079460123 ext 2"))
    }

    @Test fun rejectsShortCodesAndLongNumbers() {
        assertNull(CommsAddress.international("+123"))
        assertNull(CommsAddress.international("+1234567890123456"))
    }

    @Test fun rejectsZeroCountryCode() {
        assertNull(CommsAddress.international("+01712345678"))
    }

    @Test fun rejectsUriInjection() {
        assertNull(CommsAddress.international("+8801712345678?text=other"))
        assertNull(CommsAddress.international("+8801712345678&phone=1234567"))
    }

    @Test fun messengerConversationRowsOnly() {
        assertTrue(CommsAddress.isMessengerConversation("vnd.android.cursor.item/com.facebook.messenger.chat"))
        assertTrue(CommsAddress.isMessengerConversation("vnd.android.cursor.item/com.facebook.orca.message"))
        assertFalse(CommsAddress.isMessengerConversation("vnd.android.cursor.item/com.facebook.messenger.call"))
        assertFalse(CommsAddress.isMessengerConversation("vnd.android.cursor.item/vnd.com.whatsapp.profile"))
        assertFalse(CommsAddress.isMessengerConversation("https://com.facebook.orca.message"))
    }
}
