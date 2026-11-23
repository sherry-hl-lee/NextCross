package com.fit5120tp10.nextcross.posttripanalysis

/** A separate review ordering; this never changes the detector's chronological event list. */
fun rankEventsForReview(events: List<DrivingEvent>): List<DrivingEvent> =
    events.sortedWith(
        compareByDescending<DrivingEvent> { it.severityScore }
            .thenBy { it.startSampleIndex }
            .thenBy { it.eventId }
    )

fun topRapidAcceleration(events: List<DrivingEvent>): DrivingEvent? =
    rankEventsForReview(events.filter { it.type == DrivingEventType.RAPID_ACCELERATION })
        .firstOrNull()

fun topRapidDeceleration(events: List<DrivingEvent>): DrivingEvent? =
    rankEventsForReview(events.filter { it.type == DrivingEventType.RAPID_DECELERATION })
        .firstOrNull()

fun topEventsForReview(events: List<DrivingEvent>, limit: Int = 3): List<DrivingEvent> {
    require(limit >= 0) { "Review limit must not be negative." }
    if (limit == 0) return emptyList()
    return rankEventsForReview(events).take(limit)
}
