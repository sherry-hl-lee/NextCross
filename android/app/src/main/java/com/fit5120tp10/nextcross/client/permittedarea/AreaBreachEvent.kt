package com.fit5120tp10.nextcross.client.permittedarea

/** Stable semantic event type; a backend DTO can map its [wireValue] once the API is agreed. */
enum class AreaBreachEventType(val wireValue: String) {
    PermittedAreaBreach("PERMITTED_AREA_BREACH")
}

/**
 * Client B output for one inside-to-outside transition.
 *
 * Deliberately excludes the driver's live location. Backend transport and delivery/idempotency
 * are separate concerns and can consume this object through an adapter once a contract exists.
 */
data class AreaBreachEvent(
    val tripId: String,
    val eventId: String,
    val eventType: AreaBreachEventType,
    val occurredAtEpochMillis: Long,
    val parentEmail: String
) {
    init {
        require(tripId.isNotBlank()) { "Trip identifier must not be blank." }
        require(eventId.isNotBlank()) { "Event identifier must not be blank." }
        require(occurredAtEpochMillis >= 0L) { "Event timestamp must not be negative." }
        require(parentEmail.isNotBlank()) { "Parent email must not be blank." }
    }
}
