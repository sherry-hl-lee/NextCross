package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.posttripanalysis.SpeedLimitExceedanceDetails

object SpeedAnalysisMapper {
    fun toReviewEvents(
        result: SpeedAnalysisResult,
        local: PostTripAnalysisUiState.Ready,
        samples: List<GpsPointEntity>
    ): List<PostTripReviewEvent> {
        return result.events.mapNotNull { event ->
            runCatching { mapOne(event, local, samples) }.getOrNull()
        }
    }

    private fun mapOne(
        event: SpeedAnalysisBackendEvent,
        local: PostTripAnalysisUiState.Ready,
        samples: List<GpsPointEntity>
    ): PostTripReviewEvent {
        val start = event.startSampleId.toInt()
        val end = event.endSampleId.toInt()
        require(start in samples.indices && end in samples.indices && end >= start)
        val speedBefore = samples[start].speedMetersPerSecond?.toDouble()
        val speedAfter = samples[end].speedMetersPerSecond?.toDouble()
        require(speedBefore != null && speedAfter != null)
        val route = local.routePoints.subList(start, end + 1).toList()
        val details = SpeedLimitExceedanceDetails(
            referenceLimitKmh = event.referenceLimitKmh,
            peakSpeedKmh = event.peakSpeedKmh,
            averageExceedanceKmh = event.averageExceedanceKmh,
            limitStatus = event.limitStatus,
            detectorVersion = event.detectorVersion
        )
        return PostTripReviewEvent(
            eventId = "${local.tripId}:SPEED_LIMIT_EXCEEDANCE:${event.eventId}",
            tripId = local.tripId,
            analysisRevision = local.analysisRevision,
            type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE,
            startSampleIndex = start,
            endSampleIndex = end,
            startTimestampMillis = local.routePoints[start].timestampMillis,
            endTimestampMillis = local.routePoints[end].timestampMillis,
            durationSeconds = event.durationSeconds,
            speedBeforeMps = speedBefore,
            speedAfterMps = speedAfter,
            peakAccelerationMps2 = null,
            averageAccelerationMps2 = null,
            reviewPriorityScore = null,
            hasMissingAccuracy = event.dataQuality != "GOOD",
            routePoints = route,
            speedLimitDetails = details
        )
    }
}
