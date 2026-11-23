package com.fit5120tp10.nextcross.client.aireflection

import kotlin.math.abs

/**
 * Client contract for the post-trip AI reflection hosted by the NextCross backend.
 *
 * The app never talks to an AI provider directly: it calls two backend routes, and the
 * backend owns the provider key, the prompts and the output validation.
 *
 * 1. `POST /ai/reflection/question` - whitelisted numbers in, one neutral restatement plus
 *    one open question out.
 * 2. `POST /ai/reflection/advice`   - the same numbers, the question that was asked and the
 *    driver's own sentence in, short context-aware advice out.
 *
 * Nothing else about the trip leaves the device: no GPS points, no route, no absolute
 * timestamps and no place names. See [ReflectionEventFactsFactory].
 */

/**
 * The three event families the backend can discuss. The first two are detected on the device;
 * speed-limit events come from backend speed analysis and carry different metrics.
 */
enum class ReflectionEventType(val wireValue: String) {
    RAPID_ACCELERATION("RAPID_ACCELERATION"),
    RAPID_DECELERATION("RAPID_DECELERATION"),
    SPEED_LIMIT_EXCEEDANCE("SPEED_LIMIT_EXCEEDANCE")
}

/** Backend `limitStatus` values that make a reference limit usable. */
enum class ReflectionLimitStatus(val wireValue: String) {
    STATIC("static"),
    SCHEDULED("scheduled"),
    INACTIVE("inactive");

    companion object {
        /** Unknown, ambiguous or unmatched statuses have no usable limit, so they map to null. */
        fun fromWire(value: String?): ReflectionLimitStatus? =
            entries.firstOrNull { it.wireValue == value?.trim() }
    }
}

/**
 * Speed-limit metrics, mirroring the backend's flat `speed-v1` contract. The backend rejects
 * the request unless the peak is above the limit and the average exceedance is no larger than
 * the peak exceedance, so the same rules are enforced here.
 */
data class ReflectionSpeedLimitMetrics(
    val referenceLimitKmh: Int,
    val peakSpeedKmh: Double,
    val averageExceedanceKmh: Double
) {
    val peakExceedanceKmh: Double get() = peakSpeedKmh - referenceLimitKmh

    init {
        require(referenceLimitKmh in 1..200) { "Reference limit must be 1..200 km/h." }
        require(peakSpeedKmh.isFinite() && peakSpeedKmh > referenceLimitKmh && peakSpeedKmh <= MAX_SPEED_KMH) {
            "Peak speed must be above the reference limit and at most $MAX_SPEED_KMH km/h."
        }
        require(
            averageExceedanceKmh.isFinite() &&
                averageExceedanceKmh >= 0.0 &&
                averageExceedanceKmh <= peakExceedanceKmh
        ) { "Average exceedance must be 0..peak exceedance." }
    }
}

/**
 * Optional event-time reference limit (`roadContext`). For an acceleration or braking event it
 * says the manoeuvre happened while the recorded speed was above this limit; for a speed-limit
 * event it must repeat that event's own limit. Never a current-location lookup.
 */
data class ReflectionRoadContext(
    val referenceLimitKmh: Int,
    val limitStatus: ReflectionLimitStatus
) {
    init {
        require(referenceLimitKmh in 1..200) { "Reference limit must be 1..200 km/h." }
    }
}

/**
 * Describes the recording, not the driving.
 *
 * Room v1 stores no speed accuracy, so client events are [UNKNOWN] rather than [GOOD].
 * The backend keeps discussing [UNKNOWN] events but asks the model to stay tentative;
 * only [POOR] is refused.
 */
enum class ReflectionDataQuality(val wireValue: String) {
    GOOD("GOOD"),
    UNKNOWN("UNKNOWN"),
    POOR("POOR")
}

/**
 * Per-trip totals, so the model can tell a one-off from a habit.
 *
 * The backend requires all three counts whenever a summary is supplied, so
 * [speedLimitExceedanceCount] is sent as 0 while speed analysis has not returned.
 */
