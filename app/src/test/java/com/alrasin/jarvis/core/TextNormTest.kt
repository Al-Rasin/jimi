package com.alrasin.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormTest {
    @Test fun squash() {
        assertEquals("open youtube, now!", TextNorm.squash("  Open \t YouTube,\n now! "))
        assertEquals("", TextNorm.squash("   "))
    }

    @Test fun words() {
        assertEquals("mom s phone", TextNorm.words("Mom's  phone!"))
        assertEquals("আমার ফোন 2", TextNorm.words("আমার, ফোন 2?"))
        assertEquals("hello", TextNorm.words("...hello..."))
    }

    @Test fun alnum() {
        assertEquals("youtube2", TextNorm.alnum("You Tube 2"))
        assertEquals("mom", TextNorm.alnum("Mom ❤"))
    }
}
