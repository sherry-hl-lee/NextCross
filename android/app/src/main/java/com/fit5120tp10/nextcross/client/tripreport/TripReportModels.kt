package com.fit5120tp10.nextcross.client.tripreport

/** Compact on-device report metadata for history / sharing. */
data class TripReport(
    val tripId: String,
    val plannedTripId: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val startingPointLabel: String,
    val destinationLabel: String,
    val durationSeconds: Long?,
    val distanceMeters: Int?,
    val hardAccelerationCount: Int,
    val hardBrakingCount: Int,
    val includeDetailedRouteByDefault: Boolean,
    val includeReflectionByDefault: Boolean,
    val finalAiText: String?,
    val parentEmail: String? = null,
    val createdAtMillis: Long,
    val expiresAtMillis: Long
)

data class TripAiReflection(
    val eventId: String,
    val text: String
)

/** Speed-event reflections remain available in Trip Summary, but are not part of Trip Reports. */
fun isReportableReflectionEventId(eventId: String): Boolean =
    ":SPEED_LIMIT_EXCEEDANCE:" !in eventId

data class SaveCompletedTripReportRequest(
    val recordingTripId: String,
    val plannedTripId: String? = null,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val startingPointLabel: String,
    val destinationLabel: String,
    val finalAiText: String? = null,
    val parentEmail: String? = null,
    val includeDetailedRouteByDefault: Boolean = true,
    val includeReflectionByDefault: Boolean = false
)
