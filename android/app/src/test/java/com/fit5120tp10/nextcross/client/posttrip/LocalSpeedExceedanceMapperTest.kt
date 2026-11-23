package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.detection.DrivingEventType
import com.fit5120tp10.nextcross.client.recording.storage.DrivingEventEntity
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.posttripanalysis.SpeedLimitExceedanceDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeedExceedanceMapperTest {
    private fun rows() = listOf(10f, 20f, 18f, 12f).mapIndexed { index, speed ->
        GpsPointEntity(index + 1L, "trip", index * 1000L, -37.0, 145.0, speed, 5f)
    }

    private fun ready(points: List<GpsPointEntity>) = PostTripAnalysisUiState.Ready(
        tripId = "trip",
        analysisRevision = "rev",
        chronologicalEvents = emptyList(),
        rankedEvents = emptyList(),
        routePoints = points.mapIndexed { i, p ->
            ReviewRoutePoint(i, p.id, p.recordedAtMillis, p.latitude, p.longitude)
        },
        issues = emptyList()
    )

    private fun speedEntity(
        id: Long = 9,
        startId: Long = 2,
        endId: Long = 3,
        peakMps: Float = 20f,
        limit: Int = 40
    ) = DrivingEventEntity(
        id = id,
        tripId = "trip",
        type = DrivingEventType.SpeedLimitExceedance.name,
        recordedAtMillis = 1000L,
        accelerationMagnitude = (peakMps * 3.6f - limit),
        endedAtMillis = 2000L,
        startGpsPointId = startId,
        endGpsPointId = endId,
        limitKmh = limit,
        limitStatus = "static",
        peakSpeedMps = peakMps,
        averageExceedanceKmh = 12.0,
        severity = "Moderate"
    )

    @Test
    fun mapsLocalDrivingEventOntoSnapshotIndexes() {
        val points = rows()
        val mapped = LocalSpeedExceedanceMapper.toReviewEvents(
            listOf(speedEntity()),
            ready(points),
            points
        )
        assertEquals(1, mapped.size)
        val event = mapped.single()
        assertEquals("trip:SPEED_LIMIT_EXCEEDANCE:local-9", event.eventId)
        assertEquals(1, event.startSampleIndex)
        assertEquals(2, event.endSampleIndex)
        assertEquals(LocalSpeedExceedanceMapper.DETECTOR_VERSION, event.speedLimitDetails!!.detectorVersion)
    }

    @Test
    fun preferLocalDropsOverlappingBackendEvents() {
        val points = rows()
        val localReady = ready(points)
        val local = PostTripReviewEvent(
            eventId = "local",
            tripId = "trip",
            analysisRevision = "rev",
            type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE,
            startSampleIndex = 1,
            endSampleIndex = 2,
            startTimestampMillis = 1000L,
            endTimestampMillis = 2000L,
            durationSeconds = 1.0,
            speedBeforeMps = 20.0,
            speedAfterMps = 18.0,
            peakAccelerationMps2 = null,
            averageAccelerationMps2 = null,
            reviewPriorityScore = null,
            hasMissingAccuracy = false,
            routePoints = localReady.routePoints.subList(1, 3),
            speedLimitDetails = SpeedLimitExceedanceDetails(
                referenceLimitKmh = 40,
                peakSpeedKmh = 72.0,
                averageExceedanceKmh = 12.0,
                limitStatus = "static",
                detectorVersion = LocalSpeedExceedanceMapper.DETECTOR_VERSION
            )
        )
        val overlapping = local.copy(eventId = "backend-overlap")
        val separate = local.copy(
            eventId = "backend-separate",
            startSampleIndex = 3,
            endSampleIndex = 3,
            startTimestampMillis = 3000L,
            endTimestampMillis = 3000L,
            routePoints = localReady.routePoints.subList(3, 4),
            speedBeforeMps = 12.0,
            speedAfterMps = 12.0,
            speedLimitDetails = SpeedLimitExceedanceDetails(
                referenceLimitKmh = 40,
                peakSpeedKmh = 50.0,
                averageExceedanceKmh = 5.0,
                limitStatus = "static",
                detectorVersion = "speed-v1"
            )
        )
        val kept = LocalSpeedExceedanceMapper.preferLocalOverBackend(
            localSpeedEvents = listOf(local),
            backendSpeedEvents = listOf(overlapping, separate)
        )
        assertEquals(listOf("backend-separate"), kept.map { it.eventId })
    }

    @Test
    fun analyzeLocalIncludesPersistedSpeedDrivingEvents() {
        val points = rows().map { it.copy(speedMetersPerSecond = 5f) }
        val entity = speedEntity(
            id = 1,
            startId = 1,
            endId = 2,
            peakMps = 5f,
            limit = 10
        ).copy(
            recordedAtMillis = 0L,
            endedAtMillis = 1000L,
            averageExceedanceKmh = 8.0,
            accelerationMagnitude = 8f
        )
        val coordinator = PostTripAnalysisCoordinator(
            CompletedTripSnapshotSource {
                CompletedTripGpsSnapshot(
                    tripId = it,
                    samples = points,
                    localSpeedExceedances = listOf(entity)
                )
            }
        )
        val ready = runBlocking {
            coordinator.analyzeCompletedTrip("trip") as PostTripAnalysisUiState.Ready
        }
        assertTrue(
            ready.chronologicalEvents.any { it.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE }
        )
    }
}
