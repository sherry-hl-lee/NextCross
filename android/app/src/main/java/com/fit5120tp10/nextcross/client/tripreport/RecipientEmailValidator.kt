package com.fit5120tp10.nextcross.client.tripreport

/** The same recipient format rule is used before saving and before sending. */
object RecipientEmailValidator {
    private val pattern =
        Regex("^[A-Za-z0-9.!#\$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}$")

    fun isValid(email: String): Boolean = pattern.matches(email.trim())
}
