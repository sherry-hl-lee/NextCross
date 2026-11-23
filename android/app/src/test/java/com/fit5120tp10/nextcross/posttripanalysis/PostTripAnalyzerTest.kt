package com.fit5120tp10.nextcross.posttripanalysis

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PostTripAnalyzerTest {
    @Test fun scoringConfigurationCannotChangeAnyDetectedBoundaryOrMetric() {
        val samples = samples(5.0, 9.0, 13.0, 13.0, 17.0, 17.0, 17.0, 11.0, 5.0)
        val baseline = PostTripAnalyzer().analyze("trip", samples)
        val changed = PostTripAnalyzer(EventDetectionConfig(
            severeAccelerationReferenceMps2 = 20.0,
            severeSpeedChangeReferenceMps = 30.0,
            longEventReferenceSeconds = 20.0,
            accelerationWeight = 0.2,
            speedChangeWeight = 0.6,
            durationWeight = 0.1,
            eventSpeedReferenceMps = 50.0,
            eventSpeedWeight = 0.1
        )).analyze("trip", samples)

        assertEquals(2, baseline.events.size)
        assertEquals(baseline.events.map { it.manoeuvre }, changed.events.map { it.manoeuvre })
        assertEquals(baseline.issues, changed.issues)
        assertNotEquals(baseline.events.map { it.severityScore }, changed.events.map { it.severityScore })
    }

    @Test fun pipelineRetainsChronologicalEventsSeparatelyFromReviewRanking() {
        val result = PostTripAnalyzer().analyze("trip", samples(0.0, 3.0, 3.0, 3.0, 9.0, 15.0))
        assertEquals(listOf(0, 3), result.events.map { it.startSampleIndex })
        assertEquals(listOf(3, 0), result.rankedEvents.map { it.startSampleIndex })
        assertEquals(result.events.toSet(), result.rankedEvents.toSet())
    }

    @Test fun bothEventTypesKeepSignedMetricsAndInspectableScoreEvidence() {
        val config = EventDetectionConfig()
        val result = PostTripAnalyzer(config).analyze("trip", samples(5.0, 10.0, 15.0, 9.0, 3.0))
        assertEquals(2, result.events.size)
        for (event in result.events) {
            assertEquals(config, event.scoreEvidence.configSnapshot)
            assertEquals(event.manoeuvre.peakAccelerationMps2, event.peakAccelerationMps2, 0.0)
            assertEquals(abs(event.speedAfterMps - event.speedBeforeMps), event.deltaSpeedMps, 0.0)
            assertEquals(
                event.scoreEvidence.accelerationContribution + event.scoreEvidence.speedChangeContribution +
                    event.scoreEvidence.durationContribution + event.scoreEvidence.eventSpeedContribution,
                event.severityScore, 1e-9
            )
        }
        assertTrue(result.events[0].peakAccelerationMps2 > 0)
        assertTrue(result.events[1].peakAccelerationMps2 < 0)
    }

    @Test fun reusedAnalyzerIsDeterministicAndDoesNotBridgeTrips() {
        val analyzer = PostTripAnalyzer()
        val input = samples(0.0, 5.0, 10.0)
        val first = analyzer.analyze("one", input)
        assertEquals(first, analyzer.analyze("one", input))
        val second = analyzer.analyze("two", input)
        assertEquals(1, second.events.size)
        assertEquals("two", second.events.single().tripId)
        assertNotEquals(first.events.single().eventId, second.events.single().eventId)
        assertTrue(analyzer.analyze("three", listOf(PostTripGpsSample(0, 0.0))).events.isEmpty())
    }

    @Test fun missingDataRemainsVisibleEvenWhenThereAreNoEvents() {
        val result = PostTripAnalyzer().analyze("trip", listOf(PostTripGpsSample(0, null)))
        assertTrue(result.events.isEmpty())
        assertEquals(SampleIssueReason.INVALID_SPEED, result.issues.single().reason)
        assertTrue(result.rankedEvents.isEmpty())
    }

    @Test fun generatedTripsPreserveBoundaryAndMetricInvariants() {
        val random = Random(74219)
        repeat(100) { trip ->
            var speed = 20.0
            val input = List(80) { index ->
                speed = (speed + random.nextInt(-7, 8)).coerceAtLeast(0.0)
                PostTripGpsSample(index * 1000L, if (index % 19 == 18) null else speed)
            }
            val result = PostTripAnalyzer().analyze("generated-$trip", input)
            result.events.forEach { event ->
                assertTrue(event.severityScore.isFinite() && event.severityScore in 0.0..100.0)
                assertTrue(event.startSampleIndex < event.endSampleIndex)
                assertTrue(event.durationSeconds >= 1.0)
                val segment = input.subList(event.startSampleIndex, event.endSampleIndex + 1)
                assertTrue(segment.all { it.speedMps != null })
                assertEquals((event.endTimeMillis - event.startTimeMillis) / 1000.0, event.durationSeconds, 1e-9)
                assertEquals((event.speedAfterMps - event.speedBeforeMps) / event.durationSeconds,
                    event.averageAccelerationMps2, 1e-9)
            }
            result.events.zipWithNext().forEach { (left, right) ->
                assertTrue(left.endSampleIndex <= right.startSampleIndex)
            }
        }
    }

    private fun samples(vararg speeds: Double) = speeds.mapIndexed { index, speed ->
        PostTripGpsSample(index * 1000L, speed)
    }
}
