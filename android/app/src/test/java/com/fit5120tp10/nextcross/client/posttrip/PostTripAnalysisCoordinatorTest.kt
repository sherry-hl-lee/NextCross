package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.posttripanalysis.PostTripAnalyzer
import com.fit5120tp10.nextcross.posttripanalysis.PostTripGpsSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PostTripAnalysisCoordinatorTest {
    private fun rows(trip: String = "trip", speeds: List<Float?> = listOf(0f, 5f, 10f)) =
        speeds.mapIndexed { index, speed -> GpsPointEntity(index + 1L, trip, index * 1000L,
            -37.0 + index * 0.001, 145.0, speed, 5f) }
    private fun coordinator(points: List<GpsPointEntity>) = PostTripAnalysisCoordinator(
        CompletedTripSnapshotSource { CompletedTripGpsSnapshot(it, points) })

    @Test fun completedTripEmitsLoadingThenReadyWithExactMapRange() = runBlocking {
        val points = rows()
        val states = coordinator(points).observeCompletedTrip("trip").toList()
        assertEquals(PostTripAnalysisUiState.Loading("trip"), states.first())
        val ready = states.last() as PostTripAnalysisUiState.Ready
        val event = ready.chronologicalEvents.single()
        assertEquals(listOf(1L, 2L, 3L), event.routePoints.map { it.storedId })
        assertEquals(points.map { it.latitude }, event.routePoints.map { it.latitude })
        assertEquals(ready.analysisRevision, event.analysisRevision)
    }

    @Test fun incompleteTripDoesNotInvokeAnalyzer() = runBlocking {
        var invoked = false
        val coordinator = PostTripAnalysisCoordinator(CompletedTripSnapshotSource {
            error("Trip recording is not fully complete.")
        }, { _, _ -> invoked = true; error("must not run") })
        assertTrue(coordinator.analyzeCompletedTrip("trip") is PostTripAnalysisUiState.Failed)
        assertFalse(invoked)
    }

    @Test fun emptyMissingAndDisconnectedDataAreInsufficient() = runBlocking {
        for (points in listOf(emptyList(), rows(speeds = listOf(null, null)),
            rows().mapIndexed { i, row -> row.copy(recordedAtMillis = i * 10000L) })) {
            assertTrue(coordinator(points).analyzeCompletedTrip("trip") is PostTripAnalysisUiState.InsufficientData)
        }
    }

    @Test fun validSteadyDrivingIsReadyWithoutEvents() = runBlocking {
        val result = coordinator(rows(speeds = listOf(5f, 5f, 5f))).analyzeCompletedTrip("trip")
        assertTrue((result as PostTripAnalysisUiState.Ready).chronologicalEvents.isEmpty())
    }

    @Test fun invalidIndexFailsInsteadOfShowingWrongSegment() = runBlocking {
        val analyzed = PostTripAnalyzer().analyze("trip", listOf(0.0, 5.0, 10.0).mapIndexed {
            i, speed -> PostTripGpsSample(i * 1000L, speed)
        })
        val coordinator = PostTripAnalysisCoordinator(CompletedTripSnapshotSource {
            CompletedTripGpsSnapshot(it, rows().take(2))
        }, { _, _ -> analyzed })
        assertTrue(coordinator.analyzeCompletedTrip("trip") is PostTripAnalysisUiState.Failed)
    }

    @Test fun repeatedAnalysisIsDeterministicAndTripsStayIsolated() = runBlocking {
        val coordinator = PostTripAnalysisCoordinator(CompletedTripSnapshotSource {
            CompletedTripGpsSnapshot(it, rows(it))
        })
        val one = coordinator.analyzeCompletedTrip("one") as PostTripAnalysisUiState.Ready
        assertEquals(one, coordinator.analyzeCompletedTrip("one"))
        val two = coordinator.analyzeCompletedTrip("two") as PostTripAnalysisUiState.Ready
        assertTrue(two.chronologicalEvents.all { it.tripId == "two" })
        assertNotEquals(one.analysisRevision, two.analysisRevision)
    }

    @Test fun unorderedAndMixedTripSnapshotsFail() = runBlocking {
        assertTrue(coordinator(rows().reversed()).analyzeCompletedTrip("trip") is PostTripAnalysisUiState.Failed)
        assertTrue(coordinator(rows("other")).analyzeCompletedTrip("trip") is PostTripAnalysisUiState.Failed)
    }

    @Test fun missingAccuracyIsPreserved() = runBlocking {
        val result = coordinator(rows().map { it.copy(accuracyMeters = null) })
            .analyzeCompletedTrip("trip") as PostTripAnalysisUiState.Ready
        assertTrue(result.chronologicalEvents.single().hasMissingAccuracy)
    }

    @Test fun cancellationPropagates() = runBlocking {
        try {
            PostTripAnalysisCoordinator(CompletedTripSnapshotSource { throw CancellationException() })
                .analyzeCompletedTrip("trip")
            fail("Cancellation should propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun backendMergeChecksSnapshotAndRetainsLocalEvents() = runBlocking {
        val ready = coordinator(rows()).analyzeCompletedTrip("trip") as PostTripAnalysisUiState.Ready
        val backend = ready.chronologicalEvents.single().copy(eventId = "backend-1",
            type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE, reviewPriorityScore = null)
        assertEquals(2, mergeReviewEvents(ready, listOf(backend)).size)
        try {
            mergeReviewEvents(ready, listOf(backend.copy(analysisRevision = "wrong")))
            fail("Must reject another snapshot")
        } catch (_: IllegalArgumentException) { }
    }
}
