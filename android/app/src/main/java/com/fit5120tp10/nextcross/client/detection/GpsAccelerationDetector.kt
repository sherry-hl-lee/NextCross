package com.fit5120tp10.nextcross.client.detection

import kotlin.math.abs

/**
 * Acceleration from GPS speed: a = speed change / time in seconds.
 * Ignore gaps shorter than 0.5 seconds.
 * a >= 3.0 is hard acceleration; a <= -3.0 is hard braking.
 * One streak over the limit becomes one event; a new event is allowed only after it drops below the limit.
 */
class GpsAccelerationDetector(
    private val thresholdMetersPerSecondSquared: Float = 3.0f,
    private val minIntervalSeconds: Double = 0.5
) {
    private var lastSample: GpsSpeedSample? = null
    private var openEventType: DrivingEventType? = null

    fun onSample(tripId: String, sample: GpsSpeedSample): DrivingEvent? {
        val previous = lastSample
        if (previous == null) {
            lastSample = sample
            return null
        }

        val dtSeconds = (sample.recordedAtMillis - previous.recordedAtMillis) / 1000.0
        if (dtSeconds <= 0) {
            return null
        }
        if (dtSeconds < minIntervalSeconds) {
            return null
        }
        lastSample = sample

        val acceleration = ((sample.speedMetersPerSecond - previous.speedMetersPerSecond) / dtSeconds).toFloat()
        val detectedType = when {
            acceleration >= thresholdMetersPerSecondSquared -> DrivingEventType.HardAcceleration
            acceleration <= -thresholdMetersPerSecondSquared -> DrivingEventType.HardBraking
            else -> null
        }

        if (detectedType == null) {
            openEventType = null
            return null
        }
        if (openEventType == detectedType) {
            return null
        }
        openEventType = detectedType
        return DrivingEvent(
            tripId = tripId,
            type = detectedType,
            recordedAtMillis = sample.recordedAtMillis,
            accelerationMagnitude = abs(acceleration)
        )
    }

    fun reset() {
        lastSample = null
        openEventType = null
    }
}
