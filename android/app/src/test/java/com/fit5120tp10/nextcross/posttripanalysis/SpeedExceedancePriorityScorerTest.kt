package com.fit5120tp10.nextcross.posttripanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Defaults: peak exceedance 45% (reference 30% of the limit), average exceedance 25%
 * (reference 10 km/h), duration 30% (reference 60 s), +10 points while a time-based limit
 * was active.
 */
class SpeedExceedancePriorityScorerTest {
    private val scorer = SpeedExceedancePriorityScorer()

    @Test
    fun staticLimitExampleProducesExpectedComponentsAndScore() {
        // 74 km/h peak in a 60 zone (+23.3%), 8 km/h above on average, for 40 s.
        val score = scorer.score(details(limit = 60, peak = 74.0, average = 8.0), durationSeconds = 40.0)

        assertEquals((14.0 / 60.0 * 100.0) / 30.0, score.peakPercentComponent, EPSILON)
        assertEquals(0.8, score.averageExceedanceComponent, EPSILON)
        assertEquals(40.0 / 60.0, score.durationComponent, EPSILON)
        assertEquals(35.0, score.peakPercentContribution, EPSILON)
        assertEquals(20.0, score.averageExceedanceContribution, EPSILON)
        assertEquals(20.0, score.durationContribution, EPSILON)
        assertEquals(0.0, score.timeBasedLimitBonus, EPSILON)
        assertEquals(75.0, score.total, EPSILON)
        assertEquals("speed-review-priority-v1", score.scoringVersion)
    }

    @Test
    fun anActiveTimeBasedLimitAddsItsBonus() {
        // 50 km/h peak while a 40 km/h time-based limit was active (+25%), 6 above on average, 20 s.
        val score = scorer.score(
            details(limit = 40, peak = 50.0, average = 6.0, status = "scheduled"),
            durationSeconds = 20.0
        )

        assertEquals(37.5, score.peakPercentContribution, EPSILON)
        assertEquals(15.0, score.averageExceedanceContribution, EPSILON)
        assertEquals(10.0, score.durationContribution, EPSILON)
        assertEquals(10.0, score.timeBasedLimitBonus, EPSILON)
        assertEquals(72.5, score.total, EPSILON)
    }

    @Test
    fun inactiveAndStaticLimitsGetNoBonus() {
        for (status in listOf("static", "inactive")) {
            val score = scorer.score(details(status = status), durationSeconds = 10.0)
            assertEquals(0.0, score.timeBasedLimitBonus, EPSILON)
        }
    }

    @Test
    fun theSameKilometresOverRankHigherUnderALowerLimit() {
        val schoolStreet = scorer.score(details(limit = 40, peak = 50.0, average = 5.0), durationSeconds = 30.0)
        val freeway = scorer.score(details(limit = 100, peak = 110.0, average = 5.0), durationSeconds = 30.0)

        assertTrue(schoolStreet.total > freeway.total)
    }

    @Test
    fun componentsSaturateAndTheTotalIsCappedAtOneHundred() {
        val score = scorer.score(
            details(limit = 60, peak = 100.0, average = 20.0, status = "scheduled"),
            durationSeconds = 600.0
        )

        assertEquals(1.0, score.peakPercentComponent, EPSILON)
        assertEquals(1.0, score.averageExceedanceComponent, EPSILON)
        assertEquals(1.0, score.durationComponent, EPSILON)
        assertEquals(100.0, score.total, EPSILON)
    }

    @Test
    fun scoringIsDeterministic() {
        val input = details()
        assertEquals(scorer.score(input, 12.0), scorer.score(input, 12.0))
    }

    @Test
    fun detailsMirrorTheBackendSpeedEventRules() {
        val valid = details(limit = 60, peak = 74.0, average = 8.0)
        assertEquals(14.0, valid.peakExceedanceKmh, EPSILON)
        assertEquals(14.0 / 60.0 * 100.0, valid.peakExceedancePercent, EPSILON)

        assertRejected("peak not above the limit") { details(limit = 60, peak = 60.0, average = 0.0) }
        assertRejected("average above the peak exceedance") { details(limit = 60, peak = 70.0, average = 10.5) }
        assertRejected("negative average") { details(average = -0.1) }
        assertRejected("limit zero") { details(limit = 0, peak = 10.0, average = 1.0) }
        assertRejected("limit above 200") { details(limit = 201, peak = 250.0, average = 1.0) }
        assertRejected("unknown status") { details(status = "unknown") }
        assertRejected("no-match status") { details(status = "no_match") }
        assertRejected("non-finite peak") { details(peak = Double.NaN) }
    }

    @Test
    fun configWeightsMustBeFiniteNonNegativeAndSumToOne() {
        assertRejected("sum below one") { SpeedExceedancePriorityConfig(peakPercentWeight = 0.3) }
        assertRejected("negative") {
            SpeedExceedancePriorityConfig(peakPercentWeight = -0.1, averageExceedanceWeight = 0.8, durationWeight = 0.3)
        }
        assertRejected("zero reference") { SpeedExceedancePriorityConfig(longExceedanceReferenceSeconds = 0.0) }
        assertRejected("bonus above 100") { SpeedExceedancePriorityConfig(timeBasedLimitBonus = 101.0) }
        assertRejected("negative duration") { scorer.score(details(), durationSeconds = -1.0) }
    }

    private fun details(
        limit: Int = 60,
        peak: Double = 70.0,
        average: Double = 5.0,
        status: String = "static"
    ) = SpeedLimitExceedanceDetails(limit, peak, average, status)

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException for $label")
        } catch (_: IllegalArgumentException) {
        }
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
