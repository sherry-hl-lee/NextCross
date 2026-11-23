package com.fit5120tp10.nextcross.client.detection

/**
 * Live / local driving events written during recording.
 * Accel/brake come from the GPS detector; [SpeedLimitExceedance] comes from the Active Trip banner.
 */
enum class DrivingEventType {
    HardAcceleration,
    HardBraking,
    /** Completed overspeed segment (Inactive→Active…→Inactive or End Trip). */
    SpeedLimitExceedance
}

/**
 * One driving event.
 * For accel/brake, [accelerationMagnitude] is |a| in m/s².
 * For [DrivingEventType.SpeedLimitExceedance], [accelerationMagnitude] is peak exceedance in km/h.
 */
data class DrivingEvent(
    val tripId: String,
    val type: DrivingEventType,
    val recordedAtMillis: Long,
    val accelerationMagnitude: Float
)

/** One GPS speed sample, compared with the next sample to get acceleration. */
data class GpsSpeedSample(
    val recordedAtMillis: Long,
    val speedMetersPerSecond: Float
)
