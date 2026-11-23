package com.fit5120tp10.nextcross.posttripanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTripEventDetectorTest {
    private val detector = PostTripEventDetector()

    @Test
    fun emptySingleAndConstantSpeedTripsHaveNoEvents() {
        for (samples in listOf(emptyList(), listOf(point(0, 10.0)), speeds(10.0, 10.0, 10.0))) {
            val result = detector.detect("quiet-trip", samples)
            assertTrue(result.events.isEmpty())
            assertTrue(result.issues.isEmpty())
        }
    }

    @Test
    fun exactAccelerationThresholdCreatesAnEventAndFlushesAtTripEnd() {
        val event = detector.detect("trip-a", speeds(10.0, 13.0)).events.single()

        assertEquals(DrivingEventType.RAPID_ACCELERATION, event.type)
        assertEquals("trip-a", event.tripId)
        assertRange(event, 0, 1, 1.0, 10.0, 13.0)
        assertEquals(3.0, event.peakAccelerationMps2, EPSILON)
        assertEquals(3.0, event.averageAccelerationMps2, EPSILON)
        assertEquals(EventEndReason.TRIP_END, event.endReason)
        assertFalse(event.hasMissingAccuracy)
        assertFalse(event.usesElapsedRealtime)
    }

    @Test
    fun exactDecelerationThresholdRetainsSignedAcceleration() {
        val event = detector.detect("trip-a", speeds(13.0, 10.0)).events.single()

        assertEquals(DrivingEventType.RAPID_DECELERATION, event.type)
        assertRange(event, 0, 1, 1.0, 13.0, 10.0)
        assertEquals(-3.0, event.peakAccelerationMps2, EPSILON)
        assertEquals(-3.0, event.averageAccelerationMps2, EPSILON)
        assertEquals(3.0, event.deltaSpeedMps, EPSILON)
    }

    @Test
    fun belowStartThresholdDoesNotStartEitherType() {
        assertTrue(detector.detect("up", speeds(10.0, 12.99, 15.98)).events.isEmpty())
        assertTrue(detector.detect("down", speeds(20.0, 17.01, 14.02)).events.isEmpty())
    }

    @Test
    fun exactContinuationThresholdExtendsAnExistingEvent() {
        val up = detector.detect("up", speeds(10.0, 14.0, 15.0)).events.single()
        val down = detector.detect("down", speeds(20.0, 16.0, 15.0)).events.single()

        assertRange(up, 0, 2, 2.0, 10.0, 15.0)
        assertEquals(4.0, up.peakAccelerationMps2, EPSILON)
        assertEquals(2.5, up.averageAccelerationMps2, EPSILON)
        assertRange(down, 0, 2, 2.0, 20.0, 15.0)
        assertEquals(-4.0, down.peakAccelerationMps2, EPSILON)
        assertEquals(-2.5, down.averageAccelerationMps2, EPSILON)
    }

    @Test
    fun weakTrailingIntervalsAreNotIncludedInTheEvent() {
        val event = detector.detect("trip", speeds(10.0, 14.0, 14.5, 14.75)).events.single()

        assertRange(event, 0, 1, 1.0, 10.0, 14.0)
        assertEquals(4.0, event.averageAccelerationMps2, EPSILON)
        assertEquals(0.0, event.mergedGapSeconds, EPSILON)
    }

    @Test
    fun sameTypeRestartAtMergeGapBoundaryIncludesTheValidBridge() {
        val event = detector.detect("trip", speeds(10.0, 14.0, 14.5, 18.5)).events.single()

        assertRange(event, 0, 3, 3.0, 10.0, 18.5)
        assertEquals(1.0, event.mergedGapSeconds, EPSILON)
        assertEquals(8.5 / 3.0, event.averageAccelerationMps2, EPSILON)
        assertEquals(4.0, event.peakAccelerationMps2, EPSILON)
    }

    @Test
    fun brakingRestartAlsoMergesAtTheBoundary() {
        val event = detector.detect("trip", speeds(20.0, 16.0, 15.5, 11.5)).events.single()

        assertRange(event, 0, 3, 3.0, 20.0, 11.5)
        assertEquals(DrivingEventType.RAPID_DECELERATION, event.type)
        assertEquals(1.0, event.mergedGapSeconds, EPSILON)
        assertEquals(-8.5 / 3.0, event.averageAccelerationMps2, EPSILON)
    }

    @Test
    fun neutralGapBeyondMergeLimitProducesSeparateEvents() {
        val events = detector.detect("trip", speeds(10.0, 14.0, 14.5, 15.0, 19.0)).events

        assertEquals(2, events.size)
        assertRange(events[0], 0, 1, 1.0, 10.0, 14.0)
        assertRange(events[1], 3, 4, 1.0, 15.0, 19.0)
        assertEquals(0.0, events[0].mergedGapSeconds, EPSILON)
        assertEquals(0.0, events[1].mergedGapSeconds, EPSILON)
    }

    @Test
    fun continuationWithoutANewStartDoesNotRescueAPendingWeakGap() {
        val event = detector.detect("trip", speeds(10.0, 14.0, 14.5, 16.0)).events.single()

        assertRange(event, 0, 1, 1.0, 10.0, 14.0)
        assertEquals(0.0, event.mergedGapSeconds, EPSILON)
    }

    @Test
    fun oppositeContinuationSplitsWithoutStartingABelowThresholdOppositeEvent() {
        val event = detector.detect("trip", speeds(10.0, 14.0, 13.0)).events.single()

        assertRange(event, 0, 1, 1.0, 10.0, 14.0)
        assertEquals(EventEndReason.DIRECTION_CHANGE, event.endReason)
    }

    @Test
    fun oppositeStartClosesOldEventAndStartsNewEventOnThatInterval() {
        val events = detector.detect("trip", speeds(10.0, 14.0, 10.0, 6.0)).events

        assertEquals(2, events.size)
        assertEquals(DrivingEventType.RAPID_ACCELERATION, events[0].type)
        assertRange(events[0], 0, 1, 1.0, 10.0, 14.0)
        assertEquals(EventEndReason.DIRECTION_CHANGE, events[0].endReason)
        assertEquals(DrivingEventType.RAPID_DECELERATION, events[1].type)
        assertRange(events[1], 1, 3, 2.0, 14.0, 6.0)
        assertEquals(-4.0, events[1].peakAccelerationMps2, EPSILON)
    }

    @Test
    fun minimumDurationRejectsASingleShortSpikeButKeepsBoundaryDuration() {
        val short = listOf(point(0, 10.0), point(500, 12.0))
        assertTrue(detector.detect("short", short).events.isEmpty())

        val event = detector.detect("long-enough", short + point(1000, 12.5)).events.single()
        assertRange(event, 0, 2, 1.0, 10.0, 12.5)
    }

    @Test
    fun minimumSampleIntervalIsInclusiveAndShorterIntervalsAreRejected() {
        val permissiveDuration = PostTripEventDetector(EventDetectionConfig(minEventDurationSeconds = 0.5))
        val event = permissiveDuration.detect("boundary", listOf(point(0, 10.0), point(500, 11.5))).events.single()
        assertRange(event, 0, 1, 0.5, 10.0, 11.5)

        val tooShort = detector.detect("too-short", listOf(point(0, 10.0), point(499, 14.0)))
        assertTrue(tooShort.events.isEmpty())
        assertIssue(tooShort, 1, SampleIssueReason.INTERVAL_TOO_SHORT)
    }

    @Test
    fun maximumSampleGapIsInclusive() {
        val result = detector.detect("boundary", listOf(point(0, 10.0), point(2500, 17.5)))

        assertRange(result.events.single(), 0, 1, 2.5, 10.0, 17.5)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun longGapClosesEventAndCurrentPointBecomesNewBaseline() {
        val result = detector.detect("gap", listOf(point(0, 10.0), point(1000, 14.0), point(4000, 26.0), point(5000, 30.0)))

        assertEquals(2, result.events.size)
        assertRange(result.events[0], 0, 1, 1.0, 10.0, 14.0)
        assertEquals(EventEndReason.DATA_GAP, result.events[0].endReason)
        assertRange(result.events[1], 2, 3, 1.0, 26.0, 30.0)
        assertIssue(result, 2, SampleIssueReason.DATA_GAP)
    }

    @Test
    fun invalidSpeedsHardCutAndOriginalIndicesArePreserved() {
        for (badSpeed in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val result = detector.detect("bad-speed", listOf(point(0, 10.0), point(1000, 14.0), point(2000, badSpeed), point(3000, 18.0), point(4000, 22.0)))

            assertEquals("bad speed $badSpeed", 2, result.events.size)
            assertRange(result.events[0], 0, 1, 1.0, 10.0, 14.0)
            assertEquals(EventEndReason.INVALID_SAMPLE, result.events[0].endReason)
            assertRange(result.events[1], 3, 4, 1.0, 18.0, 22.0)
            assertIssue(result, 2, SampleIssueReason.INVALID_SPEED)
        }
    }

    @Test
    fun badPositionAccuracyHardCutsInsteadOfBridgingTheBadRow() {
        for (accuracy in listOf(-1.0, 50.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = detector.detect("position", speeds(10.0, 14.0, 18.0, 22.0, 26.0).mapIndexed { index, sample ->
                if (index == 2) sample.copy(horizontalAccuracyMeters = accuracy) else sample
            })

            assertEquals(2, result.events.size)
            assertEquals(1, result.events[0].endSampleIndex)
            assertEquals(3, result.events[1].startSampleIndex)
            assertIssue(result, 2, SampleIssueReason.POOR_POSITION_ACCURACY)
        }
    }

    @Test
    fun badSpeedAccuracyHardCutsInsteadOfBridgingTheBadRow() {
        for (accuracy in listOf(-1.0, 3.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = detector.detect("speed-accuracy", speeds(10.0, 14.0, 18.0, 22.0, 26.0).mapIndexed { index, sample ->
                if (index == 2) sample.copy(speedAccuracyMps = accuracy) else sample
            })

            assertEquals(2, result.events.size)
            assertEquals(1, result.events[0].endSampleIndex)
            assertEquals(3, result.events[1].startSampleIndex)
            assertIssue(result, 2, SampleIssueReason.POOR_SPEED_ACCURACY)
        }
    }

    @Test
    fun accuracyLimitsAreInclusiveAndZeroAccuracyIsValid() {
        val result = detector.detect("accuracy-boundaries", listOf(
            point(0, 10.0).copy(horizontalAccuracyMeters = 0.0, speedAccuracyMps = 0.0),
            point(1000, 14.0).copy(horizontalAccuracyMeters = 50.0, speedAccuracyMps = 3.0)
        ))

        assertEquals(1, result.events.size)
        assertFalse(result.events.single().hasMissingAccuracy)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun missingAccuracyIsAllowedAndReportedOnTheEvent() {
        val result = detector.detect("unknown-quality", speeds(10.0, 14.0).map {
            it.copy(horizontalAccuracyMeters = null, speedAccuracyMps = null)
        })

        assertEquals(1, result.events.size)
        assertTrue(result.events.single().hasMissingAccuracy)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun missingAccuracyInAMergedBridgeIsNotLost() {
        val result = detector.detect("bridge-quality", speeds(10.0, 14.0, 14.5, 18.5).mapIndexed { index, sample ->
            if (index == 2) sample.copy(horizontalAccuracyMeters = null) else sample
        })

        assertTrue(result.events.single().hasMissingAccuracy)
        assertEquals(1.0, result.events.single().mergedGapSeconds, EPSILON)
    }

    @Test
    fun elapsedRealtimeAllowsWallClockToMoveBackwards() {
        val result = detector.detect("clock-change", listOf(
            point(10_000, 10.0).copy(elapsedRealtimeMillis = 0),
            point(9_000, 14.0).copy(elapsedRealtimeMillis = 1000),
            point(8_000, 18.0).copy(elapsedRealtimeMillis = 2000)
        ))
        val event = result.events.single()

        assertRange(event, 0, 2, 2.0, 10.0, 18.0)
        assertEquals(10_000L, event.startTimeMillis)
        assertEquals(8_000L, event.endTimeMillis)
        assertTrue(event.usesElapsedRealtime)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun mixedTimeBasesAreNotSilentlyComparedUsingWallClock() {
        for (samples in listOf(
            listOf(point(0, 10.0).copy(elapsedRealtimeMillis = 0), point(1000, 14.0)),
            listOf(point(0, 10.0), point(1000, 14.0).copy(elapsedRealtimeMillis = 1000))
        )) {
            val result = detector.detect("mixed-time", samples)
            assertTrue(result.events.isEmpty())
            assertIssue(result, 1, SampleIssueReason.MIXED_TIME_BASE)
        }
    }

    @Test
    fun negativeElapsedRealtimeIsAnInvalidSampleAndHardCut() {
        val result = detector.detect("invalid-elapsed", speeds(10.0, 14.0, 18.0, 22.0, 26.0).mapIndexed { index, sample ->
            sample.copy(elapsedRealtimeMillis = if (index == 2) -1L else index * 1000L)
        })

        assertEquals(2, result.events.size)
        assertEquals(1, result.events[0].endSampleIndex)
        assertEquals(3, result.events[1].startSampleIndex)
        assertIssue(result, 2, SampleIssueReason.INVALID_ELAPSED_TIME)
    }

    @Test
    fun nonIncreasingWallClockDiscardsOffendingPointAfterHardCut() {
        for (badTime in listOf(1000L, 500L)) {
            val result = detector.detect("bad-order", listOf(point(0, 10.0), point(1000, 14.0), point(badTime, 99.0), point(2000, 18.0), point(3000, 22.0)))

            assertEquals(2, result.events.size)
            assertRange(result.events[0], 0, 1, 1.0, 10.0, 14.0)
            assertRange(result.events[1], 3, 4, 1.0, 18.0, 22.0)
            assertIssue(result, 2, SampleIssueReason.NON_INCREASING_TIME)
        }
    }

    @Test
    fun nonIncreasingElapsedRealtimeDoesNotFallBackToIncreasingWallClock() {
        val result = detector.detect("elapsed-stalled", listOf(
            point(0, 10.0).copy(elapsedRealtimeMillis = 1000),
            point(1000, 14.0).copy(elapsedRealtimeMillis = 1000)
        ))

        assertTrue(result.events.isEmpty())
        assertIssue(result, 1, SampleIssueReason.NON_INCREASING_TIME)
    }

    @Test
    fun overflowingTimestampDifferenceCannotCreateAnEvent() {
        val result = detector.detect("overflow", listOf(point(Long.MIN_VALUE, 10.0), point(Long.MAX_VALUE, 14.0)))

        assertTrue(result.events.isEmpty())
        assertTrue(result.issues.isNotEmpty())
    }

    @Test
    fun nonFiniteDerivedAccelerationIsRejected() {
        val result = detector.detect("acceleration-overflow", listOf(point(0, 0.0), point(500, Double.MAX_VALUE)))

        assertTrue(result.events.isEmpty())
        assertIssue(result, 1, SampleIssueReason.NON_FINITE_ACCELERATION)
    }

    @Test
    fun detectorHasNoCrossTripStateAndRepeatedInputHasDeterministicIds() {
        val samples = speeds(10.0, 14.0, 15.0)
        val first = detector.detect("trip-a", samples)
        val other = detector.detect("trip-b", speeds(30.0, 34.0))
        val repeated = detector.detect("trip-a", samples)

        assertEquals(first, repeated)
        assertRange(other.events.single(), 0, 1, 1.0, 30.0, 34.0)
        assertNotEquals(first.events.single().eventId, other.events.single().eventId)
        assertTrue(detector.detect("empty-after-event", emptyList()).events.isEmpty())
    }

    @Test
    fun changingTypeOrEventExtentChangesTheDeterministicId() {
        val up = detector.detect("same-trip", speeds(10.0, 14.0)).events.single()
        val down = detector.detect("same-trip", speeds(14.0, 10.0)).events.single()
        val longer = detector.detect("same-trip", speeds(10.0, 14.0, 15.0)).events.single()

        assertNotEquals(up.eventId, down.eventId)
        assertNotEquals(up.eventId, longer.eventId)
    }

    private fun point(time: Long, speed: Double?): PostTripGpsSample = PostTripGpsSample(
        timestampMillis = time,
        speedMps = speed,
        horizontalAccuracyMeters = 5.0,
        speedAccuracyMps = 0.5
    )

    private fun speeds(vararg values: Double): List<PostTripGpsSample> =
        values.mapIndexed { index, speed -> point(index * 1000L, speed) }

    private fun assertRange(event: DetectedManoeuvre, start: Int, end: Int, duration: Double, before: Double, after: Double) {
        assertEquals(start, event.startSampleIndex)
        assertEquals(end, event.endSampleIndex)
        assertEquals(duration, event.durationSeconds, EPSILON)
        assertEquals(before, event.speedBeforeMps, EPSILON)
        assertEquals(after, event.speedAfterMps, EPSILON)
        assertEquals(kotlin.math.abs(after - before), event.deltaSpeedMps, EPSILON)
    }

    private fun assertIssue(result: DetectionResult, index: Int, reason: SampleIssueReason) {
        assertTrue("Expected $reason at original sample $index, actual: ${result.issues}", result.issues.contains(SampleIssue(index, reason)))
    }

    companion object {
        private const val EPSILON = 1e-9
    }
}
