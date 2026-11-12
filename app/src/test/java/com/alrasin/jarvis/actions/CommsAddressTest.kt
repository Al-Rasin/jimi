package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommsAddressTest {
    @Test fun explicitInternationalNumber() {
        assertEquals("8801712345678", PhoneNumbers.international("+880 1712-345678"))
    }

    @Test fun internationalDialPrefix() {
        assertEquals("442079460123", PhoneNumbers.international("0044 (20) 7946 0123"))
    }

    @Test fun localNumberRequiresCountry() {
        assertNull(PhoneNumbers.international("01712345678"))
        assertNull(PhoneNumbers.international("2025550123"))
    }

    @Test fun rejectsEmbeddedPlusAndExtensions() {
        assertNull(PhoneNumbers.international("+880+1712345678"))
        assertNull(PhoneNumbers.international("+442079460123 ext 2"))
    }

    @Test fun rejectsShortCodesAndLongNumbers() {
        assertNull(PhoneNumbers.international("+123"))
        assertNull(PhoneNumbers.international("+1234567890123456"))
    }

    @Test fun rejectsZeroCountryCode() {
        assertNull(PhoneNumbers.international("+01712345678"))
    }

    @Test fun rejectsUriInjection() {
        assertNull(PhoneNumbers.international("+8801712345678?text=other"))
        assertNull(PhoneNumbers.international("+8801712345678&phone=1234567"))
    }

    @Test fun messengerConversationRowsOnly() {
        assertTrue(CommsAddress.isMessengerConversation("vnd.android.cursor.item/com.facebook.messenger.chat"))
        assertTrue(CommsAddress.isMessengerConversation("vnd.android.cursor.item/com.facebook.orca.message"))
        assertFalse(CommsAddress.isMessengerConversation("vnd.android.cursor.item/com.facebook.messenger.call"))
        assertFalse(CommsAddress.isMessengerConversation("vnd.android.cursor.item/vnd.com.whatsapp.profile"))
        assertFalse(CommsAddress.isMessengerConversation("https://com.facebook.orca.message"))
    }
}
