package com.alrasin.jimi.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickMathTest {
    @Test fun spokenSums() {
        assertEquals("25 times 4 is 100.", QuickMath.answer("what is 25 times 4"))
        assertEquals("25 times 4 is 100.", QuickMath.answer("What's twenty five times four?"))
        assertEquals("2 plus 3 times 4 is 14.", QuickMath.answer("calculate 2 plus 3 times 4"))
        assertEquals("100 divided by 8 is 12.5.", QuickMath.answer("how much is 100 divided by 8"))
        assertEquals("10 divided by 3 is 3.3333.", QuickMath.answer("10 / 3"))
        assertEquals("50 minus 75 is -25.", QuickMath.answer("50 minus 75"))
        assertEquals("1500 times 12 is 18000.", QuickMath.answer("1,500 x 12"))
    }
    @Test fun percentRootSquare() {
        assertEquals("15 percent of 200 is 30.", QuickMath.answer("what is 15% of 200"))
        assertEquals("15 percent of 250 is 37.5.", QuickMath.answer("what is 15 percent of 250"))
        assertEquals("99 with 15 percent off is 84.15.", QuickMath.answer("15 percent off 99"))
        assertEquals("Half 7 is 3.5.", QuickMath.answer("half 7"))
        assertEquals("The square root of 81 is 9.", QuickMath.answer("square root of 81"))
        assertEquals("12 squared is 144.", QuickMath.answer("what is 12 squared"))
    }
    @Test fun powersAndPicks() {
        assertEquals("2 to the power of 10 is 1024.", QuickMath.answer("what's 2 to the power of 10"))
        assertEquals("3 to the power of 4 is 81.", QuickMath.answer("3 raised to 4"))
        repeat(20) {
            val n = QuickMath.answer("pick a number between one and ten")!!.removeSuffix(".").toInt()
            assert(n in 1..10)
        }
        assertNull(QuickMath.answer("pick a number between 10 and 1"))
    }
    @Test fun divideByZero() {
        assertEquals("You can't divide by zero, Boss.", QuickMath.answer("5 divided by 0"))
    }
    @Test fun notSums() {
        for (t in listOf("what is gravity", "set volume to 50", "call 01712345678", "what time is it", "-5", "5", "who are you", "set a timer for 5 minutes", "what is 5 and 3 apples")) {
            assertNull(t, QuickMath.answer(t))
        }
    }
}
