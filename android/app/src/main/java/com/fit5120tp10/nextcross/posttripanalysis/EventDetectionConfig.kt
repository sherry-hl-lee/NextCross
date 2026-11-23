package com.fit5120tp10.nextcross.posttripanalysis

import kotlin.math.abs

/**
 * Experimental engineering parameters, NOT validated crash-risk or legal thresholds.
 *
 * The four weights define the review priority (复盘优先级) of one manoeuvre. They are
 * explainable design choices, not values fitted to crash data:
 * - peak acceleration (40%) and speed change (25%) describe how sharp the manoeuvre was;
 * - event speed (25%) is the higher of the speeds before and after the manoeuvre, because
 *   the same change in speed carries more kinetic energy and needs more distance at a higher
 *   speed (100 -> 70 km/h is reviewed before 40 -> 10 km/h);
 * - duration (10%) separates a sustained manoeuvre from a brief spike.
 */
data class EventDetectionConfig(
    val rapidAccelerationThresholdMps2: Double = 3.0,
    val rapidDecelerationThresholdMps2: Double = -3.0,
    val continuationAccelerationMagnitudeMps2: Double = 1.0,
    val minSampleIntervalSeconds: Double = 0.5,
    val maxSampleGapSeconds: Double = 2.5,
    val mergeGapSeconds: Double = 1.0,
    val minEventDurationSeconds: Double = 1.0,
    val maxHorizontalAccuracyMeters: Double = 50.0,
    val maxSpeedAccuracyMps: Double = 3.0,
    val severeAccelerationReferenceMps2: Double = 6.0,
    val severeSpeedChangeReferenceMps: Double = 10.0,
    val longEventReferenceSeconds: Double = 4.0,
    val accelerationWeight: Double = 0.40,
    val speedChangeWeight: Double = 0.25,
    val durationWeight: Double = 0.10,
    /** Event speed at which the event-speed component saturates: 100 km/h. */
    val eventSpeedReferenceMps: Double = 100.0 / 3.6,
    val eventSpeedWeight: Double = 0.25
) {
    init {
        require(rapidAccelerationThresholdMps2.isFinite() && rapidAccelerationThresholdMps2 > 0.0)
        require(rapidDecelerationThresholdMps2.isFinite() && rapidDecelerationThresholdMps2 < 0.0)
        require(continuationAccelerationMagnitudeMps2.isFinite() && continuationAccelerationMagnitudeMps2 > 0.0)
        require(continuationAccelerationMagnitudeMps2 <= rapidAccelerationThresholdMps2)
        require(continuationAccelerationMagnitudeMps2 <= abs(rapidDecelerationThresholdMps2))
        require(minSampleIntervalSeconds.isFinite() && minSampleIntervalSeconds > 0.0)
        require(maxSampleGapSeconds.isFinite() && maxSampleGapSeconds >= minSampleIntervalSeconds)
        require(mergeGapSeconds.isFinite() && mergeGapSeconds >= 0.0)
        require(minEventDurationSeconds.isFinite() && minEventDurationSeconds > 0.0)
        require(maxHorizontalAccuracyMeters.isFinite() && maxHorizontalAccuracyMeters > 0.0)
        require(maxSpeedAccuracyMps.isFinite() && maxSpeedAccuracyMps > 0.0)
        require(severeAccelerationReferenceMps2.isFinite() && severeAccelerationReferenceMps2 > 0.0)
        require(severeSpeedChangeReferenceMps.isFinite() && severeSpeedChangeReferenceMps > 0.0)
        require(longEventReferenceSeconds.isFinite() && longEventReferenceSeconds > 0.0)
        require(eventSpeedReferenceMps.isFinite() && eventSpeedReferenceMps > 0.0)
        val weights = listOf(accelerationWeight, speedChangeWeight, durationWeight, eventSpeedWeight)
        require(weights.all { it.isFinite() && it >= 0.0 })
        require(abs(weights.sum() - 1.0) <= 1e-9) { "Scoring weights must sum to 1." }
    }
}
