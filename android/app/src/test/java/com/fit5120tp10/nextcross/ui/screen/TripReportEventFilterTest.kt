package com.fit5120tp10.nextcross.ui.screen

import com.fit5120tp10.nextcross.client.posttrip.PostTripAnalysisUiState
import com.fit5120tp10.nextcross.client.posttrip.PostTripReviewEvent
import com.fit5120tp10.nextcross.client.posttrip.ReviewEventType
import com.fit5120tp10.nextcross.client.posttrip.SpeedCheckState
import com.fit5120tp10.nextcross.client.tripreport.ShareEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TripReportEventFilterTest {
    @Test
    fun reportAndSharingKeepOnlyAccelerationAndBrakingWhileSpeedCheckRuns() {
        val events = listOf(
            event("brake", ReviewEventType.RAPID_DECELERATION),
            event("speed", ReviewEventType.SPEED_LIMIT_EXCEEDANCE),
            event("accelerate", ReviewEventType.RAPID_ACCELERATION)
        )
        val ready = PostTripAnalysisUiState.Ready(
            tripId = "trip-1",
            analysisRevision = "revision",
            chronologicalEvents = events,
            rankedEvents = events,
            routePoints = emptyList(),
            issues = emptyList(),
            speedCheck = SpeedCheckState.Checking
        )

        assertEquals(listOf("brake", "accelerate"), reportEventsFor(events).map { it.eventId })
        val shareEvents = shareEventsFor(ready)
        assertNotNull(shareEvents)
        assertEquals(
            listOf(ShareEventKind.HARD_BRAKING, ShareEventKind.HARD_ACCELERATION),
            shareEvents?.map { it.kind }
        )
        assertEquals(
            listOf(ShareEventKind.HARD_BRAKING, ShareEventKind.HARD_ACCELERATION),
            shareEventsFor(ready.copy(speedCheck = SpeedCheckState.Failed("offline")))?.map { it.kind }
        )
        assertEquals(emptyList<ShareEventKind>(),
            shareEventsFor(ready.copy(chronologicalEvents = listOf(events[1])))?.map { it.kind })
        assertEquals(3, ready.chronologicalEvents.size)
    }

    private fun event(id: String, type: ReviewEventType) = PostTripReviewEvent(
        eventId = id,
        tripId = "trip-1",
        analysisRevision = "revision",
        type = type,
        startSampleIndex = 0,
        endSampleIndex = 0,
        startTimestampMillis = 1_000L,
        endTimestampMillis = 2_000L,
        durationSeconds = 1.0,
        speedBeforeMps = 0.0,
        speedAfterMps = 0.0,
        peakAccelerationMps2 = null,
        averageAccelerationMps2 = null,
        reviewPriorityScore = null,
        hasMissingAccuracy = false,
        routePoints = emptyList()
    )
}
