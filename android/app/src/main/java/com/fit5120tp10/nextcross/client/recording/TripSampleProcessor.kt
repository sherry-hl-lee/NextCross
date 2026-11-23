package com.fit5120tp10.nextcross.client.recording

import com.fit5120tp10.nextcross.client.detection.GpsAccelerationDetector
import com.fit5120tp10.nextcross.client.detection.GpsSpeedSample
import com.fit5120tp10.nextcross.client.recording.storage.DrivingEventEntity
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.client.recording.storage.RecordingDao

/**
 * Writes one already-observed trip location and applies the existing GPS-speed detector.
 * Callers choose how locations are obtained; this class does not obtain Android location itself.
 */
class TripSampleProcessor(
    private val recordingDao: RecordingDao,
    private val accelerationDetector: GpsAccelerationDetector = GpsAccelerationDetector()
) {
    fun record(sample: TripLocationSample): DrivingEventEntity? {
        recordingDao.insertGpsPoint(
            GpsPointEntity(
                tripId = sample.tripId,
                recordedAtMillis = sample.recordedAtMillis,
                latitude = sample.latitude,
                longitude = sample.longitude,
                speedMetersPerSecond = sample.speedMetersPerSecond,
                accuracyMeters = sample.accuracyMeters
            )
        )

        val speed = sample.speedMetersPerSecond ?: return null
        val event = accelerationDetector.onSample(
            sample.tripId,
            GpsSpeedSample(sample.recordedAtMillis, speed)
        ) ?: return null
        return DrivingEventEntity(
            tripId = event.tripId,
            type = event.type.name,
            recordedAtMillis = event.recordedAtMillis,
            accelerationMagnitude = event.accelerationMagnitude
        ).also(recordingDao::insertDrivingEvent)
    }

    fun reset() {
        accelerationDetector.reset()
    }
}

data class TripLocationSample(
    val tripId: String,
    val recordedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float?
)
