package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.detection.DrivingEventType
import com.fit5120tp10.nextcross.client.recording.storage.DrivingEventEntity
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.posttripanalysis.SpeedLimitExceedanceDetails

/** Maps local [DrivingEventType.SpeedLimitExceedance] rows onto the completed GPS snapshot. */
object LocalSpeedExceedanceMapper {
    const val DETECTOR_VERSION = "local-active-trip-v1"

    fun toReviewEvents(
        entities: List<DrivingEventEntity>,
        local: PostTripAnalysisUiState.Ready,
        samples: List<GpsPointEntity>
    ): List<PostTripReviewEvent> {
        if (samples.isEmpty() || entities.isEmpty()) return emptyList()
        val byId = samples.mapIndexed { index, point -> point.id to index }.toMap()
        return entities
            .filter { it.type == DrivingEventType.SpeedLimitExceedance.name }
            .mapNotNull { entity ->
                runCatching { mapOne(entity, local, samples, byId) }.getOrNull()
            }
    }

    private fun mapOne(
        entity: DrivingEventEntity,
        local: PostTripAnalysisUiState.Ready,
        samples: List<GpsPointEntity>,
        byId: Map<Long, Int>
    ): PostTripReviewEvent {
        val limitKmh = requireNotNull(entity.limitKmh)
        val limitStatus = requireNotNull(entity.limitStatus)
        val peakSpeedMps = requireNotNull(entity.peakSpeedMps)
        val endedAt = requireNotNull(entity.endedAtMillis)
        val start = entity.startGpsPointId?.let { byId[it] }
            ?: nearestIndex(samples, entity.recordedAtMillis)
        val end = entity.endGpsPointId?.let { byId[it] }
            ?: nearestIndex(samples, endedAt)
        require(start in samples.indices && end in samples.indices && end >= start)
        val speedBefore = samples[start].speedMetersPerSecond?.toDouble()
        val speedAfter = samples[end].speedMetersPerSecond?.toDouble()
        require(speedBefore != null && speedAfter != null)
        val peakKmh = peakSpeedMps * 3.6
        val average = (entity.averageExceedanceKmh ?: entity.accelerationMagnitude.toDouble())
            .coerceIn(0.0, (peakKmh - limitKmh).coerceAtLeast(0.0))
        val details = SpeedLimitExceedanceDetails(
            referenceLimitKmh = limitKmh,
            peakSpeedKmh = peakKmh,
            averageExceedanceKmh = average,
            limitStatus = limitStatus,
            detectorVersion = DETECTOR_VERSION
        )
        val route = local.routePoints.subList(start, end + 1).toList()
        return PostTripReviewEvent(
            eventId = "${local.tripId}:SPEED_LIMIT_EXCEEDANCE:local-${entity.id}",
            tripId = local.tripId,
            analysisRevision = local.analysisRevision,
            type = ReviewEventType.SPEED_LIMIT_EXCEEDANCE,
            startSampleIndex = start,
            endSampleIndex = end,
            startTimestampMillis = local.routePoints[start].timestampMillis,
            endTimestampMillis = local.routePoints[end].timestampMillis,
            durationSeconds = ((endedAt - entity.recordedAtMillis).coerceAtLeast(0L) / 1000.0),
            speedBeforeMps = speedBefore,
            speedAfterMps = speedAfter,
            peakAccelerationMps2 = null,
            averageAccelerationMps2 = null,
            reviewPriorityScore = null,
            hasMissingAccuracy = false,
            routePoints = route,
            speedLimitDetails = details
        )
    }

    private fun nearestIndex(samples: List<GpsPointEntity>, epochMillis: Long): Int {
        var best = 0
        var bestDelta = Long.MAX_VALUE
        samples.forEachIndexed { index, point ->
            val delta = kotlin.math.abs(point.recordedAtMillis - epochMillis)
            if (delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return best
    }

    /**
     * Local exceedances win on overlap. Backend events that overlap a local segment are dropped;
     * non-overlapping backend events are kept.
     */
    fun preferLocalOverBackend(
        localSpeedEvents: List<PostTripReviewEvent>,
        backendSpeedEvents: List<PostTripReviewEvent>
    ): List<PostTripReviewEvent> {
        if (localSpeedEvents.isEmpty()) return backendSpeedEvents
        return backendSpeedEvents.filter { backend ->
            localSpeedEvents.none { local -> rangesOverlap(local, backend) }
        }
    }

    private fun rangesOverlap(a: PostTripReviewEvent, b: PostTripReviewEvent): Boolean {
        return a.startSampleIndex <= b.endSampleIndex && b.startSampleIndex <= a.endSampleIndex
    }
}
