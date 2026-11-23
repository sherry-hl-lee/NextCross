package com.fit5120tp10.nextcross.posttripanalysis

import com.fit5120tp10.nextcross.client.detection.GpsSpeedSample

/**
 * Optional, read-only adapter. The existing model has no accuracy or monotonic-time fields.
 * Missing evidence stays null; this does not install or replace any live detector.
 */
fun GpsSpeedSample.toPostTripGpsSample(): PostTripGpsSample = PostTripGpsSample(
    timestampMillis = recordedAtMillis,
    speedMps = speedMetersPerSecond.toDouble()
)

/** No sorting or filtering: indices still identify the caller's original samples. */
fun adaptLegacyGpsSamples(samples: List<GpsSpeedSample>): List<PostTripGpsSample> =
    samples.map { it.toPostTripGpsSample() }
