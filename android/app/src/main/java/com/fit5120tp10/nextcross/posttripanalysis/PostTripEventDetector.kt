package com.fit5120tp10.nextcross.posttripanalysis

import kotlin.math.abs

/**
 * Batch-only experimental detector. Every call owns fresh state and preserves input indices.
 * Neither severity weights nor scoring references participate in boundary decisions.
 * No Android, database, network, UI, or live-recording side effects.
 */
class PostTripEventDetector(private val config: EventDetectionConfig = EventDetectionConfig()) {
    fun detect(tripId: String, samples: List<PostTripGpsSample>): DetectionResult {
        require(tripId.isNotBlank()) { "A recording trip ID is required." }
        val events = mutableListOf<DetectedManoeuvre>()
        val issues = mutableListOf<SampleIssue>()
        var previous: IndexedSample? = null
        var active: ManoeuvreBuilder? = null

        fun finish(reason: EventEndReason) {
            val event = active
            if (event != null && event.durationSeconds >= config.minEventDurationSeconds) {
                events += event.complete(tripId, reason)
            }
            active = null
        }

        samples.forEachIndexed { index, sample ->
            val current = IndexedSample(index, sample)
            val sampleIssue = invalidSampleReason(sample)
            if (sampleIssue != null) {
                issues += SampleIssue(index, sampleIssue)
                finish(EventEndReason.INVALID_SAMPLE)
                previous = null
                return@forEachIndexed
            }
            val before = previous
            if (before == null) {
                previous = current
                return@forEachIndexed
            }
            val interval = intervalBetween(before.sample, sample)
            val intervalIssue = interval.issue
            if (intervalIssue != null) {
                issues += SampleIssue(index, intervalIssue)
                finish(if (intervalIssue == SampleIssueReason.DATA_GAP) EventEndReason.DATA_GAP else EventEndReason.INVALID_TIME)
                // A reversed/duplicate timestamp is discarded, not used as a new baseline.
                previous = if (intervalIssue == SampleIssueReason.NON_INCREASING_TIME) null else current
                return@forEachIndexed
            }
            val millis = requireNotNull(interval.millis)
            val seconds = millis / 1000.0
            val acceleration = (requireNotNull(sample.speedMps) - requireNotNull(before.sample.speedMps)) / seconds
            if (!acceleration.isFinite()) {
                issues += SampleIssue(index, SampleIssueReason.NON_FINITE_ACCELERATION)
                finish(EventEndReason.INVALID_SAMPLE)
                previous = current
                return@forEachIndexed
            }

            val trigger = triggerType(acceleration)
            val continuation = continuationType(acceleration)
            val event = active
            if (event != null && !event.canAppend(millis)) {
                issues += SampleIssue(index, SampleIssueReason.NON_INCREASING_TIME)
                finish(EventEndReason.INVALID_TIME)
                previous = current
                return@forEachIndexed
            }
            when {
                event == null -> {
                    if (trigger != null) active = ManoeuvreBuilder(trigger, before, current, millis, acceleration)
                }
                continuation != null && continuation != event.type -> {
                    finish(EventEndReason.DIRECTION_CHANGE)
                    if (trigger != null) active = ManoeuvreBuilder(trigger, before, current, millis, acceleration)
                }
                !event.pendingEnd && continuation == event.type -> event.append(current, millis, acceleration)
                event.pendingEnd && trigger == event.type && event.pendingSeconds <= config.mergeGapSeconds -> {
                    if (event.preservesDirection(current)) {
                        event.resume(current, millis, acceleration)
                    } else {
                        // A large configurable bridge must never create a net-reversed event.
                        finish(EventEndReason.SETTLED)
                        active = ManoeuvreBuilder(trigger, before, current, millis, acceleration)
                    }
                }
                else -> {
                    event.waitForEnd(current, millis)
                    if (event.pendingSeconds > config.mergeGapSeconds) finish(EventEndReason.SETTLED)
                }
            }
            previous = current
        }
        finish(EventEndReason.TRIP_END)
        return DetectionResult(events.toList(), issues.toList())
    }

    private fun triggerType(acceleration: Double): DrivingEventType? = when {
        acceleration >= config.rapidAccelerationThresholdMps2 -> DrivingEventType.RAPID_ACCELERATION
        acceleration <= config.rapidDecelerationThresholdMps2 -> DrivingEventType.RAPID_DECELERATION
        else -> null
    }

    private fun continuationType(acceleration: Double): DrivingEventType? = when {
        acceleration >= config.continuationAccelerationMagnitudeMps2 -> DrivingEventType.RAPID_ACCELERATION
        acceleration <= -config.continuationAccelerationMagnitudeMps2 -> DrivingEventType.RAPID_DECELERATION
        else -> null
    }

