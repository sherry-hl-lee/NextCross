package com.fit5120tp10.nextcross.client.speedlimit

import com.fit5120tp10.nextcross.client.detection.DrivingEventType
import com.fit5120tp10.nextcross.client.recording.storage.DrivingEventEntity
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedExceedanceRecorderTest {
    private val written = mutableListOf<DrivingEventEntity>()
    private val recorder = SpeedExceedanceRecorder { event ->
        written += event.copy(id = written.size + 1L)
        written.size.toLong()
    }

    private fun point(
        id: Long,
        speedMps: Float,
        atMillis: Long = id * 1000L
    ) = GpsPointEntity(id, "trip", atMillis, -37.0, 145.0, speedMps, 5f)

    private fun usableContext(limitKmh: Int = 40) = SpeedLimitContext(
        limitKmh = limitKmh,
        status = SpeedLimitStatus.Static,
        sourceSpeedZoneId = "zone-1"
    )

    @Test
    fun persistsDrivingEventOnActiveToInactive() {
        val context = usableContext()
        recorder.onAssessment(
            "trip",
            point(1, 50f / 3.6f),
            context,
            SpeedLimitWarningEvaluator.assess(context, 50.0 / 3.6)
        )
        assertTrue(written.isEmpty())

        recorder.onAssessment(
            "trip",
            point(2, 55f / 3.6f),
            context,
            SpeedLimitWarningEvaluator.assess(context, 55.0 / 3.6)
        )
        recorder.onAssessment(
            "trip",
            point(3, 30f / 3.6f),
            context,
            SpeedLimitWarningEvaluator.assess(context, 30.0 / 3.6)
        )
        assertEquals(1, written.size)
        val event = written.single()
        assertEquals(DrivingEventType.SpeedLimitExceedance.name, event.type)
        assertEquals(1L, event.startGpsPointId)
        assertEquals(3L, event.endGpsPointId)
        assertEquals(40, event.limitKmh)
        assertTrue(event.peakSpeedMps!! * 3.6 >= 54.0)
        assertEquals(SpeedLimitExceedanceSeverity.Moderate.name, event.severity)
    }

    @Test
    fun closeOpenSegmentPersistsInProgressBanner() {
        val context = usableContext()
        recorder.onAssessment(
            "trip",
            point(1, 60f / 3.6f),
            context,
            SpeedLimitWarningEvaluator.assess(context, 60.0 / 3.6)
        )
        recorder.closeOpenSegment("trip", point(2, 58f / 3.6f))
        assertEquals(1, written.size)
        assertEquals(2L, written.single().endGpsPointId)
    }

    @Test
    fun discardOpenSegmentDoesNotPersist() {
        val context = usableContext()
        recorder.onAssessment(
            "trip",
            point(1, 60f / 3.6f),
            context,
            SpeedLimitWarningEvaluator.assess(context, 60.0 / 3.6)
        )
        recorder.discardOpenSegment("trip")
        recorder.closeOpenSegment("trip", point(2, 58f / 3.6f))
        assertTrue(written.isEmpty())
    }
}
