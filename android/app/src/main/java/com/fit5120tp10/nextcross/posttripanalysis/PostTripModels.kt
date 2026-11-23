package com.fit5120tp10.nextcross.posttripanalysis

import kotlin.math.abs

/** Input order is preserved; an event's indices refer to this original list, including bad rows. */
data class PostTripGpsSample(
    val timestampMillis: Long,
    val speedMps: Double?,
    val elapsedRealtimeMillis: Long? = null,
    val horizontalAccuracyMeters: Double? = null,
    val speedAccuracyMps: Double? = null
)

enum class DrivingEventType { RAPID_ACCELERATION, RAPID_DECELERATION }

enum class EventEndReason {
    SETTLED, DIRECTION_CHANGE, DATA_GAP, INVALID_SAMPLE, INVALID_TIME, TRIP_END
}

enum class SampleIssueReason {
    INVALID_SPEED, POOR_POSITION_ACCURACY, POOR_SPEED_ACCURACY,
    INVALID_ELAPSED_TIME, MIXED_TIME_BASE, NON_INCREASING_TIME,
    INTERVAL_TOO_SHORT, DATA_GAP, NON_FINITE_ACCELERATION
}

data class SampleIssue(val sampleIndex: Int, val reason: SampleIssueReason)

/** Completed detection result, deliberately without any score. Peak and average retain sign. */
data class DetectedManoeuvre(
    val eventId: String,
    val tripId: String,
    val type: DrivingEventType,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val startSampleIndex: Int,
    val endSampleIndex: Int,
    val durationSeconds: Double,
    val speedBeforeMps: Double,
    val speedAfterMps: Double,
    val peakAccelerationMps2: Double,
    val averageAccelerationMps2: Double,
    val endReason: EventEndReason,
    val hasMissingAccuracy: Boolean,
    val usesElapsedRealtime: Boolean,
    val mergedGapSeconds: Double = 0.0,
    val detectorVersion: String = "posttrip-detection-v1"
) {
    val deltaSpeedMps: Double get() = abs(speedAfterMps - speedBeforeMps)

    init {
        require(eventId.isNotBlank() && tripId.isNotBlank())
        require(startSampleIndex >= 0 && endSampleIndex > startSampleIndex)
        require(durationSeconds.isFinite() && durationSeconds > 0.0)
        require(speedBeforeMps.isFinite() && speedBeforeMps >= 0.0)
        require(speedAfterMps.isFinite() && speedAfterMps >= 0.0)
        require(peakAccelerationMps2.isFinite() && averageAccelerationMps2.isFinite())
        require(mergedGapSeconds.isFinite() && mergedGapSeconds >= 0.0 && mergedGapSeconds <= durationSeconds)
        when (type) {
            DrivingEventType.RAPID_ACCELERATION -> {
                require(peakAccelerationMps2 > 0.0 && averageAccelerationMps2 > 0.0)
                require(speedAfterMps > speedBeforeMps)
            }
            DrivingEventType.RAPID_DECELERATION -> {
                require(peakAccelerationMps2 < 0.0 && averageAccelerationMps2 < 0.0)
                require(speedAfterMps < speedBeforeMps)
            }
        }
    }
}

/**
 * Evidence for an experimental 0..100 review priority, not a safety certification.
 *
 * v2 adds the event-speed component: the higher of the speeds before and after the
 * manoeuvre, relative to [EventDetectionConfig.eventSpeedReferenceMps].
 */
data class EventSeverityScore(
    val total: Double,
    val accelerationComponent: Double,
    val speedChangeComponent: Double,
    val durationComponent: Double,
    val accelerationContribution: Double,
    val speedChangeContribution: Double,
    val durationContribution: Double,
    val configSnapshot: EventDetectionConfig,
    val scoringVersion: String = "posttrip-review-priority-v2",
    val eventSpeedComponent: Double = 0.0,
    val eventSpeedContribution: Double = 0.0
)

/** A completed manoeuvre plus its separate scoring evidence. All original metrics are retained. */
data class DrivingEvent(val manoeuvre: DetectedManoeuvre, val scoreEvidence: EventSeverityScore) {
    val eventId: String get() = manoeuvre.eventId
    val tripId: String get() = manoeuvre.tripId
    val type: DrivingEventType get() = manoeuvre.type
    val startTimeMillis: Long get() = manoeuvre.startTimeMillis
    val endTimeMillis: Long get() = manoeuvre.endTimeMillis
    val startSampleIndex: Int get() = manoeuvre.startSampleIndex
    val endSampleIndex: Int get() = manoeuvre.endSampleIndex
    val durationSeconds: Double get() = manoeuvre.durationSeconds
    val speedBeforeMps: Double get() = manoeuvre.speedBeforeMps
    val speedAfterMps: Double get() = manoeuvre.speedAfterMps
    val deltaSpeedMps: Double get() = manoeuvre.deltaSpeedMps
    val peakAccelerationMps2: Double get() = manoeuvre.peakAccelerationMps2
    val averageAccelerationMps2: Double get() = manoeuvre.averageAccelerationMps2
    val severityScore: Double get() = scoreEvidence.total
}

data class DetectionResult(val events: List<DetectedManoeuvre>, val issues: List<SampleIssue>)

/** Detection order is chronological/input order; [rankedEvents] is a separate review projection. */
data class PostTripAnalysisResult(
    val events: List<DrivingEvent>,
    val rankedEvents: List<DrivingEvent>,
    val issues: List<SampleIssue>
)
