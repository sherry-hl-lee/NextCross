package com.fit5120tp10.nextcross.client.speedlimit

import com.fit5120tp10.nextcross.client.detection.DrivingEventType
import com.fit5120tp10.nextcross.client.recording.storage.DrivingEventEntity
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.client.recording.storage.RecordingDao

fun interface SpeedExceedanceEventSink {
    fun insert(event: DrivingEventEntity): Long
}

/**
 * Persists Active Trip overspeed segments into [driving_events] as
 * [DrivingEventType.SpeedLimitExceedance] for Trip Summary.
 */
class SpeedExceedanceRecorder(
    private val sink: SpeedExceedanceEventSink
) {
    constructor(recordingDao: RecordingDao) : this(
        SpeedExceedanceEventSink(recordingDao::insertDrivingEvent)
    )

    private val lock = Any()
    private var open: OpenSegment? = null

    fun onAssessment(
        tripId: String,
        point: GpsPointEntity,
        context: SpeedLimitContext?,
        assessment: SpeedLimitWarningAssessment
    ) {
        synchronized(lock) {
            val active = assessment.isActive &&
                context != null &&
                context.isUsableForWarning &&
                assessment.severity != null
            val current = open
            when {
                active && current == null -> {
                    val speed = point.speedMetersPerSecond ?: return
                    open = OpenSegment(
                        tripId = tripId,
                        startedAtMillis = point.recordedAtMillis,
                        startGpsPointId = point.id,
                        limitKmh = context!!.limitKmh!!,
                        limitStatus = context.status.wireValue,
                        peakSpeedMps = speed,
                        exceedanceSumKmh = assessment.excessKmh ?: 0.0,
                        sampleCount = 1,
                        severity = assessment.severity!!,
                        lastPoint = point
                    )
                }
                active && current != null && current.tripId == tripId -> {
                    val speed = point.speedMetersPerSecond
                    open = current.copy(
                        peakSpeedMps = maxOf(current.peakSpeedMps, speed ?: current.peakSpeedMps),
                        exceedanceSumKmh = current.exceedanceSumKmh + (assessment.excessKmh ?: 0.0),
                        sampleCount = current.sampleCount + 1,
                        severity = maxSeverity(current.severity, assessment.severity!!),
                        lastPoint = point
                    )
                }
                !active && current != null && current.tripId == tripId -> {
                    persist(current, point)
                    open = null
                }
                active && current != null && current.tripId != tripId -> {
                    persist(current, current.lastPoint)
                    val speed = point.speedMetersPerSecond ?: return
                    open = OpenSegment(
                        tripId = tripId,
                        startedAtMillis = point.recordedAtMillis,
                        startGpsPointId = point.id,
                        limitKmh = context!!.limitKmh!!,
                        limitStatus = context.status.wireValue,
                        peakSpeedMps = speed,
                        exceedanceSumKmh = assessment.excessKmh ?: 0.0,
                        sampleCount = 1,
                        severity = assessment.severity!!,
                        lastPoint = point
                    )
                }
            }
        }
    }

    /** Call on End Trip so an in-progress banner segment is not lost. */
    fun closeOpenSegment(tripId: String, endPoint: GpsPointEntity? = null) {
        synchronized(lock) {
            val current = open ?: return
            if (current.tripId != tripId) return
            val point = endPoint ?: current.lastPoint ?: return
            persist(current, point)
            open = null
        }
    }

    /** Drop an in-progress segment without writing it (e.g. cancel recording). */
    fun discardOpenSegment(tripId: String? = null) {
        synchronized(lock) {
            if (tripId == null || open?.tripId == tripId) {
                open = null
            }
        }
    }

    private fun persist(segment: OpenSegment, endPoint: GpsPointEntity?) {
        if (endPoint == null) return
        if (endPoint.recordedAtMillis < segment.startedAtMillis) return
        val peakMps = segment.peakSpeedMps
        val peakKmh = peakMps * 3.6
        if (peakKmh <= segment.limitKmh) return
        val peakExceedanceKmh = peakKmh - segment.limitKmh
        val average = (segment.exceedanceSumKmh / segment.sampleCount.coerceAtLeast(1))
            .coerceIn(0.0, peakExceedanceKmh)
        sink.insert(
            DrivingEventEntity(
                tripId = segment.tripId,
                type = DrivingEventType.SpeedLimitExceedance.name,
                recordedAtMillis = segment.startedAtMillis,
                accelerationMagnitude = peakExceedanceKmh.toFloat(),
                endedAtMillis = endPoint.recordedAtMillis,
                startGpsPointId = segment.startGpsPointId,
                endGpsPointId = endPoint.id,
                limitKmh = segment.limitKmh,
                limitStatus = segment.limitStatus,
                peakSpeedMps = peakMps,
                averageExceedanceKmh = average,
                severity = segment.severity.name
            )
        )
    }

    private fun maxSeverity(
        a: SpeedLimitExceedanceSeverity,
        b: SpeedLimitExceedanceSeverity
    ): SpeedLimitExceedanceSeverity = if (a.ordinal >= b.ordinal) a else b

    private data class OpenSegment(
        val tripId: String,
        val startedAtMillis: Long,
        val startGpsPointId: Long,
        val limitKmh: Int,
        val limitStatus: String,
        val peakSpeedMps: Float,
        val exceedanceSumKmh: Double,
        val sampleCount: Int,
        val severity: SpeedLimitExceedanceSeverity,
        val lastPoint: GpsPointEntity? = null
    )
}
