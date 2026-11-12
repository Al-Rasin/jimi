package com.alrasin.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test fun parseKeepsPlusAndDropsSpaces() {
        assertEquals("+8801712345678", PhoneNumbers.parse(" +880 1712-345678 "))
        assertEquals("01712345678", PhoneNumbers.parse("01712 345678"))
    }

    @Test fun parseRejectsNamesAndShortCodes() {
        assertNull(PhoneNumbers.parse("Rahim"))
        assertNull(PhoneNumbers.parse("1234"))
    }

    @Test fun tailMatchesWithOrWithoutCountryCode() {
        val tail = PhoneNumbers.tail("01712-345678")
        assertEquals("712345678", tail)
        assertTrue(PhoneNumbers.endsWith("+880 1712 345678", tail))
        assertFalse(PhoneNumbers.endsWith("+880 1712 345679", tail))
    }
}
