package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedAnalysisRequestBuilderTest {
    @Test
    fun usesIndexSampleIdsAndNullBearing() {
        val samples = listOf(0f, 5f, null).mapIndexed { index, speed ->
            GpsPointEntity(index + 1L, "trip", 1_000L + index, -37.9, 145.1, speed, if (index == 2) null else 4f)
        }
        val json = JSONObject(
            SpeedAnalysisRequestBuilder.buildJson("trip", 900L, 2_000L, samples)
        )
        assertEquals("trip", json.getString("tripId"))
        assertEquals(900L, json.getLong("startedAtEpochMillis"))
        assertEquals(2_000L, json.getLong("endedAtEpochMillis"))
        val arr = json.getJSONArray("samples")
        assertEquals(3, arr.length())
        assertEquals("0", arr.getJSONObject(0).getString("sampleId"))
        assertEquals("2", arr.getJSONObject(2).getString("sampleId"))
        assertTrue(arr.getJSONObject(0).isNull("bearingDegrees"))
        assertTrue(arr.getJSONObject(2).isNull("speedMps"))
        assertTrue(arr.getJSONObject(2).isNull("accuracyMeters"))
    }

    @Test
    fun downsamplesButKeepsOriginalIndexes() {
        val indexes = SpeedAnalysisRequestBuilder.selectedIndexes(25_000)
        assertTrue(indexes.size <= SpeedAnalysisRequestBuilder.MAX_SAMPLES)
        assertEquals(0, indexes.first())
        assertEquals(24_999, indexes.last())
        assertTrue(indexes == indexes.distinct().sorted())
    }
}
