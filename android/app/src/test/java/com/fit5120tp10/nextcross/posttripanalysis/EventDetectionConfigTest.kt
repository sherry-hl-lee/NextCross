package com.fit5120tp10.nextcross.posttripanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EventDetectionConfigTest {
    @Test
    fun defaultsAreExplicitExperimentalParameters() {
        val config = EventDetectionConfig()

        assertEquals(3.0, config.rapidAccelerationThresholdMps2, 0.0)
        assertEquals(-3.0, config.rapidDecelerationThresholdMps2, 0.0)
        assertEquals(1.0, config.continuationAccelerationMagnitudeMps2, 0.0)
        assertEquals(0.5, config.minSampleIntervalSeconds, 0.0)
        assertEquals(2.5, config.maxSampleGapSeconds, 0.0)
        assertEquals(1.0, config.mergeGapSeconds, 0.0)
        assertEquals(1.0, config.minEventDurationSeconds, 0.0)
        assertEquals(0.40, config.accelerationWeight, 0.0)
        assertEquals(0.25, config.speedChangeWeight, 0.0)
        assertEquals(0.25, config.eventSpeedWeight, 0.0)
        assertEquals(0.10, config.durationWeight, 0.0)
        assertEquals(100.0 / 3.6, config.eventSpeedReferenceMps, 1e-12)
        assertEquals(
            1.0,
            config.accelerationWeight + config.speedChangeWeight + config.durationWeight + config.eventSpeedWeight,
            1e-9
        )
    }

    @Test
    fun asymmetricStartThresholdsAndInclusiveIntervalBoundariesAreAllowed() {
        val config = EventDetectionConfig(
            rapidAccelerationThresholdMps2 = 4.0,
            rapidDecelerationThresholdMps2 = -2.0,
            continuationAccelerationMagnitudeMps2 = 2.0,
            minSampleIntervalSeconds = 1.0,
            maxSampleGapSeconds = 1.0,
            mergeGapSeconds = 0.0,
            accelerationWeight = 1.0,
            speedChangeWeight = 0.0,
            durationWeight = 0.0,
            eventSpeedWeight = 0.0
        )

        assertEquals(4.0, config.rapidAccelerationThresholdMps2, 0.0)
        assertEquals(-2.0, config.rapidDecelerationThresholdMps2, 0.0)
        assertEquals(0.0, config.mergeGapSeconds, 0.0)
    }

    @Test
    fun positiveParametersRejectZeroNegativeAndNonFiniteValues() {
        val setters: List<Pair<String, (Double) -> EventDetectionConfig>> = listOf(
            "acceleration threshold" to { value -> EventDetectionConfig(rapidAccelerationThresholdMps2 = value) },
            "continuation" to { value -> EventDetectionConfig(continuationAccelerationMagnitudeMps2 = value) },
            "minimum interval" to { value -> EventDetectionConfig(minSampleIntervalSeconds = value) },
            "maximum gap" to { value -> EventDetectionConfig(maxSampleGapSeconds = value) },
            "minimum event duration" to { value -> EventDetectionConfig(minEventDurationSeconds = value) },
            "horizontal accuracy" to { value -> EventDetectionConfig(maxHorizontalAccuracyMeters = value) },
            "speed accuracy" to { value -> EventDetectionConfig(maxSpeedAccuracyMps = value) },
            "acceleration reference" to { value -> EventDetectionConfig(severeAccelerationReferenceMps2 = value) },
            "speed change reference" to { value -> EventDetectionConfig(severeSpeedChangeReferenceMps = value) },
            "duration reference" to { value -> EventDetectionConfig(longEventReferenceSeconds = value) },
            "event speed reference" to { value -> EventDetectionConfig(eventSpeedReferenceMps = value) }
        )
        for ((name, setter) in setters) {
            for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertRejected("$name = $value") { setter(value) }
            }
        }
    }

    @Test
    fun brakingThresholdMustBeFiniteAndNegative() {
        for (value in listOf(0.0, 1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertRejected("braking threshold = $value") {
                EventDetectionConfig(rapidDecelerationThresholdMps2 = value)
            }
        }
    }

    @Test
    fun continuationCannotExceedEitherStartThreshold() {
        assertRejected("positive start") {
            EventDetectionConfig(rapidAccelerationThresholdMps2 = 2.0, continuationAccelerationMagnitudeMps2 = 2.1)
        }
        assertRejected("negative start") {
            EventDetectionConfig(rapidDecelerationThresholdMps2 = -2.0, continuationAccelerationMagnitudeMps2 = 2.1)
        }
    }

    @Test
    fun maximumGapCannotBeShorterThanMinimumInterval() {
        assertRejected("inverted interval bounds") {
            EventDetectionConfig(minSampleIntervalSeconds = 1.0, maxSampleGapSeconds = 0.9)
        }
    }

    @Test
    fun mergeGapAllowsZeroButRejectsNegativeAndNonFiniteValues() {
        assertEquals(0.0, EventDetectionConfig(mergeGapSeconds = 0.0).mergeGapSeconds, 0.0)
        for (value in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertRejected("merge gap = $value") { EventDetectionConfig(mergeGapSeconds = value) }
        }
    }

    @Test
    fun weightsMustBeFiniteNonNegativeAndSumToOne() {
        assertRejected("sum below one") { EventDetectionConfig(accelerationWeight = 0.3) }
        assertRejected("sum above one") { EventDetectionConfig(durationWeight = 0.2) }
        assertRejected("event speed pushes sum above one") { EventDetectionConfig(eventSpeedWeight = 0.3) }
        assertRejected("negative despite sum one") {
            EventDetectionConfig(
                accelerationWeight = -0.1, speedChangeWeight = 1.0, durationWeight = 0.1, eventSpeedWeight = 0.0
            )
        }
        assertRejected("NaN") { EventDetectionConfig(accelerationWeight = Double.NaN) }
        assertRejected("infinity") { EventDetectionConfig(speedChangeWeight = Double.POSITIVE_INFINITY) }
        assertRejected("event speed NaN") { EventDetectionConfig(eventSpeedWeight = Double.NaN) }
    }

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException for $label")
        } catch (_: IllegalArgumentException) {
            // Only validation exceptions satisfy this assertion.
        }
    }
}