    private fun invalidSampleReason(sample: PostTripGpsSample): SampleIssueReason? {
        val speed = sample.speedMps
        if (speed == null || !speed.isFinite() || speed < 0.0) return SampleIssueReason.INVALID_SPEED
        if (sample.elapsedRealtimeMillis != null && sample.elapsedRealtimeMillis < 0) return SampleIssueReason.INVALID_ELAPSED_TIME
        val accuracy = sample.horizontalAccuracyMeters
        if (accuracy != null && (!accuracy.isFinite() || accuracy < 0.0 || accuracy > config.maxHorizontalAccuracyMeters)) {
            return SampleIssueReason.POOR_POSITION_ACCURACY
        }
        val speedAccuracy = sample.speedAccuracyMps
        if (speedAccuracy != null && (!speedAccuracy.isFinite() || speedAccuracy < 0.0 || speedAccuracy > config.maxSpeedAccuracyMps)) {
            return SampleIssueReason.POOR_SPEED_ACCURACY
        }
        return null
    }

    private fun intervalBetween(before: PostTripGpsSample, after: PostTripGpsSample): Interval {
        if ((before.elapsedRealtimeMillis == null) != (after.elapsedRealtimeMillis == null)) {
            return Interval(issue = SampleIssueReason.MIXED_TIME_BASE)
        }
        val start = before.elapsedRealtimeMillis ?: before.timestampMillis
        val end = after.elapsedRealtimeMillis ?: after.timestampMillis
        val millis = try {
            Math.subtractExact(end, start)
        } catch (_: ArithmeticException) {
            return Interval(issue = SampleIssueReason.NON_INCREASING_TIME)
        }
        val seconds = millis / 1000.0
        return when {
            millis <= 0 -> Interval(issue = SampleIssueReason.NON_INCREASING_TIME)
            seconds < config.minSampleIntervalSeconds -> Interval(issue = SampleIssueReason.INTERVAL_TOO_SHORT)
            seconds > config.maxSampleGapSeconds -> Interval(issue = SampleIssueReason.DATA_GAP)
            else -> Interval(millis = millis)
        }
    }

    private data class Interval(val millis: Long? = null, val issue: SampleIssueReason? = null)
    private data class IndexedSample(val index: Int, val sample: PostTripGpsSample)

    private class ManoeuvreBuilder(
        val type: DrivingEventType,
        private val start: IndexedSample,
        private var end: IndexedSample,
        private var durationMillis: Long,
        private var peakAcceleration: Double
    ) {
        var pendingEnd = false
            private set
        // Integer accumulation preserves inclusive boundaries such as ten 100 ms intervals.
        val durationSeconds: Double get() = durationMillis / 1000.0
        val pendingSeconds: Double get() = pendingMillis / 1000.0
        private var pendingMillis = 0L
        private var mergedGapMillis = 0L
        private var missingAccuracy = missingAccuracy(start.sample) || missingAccuracy(end.sample)
        private var pendingMissingAccuracy = false

        fun canAppend(millis: Long): Boolean =
            durationMillis <= Long.MAX_VALUE - pendingMillis &&
                durationMillis + pendingMillis <= Long.MAX_VALUE - millis

        fun append(point: IndexedSample, millis: Long, acceleration: Double) {
            end = point
            durationMillis += millis
            if (abs(acceleration) > abs(peakAcceleration)) peakAcceleration = acceleration
            missingAccuracy = missingAccuracy || missingAccuracy(point.sample)
        }

        fun waitForEnd(point: IndexedSample, millis: Long) {
            pendingEnd = true
            pendingMillis += millis
            pendingMissingAccuracy = pendingMissingAccuracy || missingAccuracy(point.sample)
        }

        fun preservesDirection(point: IndexedSample): Boolean {
            val change = requireNotNull(point.sample.speedMps) - requireNotNull(start.sample.speedMps)
            return if (type == DrivingEventType.RAPID_ACCELERATION) change > 0.0 else change < 0.0
        }

        fun resume(point: IndexedSample, millis: Long, acceleration: Double) {
            mergedGapMillis += pendingMillis
            missingAccuracy = missingAccuracy || pendingMissingAccuracy
            append(point, pendingMillis + millis, acceleration)
            pendingEnd = false
            pendingMillis = 0L
            pendingMissingAccuracy = false
        }

        fun complete(tripId: String, reason: EventEndReason): DetectedManoeuvre {
            val before = requireNotNull(start.sample.speedMps)
            val after = requireNotNull(end.sample.speedMps)
            return DetectedManoeuvre(
                eventId = "$tripId:${type.name}:${start.index}:${end.index}",
                tripId = tripId,
                type = type,
                startTimeMillis = start.sample.timestampMillis,
                endTimeMillis = end.sample.timestampMillis,
                startSampleIndex = start.index,
                endSampleIndex = end.index,
                durationSeconds = durationSeconds,
                speedBeforeMps = before,
                speedAfterMps = after,
                peakAccelerationMps2 = peakAcceleration,
                averageAccelerationMps2 = (after - before) / durationSeconds,
                endReason = reason,
                hasMissingAccuracy = missingAccuracy,
                usesElapsedRealtime = start.sample.elapsedRealtimeMillis != null,
                mergedGapSeconds = mergedGapMillis / 1000.0
            )
        }

        private fun missingAccuracy(sample: PostTripGpsSample): Boolean =
            sample.horizontalAccuracyMeters == null || sample.speedAccuracyMps == null
    }
}
