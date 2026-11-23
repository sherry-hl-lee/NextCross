package com.fit5120tp10.nextcross.client.permittedarea

/**
 * The fixed circular boundary selected during trip setup.
 *
 * [centre] is intentionally immutable: active-trip location updates are observations and must
 * never move the permitted area's centre.
 */
data class PermittedArea(
    val centre: GeographicPoint,
    val radiusKilometres: Double
) {
    init {
        require(radiusKilometres.isFinite() && radiusKilometres > 0.0) {
            "Permitted-area radius must be a positive, finite number of kilometres."
        }
    }
}

/** Email notification destination supplied by the parent during trip setup. */
data class ParentNotificationContact(
    val email: String
) {
    init {
        require(email.isNotBlank()) { "Parent email must not be blank." }
    }
}

/**
 * Locally persistable Client B configuration associated with one planned trip.
 *
 * UI-level email format validation remains outside this domain model. This model enforces
 * only the invariants required by the permitted-area checker and event generator.
 */
data class PermittedTripConfiguration(
    val tripId: String,
    val permittedArea: PermittedArea,
    val parentContact: ParentNotificationContact
) {
    init {
        require(tripId.isNotBlank()) { "Trip identifier must not be blank." }
    }
}