data class TripEventCounts(
    val rapidAccelerationCount: Int,
    val rapidDecelerationCount: Int,
    val speedLimitExceedanceCount: Int = 0
) {
    init {
        require(
            rapidAccelerationCount >= 0 &&
                rapidDecelerationCount >= 0 &&
                speedLimitExceedanceCount >= 0
        ) { "Event counts must not be negative." }
    }
}

/**
 * The complete payload sent to the backend for one event. Every field is a number, an enum
 * or a count; there are deliberately no coordinate, route or free-text fields, so a caller
 * cannot leak them by accident.
 *
 * Field names and ranges mirror the backend's `SelectedEvent`, which is declared with
 * `extra="forbid"`: anything this class does not model must not be sent. The backend also
 * rejects mixed metric sets, so:
 * - acceleration / braking events carry [speedBeforeKmh], [speedAfterKmh] and
 *   [peakAccelerationMps2], and no [speedLimit];
 * - speed-limit events carry [speedLimit] only.
 */
data class ReflectionEventFacts(
    val eventId: String,
    val eventType: ReflectionEventType,
    val detectorVersion: String,
    val timeAfterDepartureSeconds: Double,
    val durationSeconds: Double,
    val speedBeforeKmh: Double? = null,
    val speedAfterKmh: Double? = null,
    val peakAccelerationMps2: Double? = null,
    val dataQuality: ReflectionDataQuality,
    val tripEventCounts: TripEventCounts?,
    val speedLimit: ReflectionSpeedLimitMetrics? = null,
    val roadContext: ReflectionRoadContext? = null
) {
    /** Null for speed-limit events, which have no before/after speeds. */
    val speedChangeKmh: Double?
        get() = if (speedBeforeKmh != null && speedAfterKmh != null) abs(speedAfterKmh - speedBeforeKmh) else null

    /**
     * Derived rather than measured on purpose: the backend rejects a request unless
     * `endOffset - startOffset - duration` is within 0.01s, and rounding the three values
     * independently can break that by more than the tolerance.
     */
    val endOffsetSeconds: Double get() = timeAfterDepartureSeconds + durationSeconds

    init {
        require(eventId.isNotBlank() && eventId.length <= MAX_EVENT_ID_CHARACTERS) {
            "Event id must be 1..$MAX_EVENT_ID_CHARACTERS characters."
        }
        require(detectorVersion.isNotBlank() && detectorVersion.length <= MAX_EVENT_ID_CHARACTERS) {
            "Detector version must be 1..$MAX_EVENT_ID_CHARACTERS characters."
        }
        require(
            timeAfterDepartureSeconds.isFinite() &&
                timeAfterDepartureSeconds >= 0.0 &&
                timeAfterDepartureSeconds <= MAX_TRIP_SECONDS
        ) { "Time after departure must be 0..$MAX_TRIP_SECONDS seconds." }
        require(
            durationSeconds.isFinite() &&
                durationSeconds > 0.0 &&
                timeAfterDepartureSeconds + durationSeconds <= MAX_TRIP_SECONDS
        ) { "Event duration must be positive and end inside the supported trip length." }
        // The backend must never be asked to explain metrics that contradict the event type.
        when (eventType) {
            ReflectionEventType.RAPID_ACCELERATION, ReflectionEventType.RAPID_DECELERATION -> {
                require(speedLimit == null) { "Speed-limit metrics belong to speed-limit events only." }
                val before = requireNotNull(speedBeforeKmh) { "Speed before the event is required." }
                val after = requireNotNull(speedAfterKmh) { "Speed after the event is required." }
                val peak = requireNotNull(peakAccelerationMps2) { "Peak acceleration is required." }
                require(before.isFinite() && before in 0.0..MAX_SPEED_KMH) {
                    "Speed before the event must be 0..$MAX_SPEED_KMH km/h."
                }
                require(after.isFinite() && after in 0.0..MAX_SPEED_KMH) {
                    "Speed after the event must be 0..$MAX_SPEED_KMH km/h."
                }
                require(peak.isFinite() && abs(peak) <= MAX_ACCELERATION_MS2) {
                    "Peak acceleration must be within +/-$MAX_ACCELERATION_MS2 m/s2."
                }
                if (eventType == ReflectionEventType.RAPID_ACCELERATION) {
                    require(peak > 0.0 && after > before) { "Acceleration metrics contradict the event type." }
                } else {
                    require(peak < 0.0 && after < before) { "Deceleration metrics contradict the event type." }
                }
            }
            ReflectionEventType.SPEED_LIMIT_EXCEEDANCE -> {
                require(speedBeforeKmh == null && speedAfterKmh == null && peakAccelerationMps2 == null) {
                    "Speed-limit events must not carry acceleration metrics."
                }
                val limit = requireNotNull(speedLimit) { "Speed-limit metrics are required." }
                require(roadContext == null || roadContext.referenceLimitKmh == limit.referenceLimitKmh) {
                    "The road context must repeat the event's own reference limit."
                }
            }
        }
    }
}

