package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteAppTest {
    private val share = listOf("com.whatsapp" to "WhatsApp", "com.miui.notes" to "Notes", "com.google.android.keep" to "Keep notes")

    @Test fun createNoteAppWins() =
        assertEquals("com.google.android.keep" to true, NoteApp.pick(listOf("com.google.android.keep"), share))

    @Test fun elseShareTargetNamedLikeNotes() =
        assertEquals("com.miui.notes" to false, NoteApp.pick(emptyList(), share))

    @Test fun noNotesAppMeansNull() {
        assertNull(NoteApp.pick(emptyList(), listOf("com.whatsapp" to "WhatsApp", "com.miui.powerkeeper" to "Battery")))
    }
}
