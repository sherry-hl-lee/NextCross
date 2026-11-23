package com.fit5120tp10.nextcross.posttripanalysis

import com.fit5120tp10.nextcross.client.detection.GpsSpeedSample
import org.junit.Assert.*
import org.junit.Test

class LegacyGpsSampleAdapterTest {
    @Test fun adapterPreservesValuesAndDoesNotInventMissingEvidence() {
        val original = GpsSpeedSample(1234L, 12.5f)
        val adapted = original.toPostTripGpsSample()
        assertEquals(1234L, adapted.timestampMillis)
        assertEquals(12.5, adapted.speedMps!!, 0.0)
        assertNull(adapted.elapsedRealtimeMillis)
        assertNull(adapted.horizontalAccuracyMeters)
        assertNull(adapted.speedAccuracyMps)
        assertEquals(GpsSpeedSample(1234L, 12.5f), original)
    }

    @Test fun adapterDoesNotSortOrSilentlyRemoveInvalidSamples() {
        val source = listOf(GpsSpeedSample(3000, 5f), GpsSpeedSample(1000, Float.NaN), GpsSpeedSample(2000, -1f))
        val adapted = adaptLegacyGpsSamples(source)
        assertEquals(listOf(3000L, 1000L, 2000L), adapted.map { it.timestampMillis })
        assertTrue(adapted[1].speedMps!!.isNaN())
        assertEquals(-1.0, adapted[2].speedMps!!, 0.0)
    }

    @Test fun adaptedTripCanBeAnalyzedWithoutChangingTheLiveDetector() {
        val samples = adaptLegacyGpsSamples(listOf(GpsSpeedSample(0, 0f), GpsSpeedSample(1000, 4f)))
        val result = PostTripAnalyzer().analyze("trip", samples)
        assertEquals(DrivingEventType.RAPID_ACCELERATION, result.events.single().type)
        assertTrue(result.events.single().manoeuvre.hasMissingAccuracy)
        assertFalse(result.events.single().manoeuvre.usesElapsedRealtime)
    }

    @Test fun emptyListRemainsEmpty() {
        assertTrue(adaptLegacyGpsSamples(emptyList()).isEmpty())
    }
}
