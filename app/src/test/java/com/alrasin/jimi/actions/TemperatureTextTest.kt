package com.alrasin.jimi.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureTextTest {
    @Test fun readsTenthsOfADegree() {
        assertEquals("The phone is 36 degrees. That's normal.", temperature(365))
        assertTrue(temperature(462).contains("hot"))
        assertTrue(temperature(Int.MIN_VALUE).startsWith("I can't"))
    }
}
