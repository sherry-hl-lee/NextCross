package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedAnalysisFlowTest {
    private fun rows() = listOf(5f, 5f, 5f).mapIndexed { index, speed ->
        GpsPointEntity(index + 1L, "trip", index * 1000L, -37.0, 145.0, speed, 5f)
    }

    @Test
    fun emitsLocalReadyBeforeSpeedMerge() = runBlocking {
        val client = SpeedAnalysisClient { _, _ ->
            SpeedAnalysisClientResult.Success(
                SpeedAnalysisResult(analysisStatus = "COMPLETED", events = emptyList())
            )
        }
        val states = PostTripAnalysisCoordinator(
            source = CompletedTripSnapshotSource { CompletedTripGpsSnapshot(it, rows()) },
            speedClient = client,
            stillCheckingAfterMillis = 60_000L
        ).observeCompletedTrip("trip").toList()

        assertEquals(PostTripAnalysisUiState.Loading("trip"), states.first())
        val checking = states[1] as PostTripAnalysisUiState.Ready
        assertEquals(SpeedCheckState.Checking, checking.speedCheck)
        val done = states.last() as PostTripAnalysisUiState.Ready
        assertTrue(done.speedCheck is SpeedCheckState.Done)
        assertEquals("COMPLETED", (done.speedCheck as SpeedCheckState.Done).status)
    }

    @Test
    fun failedSpeedKeepsLocalEvents() = runBlocking {
        val client = SpeedAnalysisClient { _, _ -> SpeedAnalysisClientResult.Failed("boom") }
        val states = PostTripAnalysisCoordinator(
            source = CompletedTripSnapshotSource { CompletedTripGpsSnapshot(it, rows()) },
            speedClient = client
        ).observeCompletedTrip("trip").toList()
        val last = states.last() as PostTripAnalysisUiState.Ready
        assertEquals(SpeedCheckState.Failed("boom"), last.speedCheck)
        assertTrue(last.chronologicalEvents.isEmpty())
    }

    @Test
    fun cachesMergedResult() = runBlocking {
        var calls = 0
        val client = SpeedAnalysisClient { _, _ ->
            calls += 1
            SpeedAnalysisClientResult.Success(SpeedAnalysisResult("COMPLETED", emptyList()))
        }
        val coordinator = PostTripAnalysisCoordinator(
            source = CompletedTripSnapshotSource { CompletedTripGpsSnapshot(it, rows()) },
            speedClient = client
        )
        coordinator.observeCompletedTrip("trip").toList()
        coordinator.observeCompletedTrip("trip").toList()
        assertEquals(1, calls)
    }

    @Test
    fun retryingFailedSpeedCheckReusesLocalAnalysis() = runBlocking {
        var snapshotLoads = 0
        var speedCalls = 0
        val client = SpeedAnalysisClient { _, _ ->
            speedCalls += 1
            if (speedCalls == 1) SpeedAnalysisClientResult.Failed("temporary failure")
            else SpeedAnalysisClientResult.Success(SpeedAnalysisResult("COMPLETED", emptyList()))
        }
        val coordinator = PostTripAnalysisCoordinator(
            source = CompletedTripSnapshotSource {
                snapshotLoads += 1
                CompletedTripGpsSnapshot(it, rows())
            },
            speedClient = client
        )

        val failed = coordinator.observeCompletedTrip("trip").toList().last()
            as PostTripAnalysisUiState.Ready
        val retried = coordinator.retrySpeedCheck("trip").toList().last()
            as PostTripAnalysisUiState.Ready

        assertEquals(SpeedCheckState.Failed("temporary failure"), failed.speedCheck)
        assertTrue(retried.speedCheck is SpeedCheckState.Done)
        assertEquals(1, snapshotLoads)
        assertEquals(2, speedCalls)
    }

    @Test
    fun failedSpeedCheckIsCachedAndRetryFailureIsRemembered() = runBlocking {
        var speedCalls = 0
        val coordinator = PostTripAnalysisCoordinator(
            source = CompletedTripSnapshotSource { CompletedTripGpsSnapshot(it, rows()) },
            speedClient = SpeedAnalysisClient { _, _ ->
                speedCalls += 1
                SpeedAnalysisClientResult.Failed("unavailable")
            }
        )

        coordinator.observeCompletedTrip("trip").toList()
        assertEquals(1, speedCalls)
        assertTrue(!coordinator.hasRetriedSpeedCheck("trip"))

        val cached = coordinator.observeCompletedTrip("trip").toList().last()
            as PostTripAnalysisUiState.Ready
        assertEquals(SpeedCheckState.Failed("unavailable"), cached.speedCheck)
        assertEquals(1, speedCalls)

        coordinator.retrySpeedCheck("trip").toList()
        assertEquals(2, speedCalls)
        assertTrue(coordinator.hasRetriedSpeedCheck("trip"))
        coordinator.observeCompletedTrip("trip").toList()
        assertEquals(2, speedCalls)
    }
}
