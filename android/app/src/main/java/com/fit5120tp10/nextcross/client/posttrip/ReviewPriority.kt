package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.posttripanalysis.SpeedExceedancePriorityConfig
import com.fit5120tp10.nextcross.posttripanalysis.SpeedExceedancePriorityScorer

/**
 * Review priority (复盘优先级) for the post-trip summary: which recorded events are worth
 * looking at first. It is an experimental ordering for reflection, NOT a danger rating, a
 * crash probability or a judgement that the driving was unsafe, so the UI must not call it
 * "danger" or "risk".
 *
 * Rules agreed for the post-trip review:
 * - Each event family is ranked on its own. Acceleration, braking and speed-limit scores
 *   have no common unit, so they are never merged into one cross-type list.
 * - Rapid acceleration / hard braking start from the detector's score
 *   ([PostTripReviewEvent.reviewPriorityScore], `posttrip-review-priority-v2`), then get
 *   [ReviewPriorityConfig.duringSpeedingBonus] when they overlap a speed-limit event and
 *   [ReviewPriorityConfig.longDriveBonus] when they start two hours or more into the trip.
 * - Speed-limit events are scored by [SpeedExceedancePriorityScorer] from the backend
 *   metrics in [PostTripReviewEvent.speedLimitDetails].
 * - Overlapping events are never merged. Each keeps its own family and gets an annotation
 *   ([ReviewAnnotation]) pointing at the other.
 * - GPS data quality is deliberately not part of the priority.
 */
data class ReviewPriorityConfig(
    val duringSpeedingBonus: Double = 15.0,
    val longDriveBonus: Double = 10.0,
    val longDriveThresholdMillis: Long = 2L * 60L * 60L * 1000L,
    val speedExceedance: SpeedExceedancePriorityConfig = SpeedExceedancePriorityConfig()
) {
    init {
        require(duringSpeedingBonus.isFinite() && duringSpeedingBonus in 0.0..100.0)
        require(longDriveBonus.isFinite() && longDriveBonus in 0.0..100.0)
        require(longDriveThresholdMillis > 0L)
    }
}

/** Short facts the UI shows next to an event; they never change which family it is in. */
sealed interface ReviewAnnotation {
    /** This acceleration or braking event overlapped a speed-limit event. */
    data class DuringSpeeding(
        val speedEventId: String,
        val referenceLimitKmh: Int,
        val limitStatus: String
    ) : ReviewAnnotation

    /** This speed-limit event contains these acceleration / braking events. */
    data class ContainsManoeuvres(
        val rapidAccelerationCount: Int,
        val rapidDecelerationCount: Int
    ) : ReviewAnnotation

    /** The event started this many minutes into a trip of two hours or more. */
    data class LateInLongDrive(val minutesIntoTrip: Long) : ReviewAnnotation

    /** A time-based limit (for example a school zone in school hours) was active. */
    data object TimeBasedLimit : ReviewAnnotation
}

/**
 * Short driver-facing tag for list rows, the map info window and the AI card, e.g.
 * "During speeding · limit 60 km/h". Plain text with no Compose dependency, so every screen
 * shows the same wording. Null when there is nothing worth showing.
 */
fun ReviewAnnotation.displayText(): String? = when (this) {
    is ReviewAnnotation.DuringSpeeding -> "During speeding · limit $referenceLimitKmh km/h"
    is ReviewAnnotation.ContainsManoeuvres -> listOfNotNull(
        rapidDecelerationCount.takeIf { it > 0 }?.let { "$it hard braking" },
        rapidAccelerationCount.takeIf { it > 0 }?.let { "$it rapid acceleration" }
    ).takeIf { it.isNotEmpty() }?.joinToString(prefix = "Includes ", separator = " and ")
    is ReviewAnnotation.LateInLongDrive -> "${minutesIntoTrip / 60} h ${minutesIntoTrip % 60} min into the trip"
    ReviewAnnotation.TimeBasedLimit -> "Time-based limit"
}

/** One event with its final review priority (0..100, comparable only within its family). */
data class ReviewItem(
    val event: PostTripReviewEvent,
    val priority: Double,
    val annotations: List<ReviewAnnotation>
) {
    val eventId: String get() = event.eventId
    val type: ReviewEventType get() = event.type

    val duringSpeeding: ReviewAnnotation.DuringSpeeding?
        get() = annotations.filterIsInstance<ReviewAnnotation.DuringSpeeding>().firstOrNull()
}

/** Sections and AI candidates always use this order: above the limit, braking, acceleration. */
val REVIEW_TYPE_ORDER: List<ReviewEventType> = listOf(
    ReviewEventType.SPEED_LIMIT_EXCEEDANCE,
    ReviewEventType.RAPID_DECELERATION,
    ReviewEventType.RAPID_ACCELERATION
)

