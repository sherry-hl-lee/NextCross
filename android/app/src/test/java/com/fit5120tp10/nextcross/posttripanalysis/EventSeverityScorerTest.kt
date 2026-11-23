package com.fit5120tp10.nextcross.posttripanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default weights (review priority v2): peak acceleration 40%, speed change 25%,
 * event speed 25% (reference 100 km/h), duration 10%. In [manoeuvre] the event speed defaults
 * to the speed change, so a 4 m/s change has an event-speed component of 4 / (100 / 3.6) = 0.144.
 */
class EventSeverityScorerTest {
    private val scorer = EventSeverityScorer()

    @Test
    fun exampleAProducesExpectedComponentsAndScore() {
        val event = scorer.score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5))
        val score = event.scoreEvidence

        assertEquals(3.2 / 6.0, score.accelerationComponent, EPSILON)
        assertEquals(0.4, score.speedChangeComponent, EPSILON)
        assertEquals(0.375, score.durationComponent, EPSILON)
        assertEquals(0.144, score.eventSpeedComponent, EPSILON)
        assertEquals(40.0 * (3.2 / 6.0) + 10.0 + 3.6 + 3.75, score.total, EPSILON)
    }

    @Test
    fun exampleBProducesExpectedComponentsAndScore() {
        val event = scorer.score(manoeuvre(peak = -5.0, speedChange = 10.0, duration = 3.0))
        val score = event.scoreEvidence

        assertEquals(5.0 / 6.0, score.accelerationComponent, EPSILON)
        assertEquals(1.0, score.speedChangeComponent, EPSILON)
        assertEquals(0.75, score.durationComponent, EPSILON)
        assertEquals(0.36, score.eventSpeedComponent, EPSILON)
        assertEquals(40.0 * (5.0 / 6.0) + 25.0 + 9.0 + 7.5, score.total, EPSILON)
    }

    @Test
    fun exampleBScoresHigherThanExampleA() {
        val exampleA = scorer.score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5))
        val exampleB = scorer.score(manoeuvre(peak = -5.0, speedChange = 10.0, duration = 3.0))

        assertTrue(exampleB.severityScore > exampleA.severityScore)
    }

    @Test
    fun contributionsAreWeightedPointsRatherThanFractions() {
        val score = scorer.score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5))
            .scoreEvidence

        assertEquals(40.0 * (3.2 / 6.0), score.accelerationContribution, EPSILON)
        assertEquals(10.0, score.speedChangeContribution, EPSILON)
        assertEquals(3.75, score.durationContribution, EPSILON)
        assertEquals(3.6, score.eventSpeedContribution, EPSILON)
        assertEquals(
            score.total,
            score.accelerationContribution + score.speedChangeContribution +
                score.durationContribution + score.eventSpeedContribution,
            EPSILON
        )
    }

    @Test
    fun allComponentsSaturateAtTheirReferenceAndAbove() {
        val atReference = scorer.score(
            manoeuvre(peak = -6.0, speedChange = 10.0, duration = 4.0, eventSpeed = 100.0 / 3.6)
        )
        val aboveReference = scorer.score(manoeuvre(peak = -12.0, speedChange = 40.0, duration = 8.0))

        for (event in listOf(atReference, aboveReference)) {
            val score = event.scoreEvidence
            assertEquals(1.0, score.accelerationComponent, EPSILON)
            assertEquals(1.0, score.speedChangeComponent, EPSILON)
            assertEquals(1.0, score.durationComponent, EPSILON)
            assertEquals(1.0, score.eventSpeedComponent, EPSILON)
            assertEquals(40.0, score.accelerationContribution, EPSILON)
            assertEquals(25.0, score.speedChangeContribution, EPSILON)
            assertEquals(10.0, score.durationContribution, EPSILON)
            assertEquals(25.0, score.eventSpeedContribution, EPSILON)
            assertEquals(100.0, score.total, EPSILON)
        }
    }

    @Test
    fun saturatedAccelerationDoesNotSaturateOtherComponents() {
        val score = scorer.score(manoeuvre(peak = -12.0, speedChange = 4.0, duration = 1.5))
            .scoreEvidence

        assertEquals(1.0, score.accelerationComponent, EPSILON)
        assertEquals(0.4, score.speedChangeComponent, EPSILON)
        assertEquals(0.375, score.durationComponent, EPSILON)
        assertEquals(0.144, score.eventSpeedComponent, EPSILON)
        assertEquals(40.0 + 10.0 + 3.6 + 3.75, score.total, EPSILON)
    }

    @Test
    fun accelerationAndDecelerationWithSameMagnitudesHaveSameScore() {
        val deceleration = scorer.score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5))
        val acceleration = scorer.score(manoeuvre(peak = 3.2, speedChange = 4.0, duration = 1.5))

        assertEquals(deceleration.severityScore, acceleration.severityScore, EPSILON)
        assertEquals(-3.2, deceleration.peakAccelerationMps2, EPSILON)
        assertEquals(3.2, acceleration.peakAccelerationMps2, EPSILON)
        assertTrue(deceleration.averageAccelerationMps2 < 0.0)
        assertTrue(acceleration.averageAccelerationMps2 > 0.0)
    }

    @Test
    fun scoreKeepsTheOriginalManoeuvreAndEveryMetricUnchanged() {
        val original = manoeuvre(peak = -5.0, speedChange = 10.0, duration = 3.0)
            .copy(mergedGapSeconds = 0.5, detectorVersion = "fixture-detector-v2")
        val beforeScoring = original.copy()

        val event = scorer.score(original)

        assertSame(original, event.manoeuvre)
        assertEquals(beforeScoring, original)
        assertEquals(beforeScoring, event.manoeuvre)
        assertEquals(10.0, event.deltaSpeedMps, EPSILON)
        assertEquals(-5.0, event.peakAccelerationMps2, EPSILON)
        assertEquals(-10.0 / 3.0, event.averageAccelerationMps2, EPSILON)
    }

    @Test
    fun customWeightsChangeTheScoreAndContributionPoints() {
        val config = EventDetectionConfig(
            accelerationWeight = 0.2,
            speedChangeWeight = 0.3,
            durationWeight = 0.5,
            eventSpeedWeight = 0.0
        )
        val score = EventSeverityScorer(config)
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertEquals(100.0 * 0.2 * (3.2 / 6.0), score.accelerationContribution, EPSILON)
        assertEquals(12.0, score.speedChangeContribution, EPSILON)
        assertEquals(18.75, score.durationContribution, EPSILON)
        assertEquals(0.0, score.eventSpeedContribution, EPSILON)
        assertEquals(41.41666666666667, score.total, EPSILON)
    }

    @Test
    fun zeroWeightsContributeNoPoints() {
        val config = EventDetectionConfig(
            accelerationWeight = 1.0,
            speedChangeWeight = 0.0,
            durationWeight = 0.0,
            eventSpeedWeight = 0.0
        )
        val score = EventSeverityScorer(config)
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertEquals(100.0 * (3.2 / 6.0), score.total, EPSILON)
        assertEquals(0.0, score.speedChangeContribution, EPSILON)
        assertEquals(0.0, score.durationContribution, EPSILON)
        assertEquals(0.0, score.eventSpeedContribution, EPSILON)
    }

    @Test
    fun changingAccelerationReferenceOnlyChangesAccelerationComponent() {
        val score = EventSeverityScorer(EventDetectionConfig(severeAccelerationReferenceMps2 = 8.0))
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertEquals(0.4, score.accelerationComponent, EPSILON)
        assertEquals(0.4, score.speedChangeComponent, EPSILON)
        assertEquals(0.375, score.durationComponent, EPSILON)
        assertEquals(0.144, score.eventSpeedComponent, EPSILON)
        assertEquals(16.0 + 10.0 + 3.6 + 3.75, score.total, EPSILON)
    }

    @Test
    fun changingSpeedReferenceOnlyChangesSpeedComponent() {
        val score = EventSeverityScorer(EventDetectionConfig(severeSpeedChangeReferenceMps = 8.0))
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertEquals(3.2 / 6.0, score.accelerationComponent, EPSILON)
        assertEquals(0.5, score.speedChangeComponent, EPSILON)
        assertEquals(0.375, score.durationComponent, EPSILON)
        assertEquals(0.144, score.eventSpeedComponent, EPSILON)
        assertEquals(40.0 * (3.2 / 6.0) + 12.5 + 3.6 + 3.75, score.total, EPSILON)
    }

    @Test
    fun changingDurationReferenceOnlyChangesDurationComponent() {
        val score = EventSeverityScorer(EventDetectionConfig(longEventReferenceSeconds = 3.0))
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertEquals(3.2 / 6.0, score.accelerationComponent, EPSILON)
        assertEquals(0.4, score.speedChangeComponent, EPSILON)
        assertEquals(0.5, score.durationComponent, EPSILON)
        assertEquals(0.144, score.eventSpeedComponent, EPSILON)
        assertEquals(40.0 * (3.2 / 6.0) + 10.0 + 3.6 + 5.0, score.total, EPSILON)
    }

    @Test
    fun changingEventSpeedReferenceOnlyChangesEventSpeedComponent() {
        val score = EventSeverityScorer(EventDetectionConfig(eventSpeedReferenceMps = 50.0 / 3.6))
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertEquals(3.2 / 6.0, score.accelerationComponent, EPSILON)
        assertEquals(0.4, score.speedChangeComponent, EPSILON)
        assertEquals(0.375, score.durationComponent, EPSILON)
        assertEquals(0.288, score.eventSpeedComponent, EPSILON)
        assertEquals(40.0 * (3.2 / 6.0) + 10.0 + 7.2 + 3.75, score.total, EPSILON)
    }

    @Test
    fun theSameBrakingAtAHigherSpeedHasAHigherReviewPriority() {
        // 100 -> 70 km/h and 40 -> 10 km/h: identical peak, change and duration.
        val fromHundred = scorer.score(
            manoeuvre(peak = -4.0, speedChange = 30.0 / 3.6, duration = 2.0, eventSpeed = 100.0 / 3.6)
        )
        val fromForty = scorer.score(
            manoeuvre(peak = -4.0, speedChange = 30.0 / 3.6, duration = 2.0, eventSpeed = 40.0 / 3.6)
        )

        assertEquals(1.0, fromHundred.scoreEvidence.eventSpeedComponent, EPSILON)
        assertEquals(0.4, fromForty.scoreEvidence.eventSpeedComponent, EPSILON)
        assertEquals(15.0, fromHundred.severityScore - fromForty.severityScore, EPSILON)
    }

    @Test
    fun eventSpeedIsTheHigherOfTheSpeedsBeforeAndAfter() {
        // Acceleration 20 -> 60 km/h uses the speed it reached; braking 60 -> 20 km/h where it started.
        val acceleration = scorer.score(
            manoeuvre(peak = 3.2, speedChange = 40.0 / 3.6, duration = 3.0, eventSpeed = 60.0 / 3.6)
        )
        val braking = scorer.score(
            manoeuvre(peak = -3.2, speedChange = 40.0 / 3.6, duration = 3.0, eventSpeed = 60.0 / 3.6)
        )

        assertEquals(20.0 / 3.6, acceleration.speedBeforeMps, EPSILON)
        assertEquals(0.6, acceleration.scoreEvidence.eventSpeedComponent, EPSILON)
        assertEquals(20.0 / 3.6, braking.speedAfterMps, EPSILON)
        assertEquals(0.6, braking.scoreEvidence.eventSpeedComponent, EPSILON)
        assertEquals(acceleration.severityScore, braking.severityScore, EPSILON)
    }

    @Test
    fun detectionThresholdChangesDoNotChangeTheScoringFormula() {
        val original = manoeuvre(peak = -5.0, speedChange = 10.0, duration = 3.0)
        val config = EventDetectionConfig(
            rapidAccelerationThresholdMps2 = 4.0,
            rapidDecelerationThresholdMps2 = -4.0
        )

        assertEquals(
            scorer.score(original).severityScore,
            EventSeverityScorer(config).score(original).severityScore,
            EPSILON
        )
    }

    @Test
    fun configSnapshotIsTheExactConfigUsedForScoring() {
        val config = EventDetectionConfig(
            maxSampleGapSeconds = 3.0,
            mergeGapSeconds = 0.25,
            severeAccelerationReferenceMps2 = 8.0,
            severeSpeedChangeReferenceMps = 12.0,
            longEventReferenceSeconds = 5.0,
            accelerationWeight = 0.25,
            speedChangeWeight = 0.5,
            durationWeight = 0.15,
            eventSpeedReferenceMps = 30.0,
            eventSpeedWeight = 0.10
        )
        val score = EventSeverityScorer(config)
            .score(manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)).scoreEvidence

        assertSame(config, score.configSnapshot)
        assertEquals(config, score.configSnapshot)
        assertEquals("posttrip-review-priority-v2", score.scoringVersion)
    }

    @Test
    fun tinyAndLargeValidInputsAlwaysProduceBoundedFiniteComponentsAndPoints() {
        val events = listOf(
            manoeuvre(peak = 0.000001, speedChange = 0.000001, duration = 1.0),
            manoeuvre(peak = -12.0, speedChange = 40.0, duration = 8.0)
        )
        for (input in events) {
            val score = scorer.score(input).scoreEvidence
            for (component in listOf(
                score.accelerationComponent, score.speedChangeComponent,
                score.durationComponent, score.eventSpeedComponent
            )) {
                assertTrue(component.isFinite() && component in 0.0..1.0)
            }
            for (points in listOf(
                score.total, score.accelerationContribution, score.speedChangeContribution,
                score.durationContribution, score.eventSpeedContribution
            )) {
                assertTrue(points.isFinite() && points in 0.0..100.0)
            }
        }
    }

    @Test
    fun scoringIsDeterministicAndDoesNotAccumulateState() {
        val original = manoeuvre(peak = -3.2, speedChange = 4.0, duration = 1.5)
        val first = scorer.score(original)
        scorer.score(manoeuvre(peak = -12.0, speedChange = 40.0, duration = 8.0))

        assertEquals(first, scorer.score(original))
    }

    /** [eventSpeed] is the higher of the two speeds; by default the manoeuvre starts or ends at 0. */
    private fun manoeuvre(
        peak: Double,
        speedChange: Double,
        duration: Double,
        eventSpeed: Double = speedChange
    ): DetectedManoeuvre {
        require(eventSpeed >= speedChange)
        val isAcceleration = peak > 0.0
        val speedBefore = if (isAcceleration) eventSpeed - speedChange else eventSpeed
        val speedAfter = if (isAcceleration) eventSpeed else eventSpeed - speedChange
        return DetectedManoeuvre(
            eventId = "event-1",
            tripId = "trip-1",
            type = if (isAcceleration) DrivingEventType.RAPID_ACCELERATION else DrivingEventType.RAPID_DECELERATION,
            startTimeMillis = 1_000L,
            endTimeMillis = 1_000L + (duration * 1_000).toLong(),
            startSampleIndex = 1,
            endSampleIndex = 4,
            durationSeconds = duration,
            speedBeforeMps = speedBefore,
            speedAfterMps = speedAfter,
            peakAccelerationMps2 = peak,
            averageAccelerationMps2 = (speedAfter - speedBefore) / duration,
            endReason = EventEndReason.SETTLED,
            hasMissingAccuracy = false,
            usesElapsedRealtime = true
        )
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
