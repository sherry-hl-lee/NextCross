package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.posttripanalysis.SpeedExceedancePriorityScorer
import com.fit5120tp10.nextcross.posttripanalysis.SpeedLimitExceedanceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPriorityTest {
    private val tripStart = 1_789_000_000_000L

    @Test
    fun withoutSpeedEventsEachManoeuvreKeepsItsDetectorScore() {
        val braking = manoeuvre("brake", ReviewEventType.RAPID_DECELERATION, startSeconds = 60, score = 42.0)
        val acceleration = manoeuvre("accel", ReviewEventType.RAPID_ACCELERATION, startSeconds = 120, score = 37.5)

        val ranking = ReviewPriority.rank(listOf(braking, acceleration), tripStart)

        assertEquals(42.0, ranking.item("brake")!!.priority, EPSILON)
        assertEquals(37.5, ranking.item("accel")!!.priority, EPSILON)
        assertTrue(ranking.items.all { it.annotations.isEmpty() })
    }

    @Test
    fun aManoeuvreDuringSpeedingIsAnnotatedAndGetsTheBonus() {
        val acceleration = manoeuvre("accel", ReviewEventType.RAPID_ACCELERATION, startSeconds = 610, score = 50.0)
        val speeding = speed("speed", startSeconds = 600, durationSeconds = 40, limit = 60)

        val item = ReviewPriority.rank(listOf(acceleration, speeding), tripStart).item("accel")!!

        assertEquals(65.0, item.priority, EPSILON)
        assertEquals(
            listOf(ReviewAnnotation.DuringSpeeding("speed", referenceLimitKmh = 60, limitStatus = "static")),
            item.annotations
        )
    }

    @Test
    fun touchingIntervalsOverlapButAGapDoesNot() {
        val speeding = speed("speed", startSeconds = 600, durationSeconds = 40)
        val endsAtStart = manoeuvre("touch", ReviewEventType.RAPID_ACCELERATION, startSeconds = 598, durationSeconds = 2)
        val endsBefore = manoeuvre("gap", ReviewEventType.RAPID_ACCELERATION, startSeconds = 590, durationSeconds = 2)

        val ranking = ReviewPriority.rank(listOf(speeding, endsAtStart, endsBefore), tripStart)

        assertTrue(ranking.item("touch")!!.duringSpeeding != null)
        assertEquals(null, ranking.item("gap")!!.duringSpeeding)
    }

    @Test
    fun aSpeedEventIsAnnotatedWithTheManoeuvresInsideIt() {
        val speeding = speed("speed", startSeconds = 600, durationSeconds = 60, status = "scheduled", limit = 40, peak = 52.0)
        val inside = listOf(
            manoeuvre("a1", ReviewEventType.RAPID_ACCELERATION, startSeconds = 605),
            manoeuvre("b1", ReviewEventType.RAPID_DECELERATION, startSeconds = 630),
            manoeuvre("b2", ReviewEventType.RAPID_DECELERATION, startSeconds = 650)
        )
        val outside = manoeuvre("b3", ReviewEventType.RAPID_DECELERATION, startSeconds = 900)

        val item = ReviewPriority.rank(inside + speeding + outside, tripStart).item("speed")!!

        assertEquals(
            listOf(
                ReviewAnnotation.ContainsManoeuvres(rapidAccelerationCount = 1, rapidDecelerationCount = 2),
                ReviewAnnotation.TimeBasedLimit
            ),
            item.annotations
        )
        val expected = SpeedExceedancePriorityScorer().score(speeding.speedLimitDetails!!, 60.0).total
        assertEquals(expected, item.priority, EPSILON)
    }

    @Test
    fun theLongDriveBonusStartsAtTwoHoursIntoTheTrip() {
        val justBefore = manoeuvre("before", ReviewEventType.RAPID_DECELERATION, startSeconds = 2 * 3600 - 1, score = 30.0)
        val atTwoHours = manoeuvre("at", ReviewEventType.RAPID_DECELERATION, startSeconds = 2 * 3600, score = 30.0)

        val ranking = ReviewPriority.rank(listOf(justBefore, atTwoHours), tripStart)

        assertEquals(30.0, ranking.item("before")!!.priority, EPSILON)
        assertEquals(40.0, ranking.item("at")!!.priority, EPSILON)
        assertEquals(listOf(ReviewAnnotation.LateInLongDrive(120)), ranking.item("at")!!.annotations)
    }

    @Test
    fun anUnknownTripStartDisablesOnlyTheLongDriveBonus() {
        val late = manoeuvre("late", ReviewEventType.RAPID_DECELERATION, startSeconds = 3 * 3600, score = 30.0)
        assertEquals(30.0, ReviewPriority.rank(listOf(late), tripStartMillis = null).item("late")!!.priority, EPSILON)
    }

    @Test
    fun bonusesAreCappedAtOneHundred() {
        val braking = manoeuvre("brake", ReviewEventType.RAPID_DECELERATION, startSeconds = 3 * 3600 + 5, score = 90.0)
        val speeding = speed("speed", startSeconds = 3 * 3600, durationSeconds = 30)

        val item = ReviewPriority.rank(listOf(braking, speeding), tripStart).item("brake")!!

        assertEquals(100.0, item.priority, EPSILON)
        assertEquals(2, item.annotations.size)
    }

    @Test
    fun familiesAreRankedSeparatelyWithStableTieBreaks() {
        val events = listOf(
            manoeuvre("b-low", ReviewEventType.RAPID_DECELERATION, startSeconds = 10, score = 20.0),
            manoeuvre("b-high", ReviewEventType.RAPID_DECELERATION, startSeconds = 50, score = 80.0),
            manoeuvre("b-tie-late", ReviewEventType.RAPID_DECELERATION, startSeconds = 40, score = 60.0),
            manoeuvre("b-tie-early", ReviewEventType.RAPID_DECELERATION, startSeconds = 30, score = 60.0),
            manoeuvre("a-1", ReviewEventType.RAPID_ACCELERATION, startSeconds = 70, score = 99.0)
        )

        val ranking = ReviewPriority.rank(events, tripStart)

        assertEquals(
            listOf("b-high", "b-tie-early", "b-tie-late", "b-low"),
            ranking.ranked(ReviewEventType.RAPID_DECELERATION).map { it.eventId }
        )
        // A high acceleration score never moves into the braking list.
        assertEquals(listOf("a-1"), ranking.ranked(ReviewEventType.RAPID_ACCELERATION).map { it.eventId })
        assertEquals(REVIEW_TYPE_ORDER, ranking.byType.keys.toList())
        assertTrue(ranking.byType.getValue(ReviewEventType.SPEED_LIMIT_EXCEEDANCE).isEmpty())
    }

    @Test
    fun topPerTypeUsesTheFixedFamilyOrderAndSkipsEmptyFamilies() {
        val events = listOf(
            manoeuvre("accel", ReviewEventType.RAPID_ACCELERATION, startSeconds = 5, score = 90.0),
            speed("speed", startSeconds = 600, durationSeconds = 20)
        )

        assertEquals(listOf("speed", "accel"), ReviewPriority.rank(events, tripStart).topPerType().map { it.eventId })
    }

    @Test
    fun aSpeedEventWithoutBackendMetricsIsListedButNotScored() {
        val bare = speed("bare", startSeconds = 600, durationSeconds = 20).copy(speedLimitDetails = null)
        val acceleration = manoeuvre("accel", ReviewEventType.RAPID_ACCELERATION, startSeconds = 605, score = 40.0)

        val ranking = ReviewPriority.rank(listOf(bare, acceleration), tripStart)

        assertEquals(0.0, ranking.item("bare")!!.priority, EPSILON)
        assertTrue(ranking.item("bare")!!.annotations.isEmpty())
        // Without a limit there is nothing to annotate the acceleration with.
        assertEquals(40.0, ranking.item("accel")!!.priority, EPSILON)
    }

    @Test
    fun rankingKeepsInputOrderInItemsAndDoesNotMutateEvents() {
        val events = listOf(
            manoeuvre("x", ReviewEventType.RAPID_DECELERATION, startSeconds = 100, score = 10.0),
            manoeuvre("y", ReviewEventType.RAPID_DECELERATION, startSeconds = 10, score = 90.0)
        )
        val copy = events.toList()

        val ranking = ReviewPriority.rank(events, tripStart)

        assertEquals(listOf("x", "y"), ranking.items.map { it.eventId })
        assertEquals(copy, events)
        assertEquals(listOf("y", "x"), ranking.ranked(ReviewEventType.RAPID_DECELERATION).map { it.eventId })
    }

    @Test
    fun annotationsHaveShortDriverFacingText() {
        assertEquals("During speeding · limit 60 km/h", ReviewAnnotation.DuringSpeeding("s", 60, "static").displayText())
        assertEquals("Includes 2 hard braking and 1 rapid acceleration", ReviewAnnotation.ContainsManoeuvres(1, 2).displayText())
        assertEquals("Includes 1 rapid acceleration", ReviewAnnotation.ContainsManoeuvres(1, 0).displayText())
        assertEquals(null, ReviewAnnotation.ContainsManoeuvres(0, 0).displayText())
        assertEquals("2 h 5 min into the trip", ReviewAnnotation.LateInLongDrive(125).displayText())
        assertEquals("Time-based limit", ReviewAnnotation.TimeBasedLimit.displayText())
    }

    private fun manoeuvre(
        id: String,
        type: ReviewEventType,
        startSeconds: Int,
        durationSeconds: Int = 2,
        score: Double = 50.0
    ): PostTripReviewEvent {
        val accelerating = type == ReviewEventType.RAPID_ACCELERATION
        return event(
            id, type, startSeconds, durationSeconds,
            speedBefore = if (accelerating) 10.0 else 20.0,
            speedAfter = if (accelerating) 20.0 else 10.0,
            peak = if (accelerating) 4.0 else -4.0,
            score = score,
            details = null
        )
    }

    private fun speed(
        id: String,
        startSeconds: Int,
        durationSeconds: Int,
        limit: Int = 60,
        peak: Double = 74.0,
        average: Double = 8.0,
        status: String = "static"
    ) = event(
        id, ReviewEventType.SPEED_LIMIT_EXCEEDANCE, startSeconds, durationSeconds,
        speedBefore = peak / 3.6, speedAfter = peak / 3.6, peak = null, score = null,
        details = SpeedLimitExceedanceDetails(limit, peak, average, status)
    )

    private fun event(
        id: String,
        type: ReviewEventType,
        startSeconds: Int,
        durationSeconds: Int,
        speedBefore: Double,
        speedAfter: Double,
        peak: Double?,
        score: Double?,
        details: SpeedLimitExceedanceDetails?
    ) = PostTripReviewEvent(
        eventId = id,
        tripId = "trip",
        analysisRevision = "rev",
        type = type,
        startSampleIndex = startSeconds,
        endSampleIndex = startSeconds + durationSeconds,
        startTimestampMillis = tripStart + startSeconds * 1000L,
        endTimestampMillis = tripStart + (startSeconds + durationSeconds) * 1000L,
        durationSeconds = durationSeconds.toDouble(),
        speedBeforeMps = speedBefore,
        speedAfterMps = speedAfter,
        peakAccelerationMps2 = peak,
        averageAccelerationMps2 = peak,
        reviewPriorityScore = score,
        hasMissingAccuracy = false,
        routePoints = emptyList(),
        speedLimitDetails = details
    )

    private companion object {
        const val EPSILON = 1e-9
    }
}
