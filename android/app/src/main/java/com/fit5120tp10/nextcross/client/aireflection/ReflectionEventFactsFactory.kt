package com.fit5120tp10.nextcross.client.aireflection

import com.fit5120tp10.nextcross.client.posttrip.PostTripReviewEvent
import com.fit5120tp10.nextcross.client.posttrip.REVIEW_TYPE_ORDER
import com.fit5120tp10.nextcross.client.posttrip.ReviewAnnotation
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.posttrip.ReviewItem
import com.fit5120tp10.nextcross.client.posttrip.ReviewRanking

/** Matches `DetectedManoeuvre.detectorVersion` in the post-trip analysis module. */
const val CLIENT_DETECTOR_VERSION: String = "posttrip-detection-v1"

/**
 * Turns one analysed trip event into the whitelisted facts sent to the backend.
 *
 * This is the only place where post-trip analysis meets the AI layer, so it is also the
 * place that drops everything that must not leave the device: route points, absolute
 * timestamps, sample indexes, the analysis revision, the speed-zone id and the trip id.
 *
 * The backend keeps `eventId` for its own response envelope and excludes it from the
 * payload it forwards to the AI provider.
 */
object ReflectionEventFactsFactory {

    /**
     * Returns null when the event cannot be described honestly, instead of guessing.
     *
     * @param roadContext for an acceleration or braking event that overlapped a speed-limit
     *   event (see [roadContextFor]). Ignored for speed-limit events, which always describe
     *   their own limit.
     */
    fun from(
        event: PostTripReviewEvent,
        tripStartMillis: Long?,
        counts: TripEventCounts? = null,
        roadContext: ReflectionRoadContext? = null
    ): ReflectionEventFacts? {
        val startReference = tripStartMillis ?: event.startTimestampMillis
        val offsetSeconds = ((event.startTimestampMillis - startReference) / 1000.0)
            .coerceAtLeast(0.0)
        // Room v1 records no speed accuracy, so most client events are UNKNOWN.
        // The backend still discusses UNKNOWN events, with more tentative wording.
        val dataQuality = if (event.hasMissingAccuracy) {
            ReflectionDataQuality.UNKNOWN
        } else {
            ReflectionDataQuality.GOOD
        }
        return runCatching {
            when (event.type) {
                ReviewEventType.RAPID_ACCELERATION, ReviewEventType.RAPID_DECELERATION -> {
                    val peakAcceleration = event.peakAccelerationMps2 ?: return null
                    ReflectionEventFacts(
                        eventId = shortenEventId(event.eventId),
                        eventType = if (event.type == ReviewEventType.RAPID_ACCELERATION) {
                            ReflectionEventType.RAPID_ACCELERATION
                        } else {
                            ReflectionEventType.RAPID_DECELERATION
                        },
                        detectorVersion = CLIENT_DETECTOR_VERSION,
                        timeAfterDepartureSeconds = offsetSeconds,
                        durationSeconds = event.durationSeconds,
                        speedBeforeKmh = event.speedBeforeMps * KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND,
                        speedAfterKmh = event.speedAfterMps * KILOMETRES_PER_HOUR_PER_METRE_PER_SECOND,
                        peakAccelerationMps2 = peakAcceleration,
                        dataQuality = dataQuality,
                        tripEventCounts = counts,
                        roadContext = roadContext
                    )
                }
                ReviewEventType.SPEED_LIMIT_EXCEEDANCE -> {
                    // Backend speed analysis supplies these metrics; without them there is
                    // nothing honest to describe.
                    val details = event.speedLimitDetails ?: return null
                    ReflectionEventFacts(
                        eventId = shortenEventId(event.eventId),
                        eventType = ReflectionEventType.SPEED_LIMIT_EXCEEDANCE,
                        detectorVersion = details.detectorVersion,
                        timeAfterDepartureSeconds = offsetSeconds,
                        durationSeconds = event.durationSeconds,
                        dataQuality = dataQuality,
                        tripEventCounts = counts,
                        speedLimit = ReflectionSpeedLimitMetrics(
                            referenceLimitKmh = details.referenceLimitKmh,
                            peakSpeedKmh = details.peakSpeedKmh,
                            averageExceedanceKmh = minOf(details.averageExceedanceKmh, details.peakExceedanceKmh)
                        ),
                        roadContext = ReflectionLimitStatus.fromWire(details.limitStatus)?.let { status ->
                            ReflectionRoadContext(details.referenceLimitKmh, status)
                        }
                    )
                }
            }
        }.getOrNull()
    }

    /**
     * The event-time limit for an acceleration or braking event that overlapped a speed-limit
     * event, so the backend can say it happened above the reference limit. Null otherwise.
     */
    fun roadContextFor(item: ReviewItem): ReflectionRoadContext? {
        if (item.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE) return null
        val speeding: ReviewAnnotation.DuringSpeeding = item.duringSpeeding ?: return null
        val status = ReflectionLimitStatus.fromWire(speeding.limitStatus) ?: return null
        return runCatching { ReflectionRoadContext(speeding.referenceLimitKmh, status) }.getOrNull()
    }

    /**
     * The backend caps `eventId` at 100 characters. Detector ids end with the event type and
     * the sample range, which is the part that distinguishes one event from another, so the
     * tail is kept rather than the head.
     */
    fun shortenEventId(eventId: String): String =
        if (eventId.length <= MAX_EVENT_ID_CHARACTERS) {
            eventId
        } else {
            eventId.takeLast(MAX_EVENT_ID_CHARACTERS)
        }

    /**
     * Totals for the whole trip, so the backend can tell a one-off from a habit.
     *
     * Speed-limit exceedances are counted when they are present in the list; they stay 0
     * while backend speed analysis has not returned.
     */
    fun countsOf(events: List<PostTripReviewEvent>): TripEventCounts = TripEventCounts(
        rapidAccelerationCount = events.count { it.type == ReviewEventType.RAPID_ACCELERATION },
        rapidDecelerationCount = events.count { it.type == ReviewEventType.RAPID_DECELERATION },
        speedLimitExceedanceCount = events.count { it.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE }
    )

    /**
     * Events that carry enough metrics to be discussed: acceleration and braking with a peak
     * acceleration, and speed-limit events with backend metrics.
     */
    fun reflectableEvents(events: List<PostTripReviewEvent>): List<PostTripReviewEvent> =
        events.filter(::isReflectable)

    fun isReflectable(event: PostTripReviewEvent): Boolean = when (event.type) {
        ReviewEventType.RAPID_ACCELERATION, ReviewEventType.RAPID_DECELERATION ->
            event.peakAccelerationMps2 != null
        ReviewEventType.SPEED_LIMIT_EXCEEDANCE -> event.speedLimitDetails != null
    }

    /**
     * What the AI card offers: at most one event per family - the family's highest review
     * priority event that can be discussed - in [REVIEW_TYPE_ORDER]. Nothing is sent until
     * the driver picks one and taps start.
     */
    fun candidates(ranking: ReviewRanking): List<ReviewItem> =
        REVIEW_TYPE_ORDER.mapNotNull { type ->
            ranking.ranked(type).firstOrNull { isReflectable(it.event) }
        }
}