private val HIGHEST_PRIORITY_FIRST: Comparator<ReviewItem> =
    compareByDescending<ReviewItem> { it.priority }
        .thenBy { it.event.startTimestampMillis }
        .thenBy { it.event.eventId }

/** Input order is kept in [items]; ranked views are computed per family. */
data class ReviewRanking(val items: List<ReviewItem>) {

    /** One family, highest priority first; ties by start time, then event id. */
    fun ranked(type: ReviewEventType): List<ReviewItem> =
        items.filter { it.type == type }.sortedWith(HIGHEST_PRIORITY_FIRST)

    /** Every family in [REVIEW_TYPE_ORDER], each ranked on its own. Empty families are kept. */
    val byType: Map<ReviewEventType, List<ReviewItem>>
        get() = REVIEW_TYPE_ORDER.associateWith(::ranked)

    /** At most one item per family: the family's highest priority event. */
    fun topPerType(): List<ReviewItem> = REVIEW_TYPE_ORDER.mapNotNull { ranked(it).firstOrNull() }

    fun item(eventId: String): ReviewItem? = items.firstOrNull { it.eventId == eventId }

    fun annotationsFor(eventId: String): List<ReviewAnnotation> = item(eventId)?.annotations.orEmpty()
}

object ReviewPriority {

    /**
     * Scores and annotates every event. Works with local events only (speed analysis still
     * running or unavailable) and again once backend speed events have been merged in with
     * [mergeReviewEvents]; callers simply rank the new list.
     *
     * @param tripStartMillis trip start used for the long-drive bonus; null disables it.
     */
    fun rank(
        events: List<PostTripReviewEvent>,
        tripStartMillis: Long?,
        config: ReviewPriorityConfig = ReviewPriorityConfig()
    ): ReviewRanking {
        val speedScorer = SpeedExceedancePriorityScorer(config.speedExceedance)
        val speedEvents = events
            .filter { it.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE && it.speedLimitDetails != null }
            .sortedWith(compareBy<PostTripReviewEvent> { it.startTimestampMillis }.thenBy { it.eventId })
        val manoeuvres = events.filter { it.type != ReviewEventType.SPEED_LIMIT_EXCEEDANCE }

        return ReviewRanking(
            events.map { event ->
                if (event.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE) {
                    speedItem(event, manoeuvres, speedScorer)
                } else {
                    manoeuvreItem(event, speedEvents, tripStartMillis, config)
                }
            }
        )
    }

    private fun manoeuvreItem(
        event: PostTripReviewEvent,
        speedEvents: List<PostTripReviewEvent>,
        tripStartMillis: Long?,
        config: ReviewPriorityConfig
    ): ReviewItem {
        val annotations = mutableListOf<ReviewAnnotation>()
        var bonus = 0.0

        speedEvents.firstOrNull { overlaps(event, it) }?.let { speeding ->
            val details = requireNotNull(speeding.speedLimitDetails)
            annotations += ReviewAnnotation.DuringSpeeding(
                speedEventId = speeding.eventId,
                referenceLimitKmh = details.referenceLimitKmh,
                limitStatus = details.limitStatus
            )
            bonus += config.duringSpeedingBonus
        }

        if (tripStartMillis != null) {
            val intoTripMillis = event.startTimestampMillis - tripStartMillis
            if (intoTripMillis >= config.longDriveThresholdMillis) {
                annotations += ReviewAnnotation.LateInLongDrive(intoTripMillis / 60_000L)
                bonus += config.longDriveBonus
            }
        }

        val base = event.reviewPriorityScore?.takeIf { it.isFinite() } ?: 0.0
        return ReviewItem(event, (base + bonus).coerceIn(0.0, 100.0), annotations)
    }

    private fun speedItem(
        event: PostTripReviewEvent,
        manoeuvres: List<PostTripReviewEvent>,
        scorer: SpeedExceedancePriorityScorer
    ): ReviewItem {
        val details = event.speedLimitDetails
            ?: return ReviewItem(event, 0.0, emptyList()) // Cannot be scored without backend metrics.

        val annotations = mutableListOf<ReviewAnnotation>()
        val inside = manoeuvres.filter { overlaps(it, event) }
        if (inside.isNotEmpty()) {
            annotations += ReviewAnnotation.ContainsManoeuvres(
                rapidAccelerationCount = inside.count { it.type == ReviewEventType.RAPID_ACCELERATION },
                rapidDecelerationCount = inside.count { it.type == ReviewEventType.RAPID_DECELERATION }
            )
        }
        if (details.isTimeBasedLimit) annotations += ReviewAnnotation.TimeBasedLimit

        return ReviewItem(event, scorer.score(details, event.durationSeconds).total, annotations)
    }

    /** Closed intervals on recorded timestamps: touching endpoints count as overlapping. */
    internal fun overlaps(a: PostTripReviewEvent, b: PostTripReviewEvent): Boolean =
        a.startTimestampMillis <= b.endTimestampMillis && b.startTimestampMillis <= a.endTimestampMillis
}
