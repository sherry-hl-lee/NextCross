package com.fit5120tp10.nextcross.ui.validation

import com.fit5120tp10.nextcross.client.tripreport.RecipientEmailValidator

fun isValidEmailAddress(email: String): Boolean = RecipientEmailValidator.isValid(email)

data class TripSetupValidationErrors(
    val startingPoint: String? = null,
    val destination: String? = null,
    val email: String? = null
) {
    val hasErrors: Boolean
        get() = listOf(startingPoint, destination, email)
            .any { it != null }
}

fun validateTripSetup(
    startingPoint: String,
    startingPointSelected: Boolean,
    destination: String,
    destinationSelected: Boolean,
    sameEndpoints: Boolean,
    email: String
): TripSetupValidationErrors {
    val startingPointError = when {
        startingPoint.isBlank() -> "Starting point is required."
        !startingPointSelected -> "Select a starting point suggestion."
        else -> null
    }

    val destinationError = when {
        destination.isBlank() -> "Destination is required."
        !destinationSelected -> "Select a destination suggestion."
        sameEndpoints -> "Choose a different destination."
        else -> null
    }

    val emailError = when {
        email.isBlank() -> "Email address is required."
        !isValidEmailAddress(email) -> "Enter a valid email address."
        else -> null
    }

    return TripSetupValidationErrors(
        startingPoint = startingPointError,
        destination = destinationError,
        email = emailError
    )
}
