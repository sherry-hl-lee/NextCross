package com.fit5120tp10.nextcross.posttripanalysis

import kotlin.math.abs

/**
 * Metrics of one backend `SPEED_LIMIT_EXCEEDANCE` event (flat `speed-v1` contract from
 * `POST /api/v1/trips/{tripId}/speed-analysis`).
 *
 * [limitStatus] is the backend's wire value: `static` (fixed limit), `scheduled` (a
 * time-based limit such as a school zone was active) or `inactive` (a time-based zone whose
 * rule was not active, so the base limit applied). Unknown or unmatched limits never produce
 * an event, so they are rejected here as well.
 */
data class SpeedLimitExceedanceDetails(
    val referenceLimitKmh: Int,
    val peakSpeedKmh: Double,
    val averageExceedanceKmh: Double,
    val limitStatus: String,
    val detectorVersion: String = "speed-v1"
) {
    /** Peak speed minus the reference limit. Always positive for a valid event. */
    val peakExceedanceKmh: Double get() = peakSpeedKmh - referenceLimitKmh

    /** Peak exceedance relative to the limit, so 40 and 100 km/h zones can be compared. */
    val peakExceedancePercent: Double get() = peakExceedanceKmh / referenceLimitKmh * 100.0

    /** True when a time-based limit (for example a school zone in school hours) applied. */
    val isTimeBasedLimit: Boolean get() = limitStatus == LIMIT_STATUS_SCHEDULED

    init {
        require(referenceLimitKmh in 1..200) { "Reference limit must be 1..200 km/h." }
        require(peakSpeedKmh.isFinite() && peakSpeedKmh > referenceLimitKmh && peakSpeedKmh <= 300.0) {
            "Peak speed must be above the reference limit and at most 300 km/h."
        }
        require(
            averageExceedanceKmh.isFinite() &&
                averageExceedanceKmh >= 0.0 &&
                averageExceedanceKmh <= peakExceedanceKmh + 0.01
        ) { "Average exceedance must be 0..peak exceedance." }
        require(limitStatus in USABLE_LIMIT_STATUSES) { "Limit status must be static, scheduled or inactive." }
        require(detectorVersion.isNotBlank() && detectorVersion.length <= 100) {
            "Detector version must be 1..100 characters."
        }
    }

    companion object {
        const val LIMIT_STATUS_STATIC: String = "static"
        const val LIMIT_STATUS_SCHEDULED: String = "scheduled"
        const val LIMIT_STATUS_INACTIVE: String = "inactive"
        val USABLE_LIMIT_STATUSES: Set<String> =
            setOf(LIMIT_STATUS_STATIC, LIMIT_STATUS_SCHEDULED, LIMIT_STATUS_INACTIVE)
    }
}

/**
 * Experimental parameters for the review priority of speed-limit events. Not legal
 * tolerances and not fitted to crash data.
 *
 * Peak exceedance is measured as a percentage of the limit so that 10 km/h over in a 40 km/h
 * zone ranks above 10 km/h over in a 100 km/h zone. Background: in 60 km/h zones the risk of a
 * casualty crash roughly doubled with each 5 km/h above 60 (Kloeden et al. 1997, CR 172), so
 * the size of the exceedance carries the most weight; how long it lasted comes second.
 */
data class SpeedExceedancePriorityConfig(
    val peakExceedancePercentReference: Double = 30.0,
    val averageExceedanceReferenceKmh: Double = 10.0,
    val longExceedanceReferenceSeconds: Double = 60.0,
    val peakPercentWeight: Double = 0.45,
    val averageExceedanceWeight: Double = 0.25,
    val durationWeight: Double = 0.30,
    /** Added when a time-based limit (for example a school zone) was active. */
    val timeBasedLimitBonus: Double = 10.0
) {
    init {
        require(peakExceedancePercentReference.isFinite() && peakExceedancePercentReference > 0.0)
        require(averageExceedanceReferenceKmh.isFinite() && averageExceedanceReferenceKmh > 0.0)
        require(longExceedanceReferenceSeconds.isFinite() && longExceedanceReferenceSeconds > 0.0)
        val weights = listOf(peakPercentWeight, averageExceedanceWeight, durationWeight)
        require(weights.all { it.isFinite() && it >= 0.0 })
        require(abs(weights.sum() - 1.0) <= 1e-9) { "Scoring weights must sum to 1." }
        require(timeBasedLimitBonus.isFinite() && timeBasedLimitBonus in 0.0..100.0)
    }
}

/** Evidence for one speed-limit event's 0..100 review priority. */
data class SpeedExceedancePriorityScore(
    val total: Double,
    val peakPercentComponent: Double,
    val averageExceedanceComponent: Double,
    val durationComponent: Double,
    val peakPercentContribution: Double,
    val averageExceedanceContribution: Double,
    val durationContribution: Double,
    val timeBasedLimitBonus: Double,
    val configSnapshot: SpeedExceedancePriorityConfig,
    val scoringVersion: String = "speed-review-priority-v1"
)

/** Pure and stateless, like [EventSeverityScorer]. Scores are only comparable within this type. */
class SpeedExceedancePriorityScorer(
    private val config: SpeedExceedancePriorityConfig = SpeedExceedancePriorityConfig()
) {
    fun score(details: SpeedLimitExceedanceDetails, durationSeconds: Double): SpeedExceedancePriorityScore {
        require(durationSeconds.isFinite() && durationSeconds >= 0.0) { "Duration must be finite and non-negative." }
        val peakPercentComponent =
            (details.peakExceedancePercent / config.peakExceedancePercentReference).coerceIn(0.0, 1.0)
        val averageExceedanceComponent =
            (details.averageExceedanceKmh / config.averageExceedanceReferenceKmh).coerceIn(0.0, 1.0)
        val durationComponent =
            (durationSeconds / config.longExceedanceReferenceSeconds).coerceIn(0.0, 1.0)

        val peakPercentContribution =
            (100.0 * config.peakPercentWeight * peakPercentComponent).coerceIn(0.0, 100.0)
        val averageExceedanceContribution =
            (100.0 * config.averageExceedanceWeight * averageExceedanceComponent).coerceIn(0.0, 100.0)
        val durationContribution =
            (100.0 * config.durationWeight * durationComponent).coerceIn(0.0, 100.0)
        val bonus = if (details.isTimeBasedLimit) config.timeBasedLimitBonus else 0.0

        return SpeedExceedancePriorityScore(
            total = (peakPercentContribution + averageExceedanceContribution + durationContribution + bonus)
                .coerceIn(0.0, 100.0),
            peakPercentComponent = peakPercentComponent,
            averageExceedanceComponent = averageExceedanceComponent,
            durationComponent = durationComponent,
            peakPercentContribution = peakPercentContribution,
            averageExceedanceContribution = averageExceedanceContribution,
            durationContribution = durationContribution,
            timeBasedLimitBonus = bonus,
            configSnapshot = config
        )
    }
}
