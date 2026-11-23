package com.fit5120tp10.nextcross.client.tripreport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientEmailValidatorTest {
    @Test
    fun rejectsSingleCharacterTopLevelDomain() {
        assertFalse(RecipientEmailValidator.isValid("a@b.c"))
    }

    @Test
    fun acceptsOrdinaryRecipientAndTrimsWhitespace() {
        assertTrue(RecipientEmailValidator.isValid(" parent@example.com "))
    }

    @Test
    fun rejectsMissingDomainOrAtSign() {
        assertFalse(RecipientEmailValidator.isValid("parent@example"))
        assertFalse(RecipientEmailValidator.isValid("parent.example.com"))
    }
}
