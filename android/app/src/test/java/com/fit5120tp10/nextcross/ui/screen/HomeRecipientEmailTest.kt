package com.fit5120tp10.nextcross.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecipientEmailTest {
    @Test
    fun doneDetectsAnAlreadySavedEmail() {
        assertTrue(isDuplicateRecipientEmail(listOf("parent@example.com", "parent@example.com"), 1))
    }

    @Test
    fun doneIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(isDuplicateRecipientEmail(listOf("Parent@Example.com", " parent@example.com "), 1))
    }

    @Test
    fun doneAllowsUnchangedOrDifferentEmailAndBlankNewRow() {
        assertFalse(isDuplicateRecipientEmail(listOf("parent@example.com"), 0))
        assertFalse(isDuplicateRecipientEmail(listOf("parent@example.com", "other@example.com"), 1))
        assertFalse(isDuplicateRecipientEmail(listOf("parent@example.com", " "), 1))
    }
}
