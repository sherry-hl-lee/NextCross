package com.fit5120tp10.nextcross.client.aireflection

import com.fit5120tp10.nextcross.client.posttrip.PostTripReviewEvent
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.posttrip.ReviewPriority
import com.fit5120tp10.nextcross.posttripanalysis.SpeedLimitExceedanceDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionEventFactsFactoryTest {

    private val tripStart = 1_789_000_000_000L

    private fun event(
        type: ReviewEventType = ReviewEventType.RAPID_DECELERATION,
        peak: Double? = -13.9,
        missingAccuracy: Boolean = true,
        startOffsetMillis: Long = 180_000L,
        eventId: String = "trip-1:RAPID_DECELERATION:41:43",
        durationMillis: Long = 1_000L,
        details: SpeedLimitExceedanceDetails? = null,
        score: Double? = 88.8
    ) = PostTripReviewEvent(
        eventId = eventId,
        tripId = "trip-1",
        analysisRevision = "rev-1",
        type = type,
        startSampleIndex = 41,
        endSampleIndex = 43,
        startTimestampMillis = tripStart + startOffsetMillis,
        endTimestampMillis = tripStart + startOffsetMillis + durationMillis,
        durationSeconds = durationMillis / 1000.0,
        speedBeforeMps = 16.667,
        speedAfterMps = 2.778,
        peakAccelerationMps2 = peak,
        averageAccelerationMps2 = -13.9,
        reviewPriorityScore = score,
        hasMissingAccuracy = missingAccuracy,
        routePoints = emptyList(),
        speedLimitDetails = details
    )

    private fun speedEvent(
        eventId: String = "trip-1:SPEED_LIMIT_EXCEEDANCE:E_SPEED_0001",
        startOffsetMillis: Long = 600_000L,
        durationMillis: Long = 40_000L,
        limit: Int = 60,
        peak: Double = 74.0,
        average: Double = 8.0,
        status: String = "static"
    ) = event(
        type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE,
        peak = null,
        missingAccuracy = false,
        startOffsetMillis = startOffsetMillis,
        eventId = eventId,
        durationMillis = durationMillis,
        details = SpeedLimitExceedanceDetails(limit, peak, average, status),
        score = null
    )

    @Test
    fun `a braking event becomes whitelisted facts`() {
        val facts = ReflectionEventFactsFactory.from(event(), tripStart, TripEventCounts(1, 1, 0))
        assertNotNull(facts)
        requireNotNull(facts)
        assertEquals(ReflectionEventType.RAPID_DECELERATION, facts.eventType)
        assertEquals(180.0, facts.timeAfterDepartureSeconds, 0.001)
        assertEquals(60.0, facts.speedBeforeKmh!!, 0.1)
        assertEquals(10.0, facts.speedAfterKmh!!, 0.1)
        assertEquals(CLIENT_DETECTOR_VERSION, facts.detectorVersion)
        assertNull(facts.speedLimit)
        assertNull(facts.roadContext)
    }

    @Test
    fun `a speed-limit event without backend metrics is not described`() {
        assertNull(
            ReflectionEventFactsFactory.from(
                event(type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE, peak = null), tripStart
            )
        )
    }

    @Test
    fun `a speed-limit event with backend metrics becomes speed facts only`() {
        val facts = ReflectionEventFactsFactory.from(speedEvent(status = "scheduled"), tripStart, TripEventCounts(0, 0, 1))
        requireNotNull(facts)
        assertEquals(ReflectionEventType.SPEED_LIMIT_EXCEEDANCE, facts.eventType)
        assertEquals("speed-v1", facts.detectorVersion)
        assertEquals(600.0, facts.timeAfterDepartureSeconds, 0.001)
        assertEquals(40.0, facts.durationSeconds, 0.001)
        assertNull(facts.speedBeforeKmh)
        assertNull(facts.speedAfterKmh)
        assertNull(facts.peakAccelerationMps2)
        assertEquals(ReflectionSpeedLimitMetrics(60, 74.0, 8.0), facts.speedLimit)
        assertEquals(ReflectionRoadContext(60, ReflectionLimitStatus.SCHEDULED), facts.roadContext)
        assertEquals(ReflectionDataQuality.GOOD, facts.dataQuality)
    }

    @Test
    fun `a manoeuvre during speeding carries the event-time limit`() {
        val braking = event(startOffsetMillis = 610_000L)
        val ranking = ReviewPriority.rank(listOf(braking, speedEvent()), tripStart)
        val item = requireNotNull(ranking.item(braking.eventId))
        val road = ReflectionEventFactsFactory.roadContextFor(item)
        assertEquals(ReflectionRoadContext(60, ReflectionLimitStatus.STATIC), road)
        val facts = ReflectionEventFactsFactory.from(braking, tripStart, null, road)
        assertEquals(road, facts?.roadContext)
    }

    @Test
    fun `a manoeuvre outside speeding has no road context`() {
        val braking = event(startOffsetMillis = 10_000L)
        val ranking = ReviewPriority.rank(listOf(braking, speedEvent()), tripStart)
        assertNull(ReflectionEventFactsFactory.roadContextFor(requireNotNull(ranking.item(braking.eventId))))
    }

    @Test
    fun `an event with no peak acceleration is not described at all`() {
        assertNull(ReflectionEventFactsFactory.from(event(peak = null), tripStart))
    }

    @Test
    fun `missing accuracy is reported as UNKNOWN rather than hidden`() {
        val unknown = ReflectionEventFactsFactory.from(event(missingAccuracy = true), tripStart)
        assertEquals(ReflectionDataQuality.UNKNOWN, unknown?.dataQuality)
        val good = ReflectionEventFactsFactory.from(event(missingAccuracy = false), tripStart)
        assertEquals(ReflectionDataQuality.GOOD, good?.dataQuality)
    }

    @Test
    fun `an event before the recorded trip start never yields a negative offset`() {
        val facts = ReflectionEventFactsFactory.from(event(), tripStart + 60_000L)
        assertTrue((facts?.timeAfterDepartureSeconds ?: -1.0) >= 0.0)
    }

    @Test
    fun `a missing trip start falls back to the event itself`() {
        val facts = ReflectionEventFactsFactory.from(event(), null)
        assertEquals(0.0, facts?.timeAfterDepartureSeconds ?: -1.0, 0.001)
    }

    @Test
    fun `a long event id keeps the discriminating tail`() {
        val longId = "x".repeat(140) + ":RAPID_DECELERATION:41:43"
        val facts = ReflectionEventFactsFactory.from(event(eventId = longId), tripStart)
        requireNotNull(facts)
        assertEquals(MAX_EVENT_ID_CHARACTERS, facts.eventId.length)
        assertTrue(facts.eventId.endsWith(":RAPID_DECELERATION:41:43"))
    }

    @Test
    fun `counts cover all three event families`() {
        val counts = ReflectionEventFactsFactory.countsOf(
            listOf(
                event(type = ReviewEventType.RAPID_ACCELERATION, peak = 11.1),
                event(type = ReviewEventType.RAPID_DECELERATION),
                event(type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE)
            )
        )
        assertEquals(1, counts.rapidAccelerationCount)
        assertEquals(1, counts.rapidDecelerationCount)
        assertEquals(1, counts.speedLimitExceedanceCount)
    }

    @Test
    fun `only events with enough metrics are offered for reflection`() {
        val acceleration = event(type = ReviewEventType.RAPID_ACCELERATION, peak = 11.1, eventId = "a")
        val speedWithoutMetrics = event(type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE, peak = null, eventId = "s0")
        val speedWithMetrics = speedEvent(eventId = "s1")
        val reflectable = ReflectionEventFactsFactory.reflectableEvents(
            listOf(acceleration, speedWithoutMetrics, speedWithMetrics)
        )
        assertEquals(listOf(acceleration, speedWithMetrics), reflectable)
    }

    @Test
    fun `candidates are the top reflectable event of each family in a fixed order`() {
        val weakBrake = event(eventId = "brake-weak", startOffsetMillis = 1_000L, score = 40.0)
        val strongBrake = event(eventId = "brake-strong", startOffsetMillis = 2_000L, score = 70.0)
        val acceleration = event(
            type = ReviewEventType.RAPID_ACCELERATION, peak = 11.1, eventId = "accel", startOffsetMillis = 3_000L
        )
        val speeding = speedEvent(eventId = "speed", startOffsetMillis = 900_000L)
        val candidates = ReflectionEventFactsFactory.candidates(
            ReviewPriority.rank(listOf(weakBrake, acceleration, strongBrake, speeding), tripStart)
        )
        assertEquals(listOf("speed", "brake-strong", "accel"), candidates.map { it.eventId })
    }

    @Test
    fun `a family whose top event cannot be discussed falls back to its next event`() {
        val noPeak = event(eventId = "brake-no-peak", peak = null, score = 99.0)
        val usable = event(eventId = "brake-usable", startOffsetMillis = 5_000L, score = 10.0)
        val candidates = ReflectionEventFactsFactory.candidates(ReviewPriority.rank(listOf(noPeak, usable), tripStart))
        assertEquals(listOf("brake-usable"), candidates.map { it.eventId })
    }
}
