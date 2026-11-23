package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedAnalysisMapperTest {
    private fun rows() = listOf(0f, 10f, 20f, 15f).mapIndexed { index, speed ->
        GpsPointEntity(index + 1L, "trip", index * 1000L, -37.0, 145.0, speed, 5f)
    }

    @Test
    fun mapsNamespacedIdAndSampleIndexes() {
        val points = rows()
        val ready = PostTripAnalysisUiState.Ready(
            tripId = "trip",
            analysisRevision = "rev",
            chronologicalEvents = emptyList(),
            rankedEvents = emptyList(),
            routePoints = points.mapIndexed { i, p ->
                ReviewRoutePoint(i, p.id, p.recordedAtMillis, p.latitude, p.longitude)
            },
            issues = emptyList()
        )
        val backend = SpeedAnalysisResult(
            analysisStatus = "COMPLETED",
            events = listOf(
                SpeedAnalysisBackendEvent(
                    eventId = "E_SPEED_0001",
                    startSampleId = "1",
                    endSampleId = "2",
                    durationSeconds = 1.0,
                    referenceLimitKmh = 40,
                    peakSpeedKmh = 55.0,
                    averageExceedanceKmh = 8.0,
                    limitStatus = "static",
                    dataQuality = "GOOD",
                    detectorVersion = "speed-v1"
                )
            )
        )
        val mapped = SpeedAnalysisMapper.toReviewEvents(backend, ready, points)
        assertEquals(1, mapped.size)
        assertEquals("trip:SPEED_LIMIT_EXCEEDANCE:E_SPEED_0001", mapped.single().eventId)
        assertEquals(1, mapped.single().startSampleIndex)
        assertEquals(2, mapped.single().endSampleIndex)
        assertEquals(40, mapped.single().speedLimitDetails!!.referenceLimitKmh)
        assertEquals(1, mergeReviewEvents(ready, mapped).size)
    }

    @Test
    fun skipsInvalidEvents() {
        val ready = PostTripAnalysisUiState.Ready(
            tripId = "trip",
            analysisRevision = "rev",
            chronologicalEvents = emptyList(),
            rankedEvents = emptyList(),
            routePoints = rows().mapIndexed { i, p ->
                ReviewRoutePoint(i, p.id, p.recordedAtMillis, p.latitude, p.longitude)
            },
            issues = emptyList()
        )
        val backend = SpeedAnalysisResult(
            analysisStatus = "PARTIAL",
            events = listOf(
                SpeedAnalysisBackendEvent(
                    eventId = "bad",
                    startSampleId = "9",
                    endSampleId = "9",
                    durationSeconds = 1.0,
                    referenceLimitKmh = 40,
                    peakSpeedKmh = 55.0,
                    averageExceedanceKmh = 8.0,
                    limitStatus = "static",
                    dataQuality = "GOOD",
                    detectorVersion = "speed-v1"
                )
            )
        )
        assertTrue(SpeedAnalysisMapper.toReviewEvents(backend, ready, rows()).isEmpty())
    }
}