/** First turn: what the backend says about the recorded numbers, and what it asks the driver. */
data class ReflectionQuestion(
    val headline: String,
    val question: String,
    val quickReplies: List<String>
) {
    init {
        require(headline.isNotBlank()) { "The reflection headline must not be blank." }
        require(question.isNotBlank()) { "The reflection question must not be blank." }
        require(quickReplies.none { it.isBlank() }) { "Quick replies must not be blank." }
    }
}

/** Second turn: the response to the driver's own description of the context. */
data class ReflectionAdvice(
    val acknowledgement: String,
    val advice: List<String>,
    val encouragement: String
) {
    init {
        require(acknowledgement.isNotBlank()) { "The acknowledgement must not be blank." }
        require(advice.isNotEmpty() && advice.none { it.isBlank() }) {
            "At least one non-blank suggestion is required."
        }
        require(encouragement.isNotBlank()) { "The encouragement must not be blank." }
    }
}

/**
 * Why a reflection could not be produced.
 *
 * The first seven mirror the backend's own `UNAVAILABLE` reasons one for one, so a backend
 * reason string maps straight onto this enum. [ENDPOINT_NOT_FOUND] is client-side only: it
 * means the configured backend does not serve the two-turn routes yet.
 */
enum class AiUnavailableReason(val wireValue: String) {
    NOT_CONFIGURED("not_configured"),
    RATE_LIMITED("rate_limited"),
    PROVIDER_AUTHENTICATION("provider_authentication"),
    TIMEOUT("timeout"),
    NETWORK_ERROR("network_error"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    PROVIDER_REQUEST_REJECTED("provider_request_rejected"),
    ENDPOINT_NOT_FOUND("endpoint_not_found");

    companion object {
        /** Unknown or missing reasons degrade to [PROVIDER_UNAVAILABLE] rather than throwing. */
        fun fromWire(value: String?): AiUnavailableReason =
            entries.firstOrNull { it.wireValue == value?.trim() } ?: PROVIDER_UNAVAILABLE
    }
}

/** No exception ever reaches the UI: every outcome is one of these four cases. */
sealed interface AiReflectionResult<out T> {
    data class Completed<T>(val value: T) : AiReflectionResult<T>

    /** The backend or its provider was not reachable; recorded events stay visible. */
    data class Unavailable(val reason: AiUnavailableReason) : AiReflectionResult<Nothing>

    /** The backend validated the event and declined to discuss it, e.g. POOR data quality. */
    data class Rejected(val reason: String) : AiReflectionResult<Nothing>

    /** The backend answered, but with something this app will not show. */
    data class Failed(val detail: String) : AiReflectionResult<Nothing>
}

/** Shown with every reflection. The backend sends its own copy; this is the fallback. */
const val AI_REFLECTION_DISCLAIMER: String =
    "For post-trip discussion only; this does not establish unsafe driving or the cause of an event."

/** Metres per second to kilometres per hour. */
const val KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND: Double = 3.6

/** Longer driver answers are truncated before they are sent. Matches the backend's limit. */
const val MAX_DRIVER_CONTEXT_CHARACTERS: Int = 400

/** The backend caps identifiers at 100 characters. */
const val MAX_EVENT_ID_CHARACTERS: Int = 100

internal const val MAX_TRIP_SECONDS: Double = 86_400.0
internal const val MAX_SPEED_KMH: Double = 300.0
internal const val MAX_ACCELERATION_MS2: Double = 100.0
