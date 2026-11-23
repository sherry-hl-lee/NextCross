package com.fit5120tp10.nextcross.client.speedlimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitWarningEvaluatorTest {
    @Test
    fun doesNotWarnWhenLimitUnavailable() {
        val context = SpeedLimitContext(limitKmh = null, status = SpeedLimitStatus.Unknown)
        assertEquals(
            SpeedLimitWarningState.Inactive,
            SpeedLimitWarningEvaluator.evaluate(context, recordedSpeedMps = 20.0)
        )
        assertFalse(context.isUsableForWarning)
        val assessment = SpeedLimitWarningEvaluator.assess(context, recordedSpeedMps = 20.0)
        assertNull(assessment.severity)
        assertNull(assessment.excessKmh)
    }

    @Test
    fun warnsOnlyWhenRecordedSpeedExceedsUsableLimit() {
        val context = SpeedLimitContext(limitKmh = 40, status = SpeedLimitStatus.Static)
        assertTrue(context.isUsableForWarning)
        // 40 km/h ~= 11.11 m/s
        assertEquals(
            SpeedLimitWarningState.Inactive,
            SpeedLimitWarningEvaluator.evaluate(context, recordedSpeedMps = 10.0)
        )
        assertEquals(
            SpeedLimitWarningState.Active,
            SpeedLimitWarningEvaluator.evaluate(context, recordedSpeedMps = 15.0)
        )
    }

    @Test
    fun endsWarningWhenSpeedReturnsAtOrBelowLimit() {
        val context = SpeedLimitContext(limitKmh = 50, status = SpeedLimitStatus.Scheduled)
        assertEquals(
            SpeedLimitWarningState.Active,
            SpeedLimitWarningEvaluator.evaluate(context, recordedSpeedMps = 20.0)
        )
        assertEquals(
            SpeedLimitWarningState.Inactive,
            SpeedLimitWarningEvaluator.evaluate(context, recordedSpeedMps = 10.0)
        )
    }

    @Test
    fun gradesExceedanceBandsByExcessKmh() {
        assertEquals(SpeedLimitExceedanceSeverity.Low, SpeedLimitWarningEvaluator.severityForExcessKmh(0.1))
        assertEquals(SpeedLimitExceedanceSeverity.Low, SpeedLimitWarningEvaluator.severityForExcessKmh(9.9))
        assertEquals(SpeedLimitExceedanceSeverity.Moderate, SpeedLimitWarningEvaluator.severityForExcessKmh(10.0))
        assertEquals(SpeedLimitExceedanceSeverity.Moderate, SpeedLimitWarningEvaluator.severityForExcessKmh(24.9))
        assertEquals(SpeedLimitExceedanceSeverity.High, SpeedLimitWarningEvaluator.severityForExcessKmh(25.0))
        assertEquals(SpeedLimitExceedanceSeverity.High, SpeedLimitWarningEvaluator.severityForExcessKmh(34.9))
        assertEquals(SpeedLimitExceedanceSeverity.Severe, SpeedLimitWarningEvaluator.severityForExcessKmh(35.0))
        assertEquals(SpeedLimitExceedanceSeverity.Severe, SpeedLimitWarningEvaluator.severityForExcessKmh(44.9))
        assertEquals(SpeedLimitExceedanceSeverity.Extreme, SpeedLimitWarningEvaluator.severityForExcessKmh(45.0))
        assertEquals(SpeedLimitExceedanceSeverity.Extreme, SpeedLimitWarningEvaluator.severityForExcessKmh(80.0))
    }

    @Test
    fun assessAttachesSeverityOnlyWhenActive() {
        val context = SpeedLimitContext(limitKmh = 40, status = SpeedLimitStatus.Static)
        // 50 km/h = 50/3.6 m/s → excess 10 → Moderate
        val over = SpeedLimitWarningEvaluator.assess(context, recordedSpeedMps = 50.0 / 3.6)
        assertEquals(SpeedLimitWarningState.Active, over.state)
        assertEquals(SpeedLimitExceedanceSeverity.Moderate, over.severity)
        assertEquals(10.0, over.excessKmh!!, 0.01)

        val under = SpeedLimitWarningEvaluator.assess(context, recordedSpeedMps = 40.0 / 3.6)
        assertEquals(SpeedLimitWarningState.Inactive, under.state)
        assertNull(under.severity)
        assertNull(under.excessKmh)
    }
}
