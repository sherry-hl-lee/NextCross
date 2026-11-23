package com.fit5120tp10.nextcross.posttripanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReviewRankingTest {
    @Test
    fun rankingSortsByScoreDescendingRatherThanChronologicalOrder() {
        val weak = event("weak", startIndex = 1, peakMagnitude = 3.2)
        val strongest = event("strongest", startIndex = 20, peakMagnitude = 6.0)
        val medium = event("medium", startIndex = 10, peakMagnitude = 4.5)

        assertEquals(listOf(strongest, medium, weak), rankEventsForReview(listOf(weak, strongest, medium)))
    }

    @Test
    fun rankingUsesCompleteScoreRatherThanPeakAccelerationAlone() {
        val higherPeak = event("higher-peak", peakMagnitude = 6.0, speedChange = 3.0, duration = 0.5)
        val moreReviewPoints = event("more-points", peakMagnitude = 5.0, speedChange = 10.0, duration = 3.0)

        assertTrue(higherPeak.peakAccelerationMps2 > moreReviewPoints.peakAccelerationMps2)
        assertEquals(listOf(moreReviewPoints, higherPeak), rankEventsForReview(listOf(higherPeak, moreReviewPoints)))
    }

    @Test
    fun tiedScoresUseStartSampleIndexBeforeEventId() {
        val later = event("a", startIndex = 10)
        val earlier = event("z", startIndex = 1)

        assertEquals(listOf(earlier, later), rankEventsForReview(listOf(later, earlier)))
    }

    @Test
    fun tiedScoreAndIndexUseEventIdLexicographically() {
        val c = event("event-c", startIndex = 1)
        val a = event("event-a", startIndex = 1)
        val b = event("event-b", startIndex = 1)

        assertEquals(listOf(a, b, c), rankEventsForReview(listOf(c, a, b)))
    }

    @Test
    fun exactTiesRetainTheirOriginalRelativeOrder() {
        val first = event("same", startIndex = 1)
        val second = first.copy(manoeuvre = first.manoeuvre.copy(tripId = "trip-2"))

        val ranked = rankEventsForReview(listOf(second, first))

        assertSame(second, ranked[0])
        assertSame(first, ranked[1])
    }

    @Test
    fun rankingDoesNotMutateTheSuppliedListOrEvents() {
        val weaker = event("weaker", startIndex = 1, peakMagnitude = 3.2)
        val stronger = event("stronger", startIndex = 10, peakMagnitude = 6.0)
        val input = mutableListOf(weaker, stronger)
        val original = input.toList()

        val ranked = rankEventsForReview(input)

        assertEquals(original, input)
        assertNotSame(input, ranked)
        assertSame(stronger, ranked[0])
        assertSame(weaker, ranked[1])
    }

    @Test
    fun allReviewFunctionsAcceptAnEmptyList() {
        assertTrue(rankEventsForReview(emptyList()).isEmpty())
        assertNull(topRapidAcceleration(emptyList()))
        assertNull(topRapidDeceleration(emptyList()))
        assertTrue(topEventsForReview(emptyList()).isEmpty())
    }

    @Test
    fun rankingAndReviewKeepASingleEvent() {
        val only = event("only")

        assertEquals(listOf(only), rankEventsForReview(listOf(only)))
        assertEquals(listOf(only), topEventsForReview(listOf(only)))
        assertSame(only, topRapidAcceleration(listOf(only)))
        assertNull(topRapidDeceleration(listOf(only)))
    }

    @Test
    fun topRapidAccelerationFiltersOutEvenHigherScoringDecelerations() {
        val weakAcceleration = event("weak-accel", peakMagnitude = 3.2)
        val strongAcceleration = event("strong-accel", peakMagnitude = 4.5)
        val strongestDeceleration = event("brake", type = DrivingEventType.RAPID_DECELERATION, peakMagnitude = 6.0)

        assertSame(strongAcceleration, topRapidAcceleration(listOf(weakAcceleration, strongestDeceleration, strongAcceleration)))
    }

    @Test
    fun topRapidDecelerationFiltersOutEvenHigherScoringAccelerations() {
        val weakDeceleration = event("weak-brake", type = DrivingEventType.RAPID_DECELERATION, peakMagnitude = 3.2)
        val strongDeceleration = event("strong-brake", type = DrivingEventType.RAPID_DECELERATION, peakMagnitude = 4.5)
        val strongestAcceleration = event("accel", peakMagnitude = 6.0)

        assertSame(strongDeceleration, topRapidDeceleration(listOf(weakDeceleration, strongestAcceleration, strongDeceleration)))
    }

    @Test
    fun topTypeReturnsNullWhenThatTypeIsAbsent() {
        val acceleration = event("accel")
        val deceleration = event("brake", type = DrivingEventType.RAPID_DECELERATION)

        assertNull(topRapidAcceleration(listOf(deceleration)))
        assertNull(topRapidDeceleration(listOf(acceleration)))
    }

    @Test
    fun topTypeHelpersUseTheSameTieBreaksAsTheOverallRanking() {
        val laterAcceleration = event("a", startIndex = 10)
        val earlierAcceleration = event("z", startIndex = 1)
        val laterIdDeceleration = event("z-brake", startIndex = 1, type = DrivingEventType.RAPID_DECELERATION)
        val earlierIdDeceleration = event("a-brake", startIndex = 1, type = DrivingEventType.RAPID_DECELERATION)
        val events = listOf(laterAcceleration, laterIdDeceleration, earlierAcceleration, earlierIdDeceleration)

        assertSame(earlierAcceleration, topRapidAcceleration(events))
        assertSame(earlierIdDeceleration, topRapidDeceleration(events))
    }

    @Test
    fun topEventsDefaultsToTheThreeHighestScores() {
        val events = listOf(
            event("weak", peakMagnitude = 3.0),
            event("strongest", peakMagnitude = 6.0),
            event("medium", peakMagnitude = 4.0),
            event("strong", peakMagnitude = 5.0)
        )

        assertEquals(listOf("strongest", "strong", "medium"), topEventsForReview(events).map { it.eventId })
    }

    @Test
    fun topEventsRespectsARequestedLimitWithoutChangingTheInput() {
        val events = mutableListOf(event("weak", peakMagnitude = 3.0), event("strong", peakMagnitude = 6.0))
        val original = events.toList()

        assertEquals(listOf("strong"), topEventsForReview(events, limit = 1).map { it.eventId })
        assertEquals(original, events)
    }

    @Test
    fun aLimitLargerThanTheListReturnsEveryEventInRankOrder() {
        val weak = event("weak", peakMagnitude = 3.0)
        val strong = event("strong", peakMagnitude = 6.0)

        assertEquals(listOf(strong, weak), topEventsForReview(listOf(weak, strong), limit = 10))
    }

    @Test
    fun zeroLimitReturnsNoEventsForEmptyAndNonEmptyInput() {
        assertTrue(topEventsForReview(emptyList(), limit = 0).isEmpty())
        assertTrue(topEventsForReview(listOf(event("event")), limit = 0).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeLimitIsRejectedForNonEmptyInput() {
        topEventsForReview(listOf(event("event")), limit = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeLimitIsRejectedEvenForEmptyInput() {
        topEventsForReview(emptyList(), limit = -1)
    }

    private fun event(
        id: String,
        startIndex: Int = 1,
        type: DrivingEventType = DrivingEventType.RAPID_ACCELERATION,
        peakMagnitude: Double = 4.0,
        speedChange: Double = 4.0,
        duration: Double = 3.0
    ): DrivingEvent {
        val isAcceleration = type == DrivingEventType.RAPID_ACCELERATION
        val speedBefore = if (isAcceleration) 0.0 else speedChange
        val speedAfter = if (isAcceleration) speedChange else 0.0
        val startTime = startIndex * 1_000L
        return EventSeverityScorer().score(
            DetectedManoeuvre(
                eventId = id,
                tripId = "trip-1",
                type = type,
                startTimeMillis = startTime,
                endTimeMillis = startTime + (duration * 1_000).toLong(),
                startSampleIndex = startIndex,
                endSampleIndex = startIndex + 3,
                durationSeconds = duration,
                speedBeforeMps = speedBefore,
                speedAfterMps = speedAfter,
                peakAccelerationMps2 = if (isAcceleration) peakMagnitude else -peakMagnitude,
                averageAccelerationMps2 = (speedAfter - speedBefore) / duration,
                endReason = EventEndReason.TRIP_END,
                hasMissingAccuracy = false,
                usesElapsedRealtime = true
            )
        )
    }
}
