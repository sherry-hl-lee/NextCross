package com.fit5120tp10.nextcross.posttripanalysis

import org.junit.Assert.*
import org.junit.Test

class PostTripBoundaryRegressionTest {
    @Test fun tenHundredMillisecondIntervalsReachExactlyOneSecond() {
        val input = List(11) { PostTripGpsSample(it * 100L, 10.0 + it * 0.4) }
        val result = PostTripEventDetector(EventDetectionConfig(minSampleIntervalSeconds = 0.1)).detect("trip", input)
        assertEquals(1.0, result.events.single().durationSeconds, 0.0)
        assertEquals(10, result.events.single().endSampleIndex)
    }

    @Test fun threeHundredMillisecondBridgeMergesAtInclusiveBoundary() {
        val input = listOf(10.0, 10.4, 10.4, 10.4, 10.4, 10.8).mapIndexed { index, speed ->
            PostTripGpsSample(index * 100L, speed)
        }
        val result = PostTripEventDetector(EventDetectionConfig(
            minSampleIntervalSeconds = 0.1, minEventDurationSeconds = 0.1, mergeGapSeconds = 0.3
        )).detect("trip", input)
        assertEquals(1, result.events.size)
        assertEquals(0.5, result.events.single().durationSeconds, 0.0)
        assertEquals(0.3, result.events.single().mergedGapSeconds, 0.0)
    }

    @Test fun largeBridgeCannotReverseOrCancelTheNetDirectionOfAMergedEvent() {
        for (lastSpeed in listOf(19.8, 20.0)) {
            val speeds = listOf(20.0, 24.0) + List(8) { 24.0 - (it + 1) * 0.9 } + lastSpeed
            val input = speeds.mapIndexed { index, speed -> PostTripGpsSample(index * 1000L, speed) }
            val result = PostTripEventDetector(EventDetectionConfig(mergeGapSeconds = 10.0)).detect("trip", input)
            assertEquals(2, result.events.size)
            assertEquals(listOf(0, 9), result.events.map { it.startSampleIndex })
            assertTrue(result.events.all { it.type == DrivingEventType.RAPID_ACCELERATION && it.averageAccelerationMps2 > 0 })
            assertTrue(result.events.all { it.mergedGapSeconds == 0.0 })
        }
    }

    @Test fun pendingBridgeCannotCrossInvalidDataTimeOrAGap() {
        val base = listOf(
            PostTripGpsSample(0, 10.0), PostTripGpsSample(1000, 14.0), PostTripGpsSample(2000, 14.0)
        )
        val tails = listOf(
            listOf(PostTripGpsSample(3000, null), PostTripGpsSample(4000, 18.0), PostTripGpsSample(5000, 22.0)),
            listOf(PostTripGpsSample(1000, 18.0), PostTripGpsSample(4000, 18.0), PostTripGpsSample(5000, 22.0)),
            listOf(PostTripGpsSample(5000, 18.0), PostTripGpsSample(6000, 22.0))
        )
        tails.forEach { tail ->
            val result = PostTripEventDetector(EventDetectionConfig(mergeGapSeconds = 10.0)).detect("trip", base + tail)
            assertEquals(2, result.events.size)
            assertEquals(1, result.events.first().endSampleIndex)
            assertEquals(1.0, result.events.first().durationSeconds, 0.0)
            assertTrue(result.events.all { it.mergedGapSeconds == 0.0 })
            assertTrue(result.issues.isNotEmpty())
        }
    }
}
