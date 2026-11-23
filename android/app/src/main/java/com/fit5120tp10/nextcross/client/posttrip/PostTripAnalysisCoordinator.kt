package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.DrivingEventEntity
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.posttripanalysis.PostTripAnalyzer
import com.fit5120tp10.nextcross.posttripanalysis.PostTripGpsSample
import com.fit5120tp10.nextcross.posttripanalysis.PostTripAnalysisResult
import com.fit5120tp10.nextcross.posttripanalysis.SpeedLimitExceedanceDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class ReviewEventType { RAPID_ACCELERATION, RAPID_DECELERATION, SPEED_LIMIT_EXCEEDANCE }

data class ReviewRoutePoint(val sampleIndex: Int, val storedId: Long, val timestampMillis: Long,
    val latitude: Double, val longitude: Double)
data class PostTripAnalysisIssue(val sampleIndex: Int, val reason: String)
data class PostTripReviewEvent(
    val eventId: String, val tripId: String, val analysisRevision: String,
    val type: ReviewEventType, val startSampleIndex: Int, val endSampleIndex: Int,
    val startTimestampMillis: Long, val endTimestampMillis: Long,
    val durationSeconds: Double, val speedBeforeMps: Double, val speedAfterMps: Double,
    val peakAccelerationMps2: Double?, val averageAccelerationMps2: Double?,
    val reviewPriorityScore: Double?, val hasMissingAccuracy: Boolean,
    val routePoints: List<ReviewRoutePoint>,
    val speedLimitDetails: SpeedLimitExceedanceDetails? = null
)

sealed interface PostTripAnalysisUiState {
    data object Idle : PostTripAnalysisUiState
    data class Loading(val tripId: String) : PostTripAnalysisUiState
    data class Ready(
        val tripId: String,
        val analysisRevision: String,
        val chronologicalEvents: List<PostTripReviewEvent>,
        val rankedEvents: List<PostTripReviewEvent>,
        val routePoints: List<ReviewRoutePoint>,
        val issues: List<PostTripAnalysisIssue>,
        val speedCheck: SpeedCheckState = SpeedCheckState.Done("LOCAL_ONLY", null)
    ) : PostTripAnalysisUiState
    data class InsufficientData(val tripId: String, val reason: String,
        val issues: List<PostTripAnalysisIssue>) : PostTripAnalysisUiState
    data class Failed(val tripId: String, val message: String) : PostTripAnalysisUiState
}

data class CompletedTripGpsSnapshot(
    val tripId: String,
    val samples: List<GpsPointEntity>,
    val startedAtMillis: Long = samples.firstOrNull()?.recordedAtMillis ?: 0L,
    val endedAtMillis: Long = samples.lastOrNull()?.recordedAtMillis ?: 0L,
    val localSpeedExceedances: List<DrivingEventEntity> = emptyList()
)

fun interface CompletedTripSnapshotSource {
    suspend fun load(tripId: String): CompletedTripGpsSnapshot
}

class PostTripAnalysisCoordinator(
    private val source: CompletedTripSnapshotSource,
    private val analyze: (String, List<PostTripGpsSample>) -> PostTripAnalysisResult =
        PostTripAnalyzer()::analyze,
    private val speedClient: SpeedAnalysisClient? = null,
    private val stillCheckingAfterMillis: Long = 8_000L
) {
    private val mergedCache = ConcurrentHashMap<String, PostTripAnalysisUiState.Ready>()
    private val retryableSpeedChecks = ConcurrentHashMap<String, SpeedCheckInput>()
    private val retriedSpeedChecks = ConcurrentHashMap.newKeySet<String>()

    fun hasRetriedSpeedCheck(tripId: String): Boolean = tripId in retriedSpeedChecks

    fun observeCompletedTrip(tripId: String): Flow<PostTripAnalysisUiState> = flow {
        emit(PostTripAnalysisUiState.Loading(tripId))
        val cached = mergedCache[tripId]
        if (cached != null) {
            emit(cached)
            return@flow
        }
        val analyzed = analyzeLocal(tripId)
        val local = analyzed.state
        if (local !is PostTripAnalysisUiState.Ready || speedClient == null || analyzed.snapshot == null) {
            emit(local)
            return@flow
        }
        val input = SpeedCheckInput(local, analyzed.snapshot)
        speedCheck(input).collect { merged ->
            cacheSpeedOutcome(tripId, input, merged)
            emit(merged)
        }
    }

    /**
     * Repeats only the optional road-speed request after it has failed. The local event analysis
     * and the completed GPS snapshot are reused from the failed attempt.
     */
    fun retrySpeedCheck(tripId: String): Flow<PostTripAnalysisUiState> = flow {
        val input = retryableSpeedChecks[tripId] ?: return@flow
        speedCheck(input).collect { merged ->
            cacheSpeedOutcome(tripId, input, merged)
            if (merged.speedCheck is SpeedCheckState.Failed) retriedSpeedChecks.add(tripId)
            emit(merged)
        }
    }

    private fun cacheSpeedOutcome(
        tripId: String,
        input: SpeedCheckInput,
        merged: PostTripAnalysisUiState.Ready
    ) {
        when (merged.speedCheck) {
            is SpeedCheckState.Done -> {
                mergedCache[tripId] = merged
                retryableSpeedChecks.remove(tripId)
            }
            is SpeedCheckState.Failed -> {
                mergedCache[tripId] = merged
                retryableSpeedChecks[tripId] = input
            }
            SpeedCheckState.Checking, SpeedCheckState.StillChecking -> Unit
        }
    }

    private fun speedCheck(input: SpeedCheckInput): Flow<PostTripAnalysisUiState.Ready> = flow {
        val local = input.local
        val snapshot = input.snapshot
        val speedClient = requireNotNull(speedClient)
        emit(local.copy(speedCheck = SpeedCheckState.Checking))
        coroutineScope {
            val requestJson = SpeedAnalysisRequestBuilder.buildJson(
                tripId = local.tripId,
                startedAtMillis = snapshot.startedAtMillis,
                endedAtMillis = snapshot.endedAtMillis,
                samples = snapshot.samples
            )
            val deferred = async(Dispatchers.IO) { speedClient.analyze(local.tripId, requestJson) }
            val early = withTimeoutOrNull(stillCheckingAfterMillis) { deferred.await() }
            if (early == null) {
                emit(local.copy(speedCheck = SpeedCheckState.StillChecking))
            }
            val outcome = runCatching { early ?: deferred.await() }
            val merged = outcome.fold(
                onSuccess = { clientResult -> applySpeedResult(local, clientResult) },
                onFailure = { error ->
                    local.copy(speedCheck = SpeedCheckState.Failed(error.message ?: "unavailable"))
                }
            )
            emit(merged)
        }
    }

    fun clearSpeedCache(tripId: String) {
        mergedCache.remove(tripId)
        retryableSpeedChecks.remove(tripId)
        retriedSpeedChecks.remove(tripId)
    }

    suspend fun analyzeCompletedTrip(tripId: String): PostTripAnalysisUiState = analyzeLocal(tripId).state

    private data class AnalyzedTrip(
        val state: PostTripAnalysisUiState,
        val snapshot: CompletedTripGpsSnapshot? = null
    )

    private data class SpeedCheckInput(
        val local: PostTripAnalysisUiState.Ready,
        val snapshot: CompletedTripGpsSnapshot
    )

    private suspend fun analyzeLocal(tripId: String): AnalyzedTrip = try {
        require(tripId.isNotBlank()) { "A recording trip ID is required." }
        val snapshot = source.load(tripId)
        withContext(Dispatchers.Default) {
            require(snapshot.tripId == tripId)
            val rows = snapshot.samples.toList()
            require(rows.all { it.tripId == tripId }) { "Snapshot contains another trip." }
            require(rows.zipWithNext().all { (a, b) -> a.id < b.id }) { "Unstable GPS order." }
            val input = rows.map { PostTripGpsSample(
                timestampMillis = it.recordedAtMillis,
                speedMps = it.speedMetersPerSecond?.toDouble(),
                horizontalAccuracyMeters = it.accuracyMeters?.toDouble()
            ) }
            val result = analyze(tripId, input)
            val issues = result.issues.map { PostTripAnalysisIssue(it.sampleIndex, it.reason.name) }
            val route = rows.mapIndexed { index, point -> ReviewRoutePoint(index, point.id,
                point.recordedAtMillis, point.latitude, point.longitude) }
            val revision = MessageDigest.getInstance("SHA-256").digest(
                ("posttrip-client-v1|default-detector-v1|$tripId|" + rows.joinToString(";")).toByteArray(Charsets.UTF_8)
            ).joinToString("") { "%02x".format(it) }
            val manoeuvreEvents = result.events.map { event ->
                require(event.tripId == tripId && event.startSampleIndex >= 0 &&
                    event.endSampleIndex >= event.startSampleIndex && event.endSampleIndex < rows.size) {
                    "Event indexes do not match the completed GPS snapshot."
                }
                val segment = route.subList(event.startSampleIndex, event.endSampleIndex + 1).toList()
                require(segment.all { it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }) { "Invalid event coordinates." }
                PostTripReviewEvent(event.eventId, tripId, revision, ReviewEventType.valueOf(event.type.name),
                    event.startSampleIndex, event.endSampleIndex, event.startTimeMillis, event.endTimeMillis,
                    event.durationSeconds, event.speedBeforeMps, event.speedAfterMps,
                    event.peakAccelerationMps2, event.averageAccelerationMps2, event.severityScore,
                    event.manoeuvre.hasMissingAccuracy, segment)
            }
            val readyWithoutSpeed = PostTripAnalysisUiState.Ready(
                tripId = tripId,
                analysisRevision = revision,
                chronologicalEvents = manoeuvreEvents,
                rankedEvents = emptyList(),
                routePoints = route,
                issues = issues
            )
            val localSpeedEvents = LocalSpeedExceedanceMapper.toReviewEvents(
                entities = snapshot.localSpeedExceedances,
                local = readyWithoutSpeed,
                samples = rows
            )
            val events = if (localSpeedEvents.isEmpty()) {
                manoeuvreEvents
            } else {
                mergeReviewEvents(readyWithoutSpeed, localSpeedEvents)
            }
            val badIndexes = result.issues.map { it.sampleIndex }.toSet()
            var coverageMillis = 0L
            var enoughData = false
            for (index in 1 until rows.size) {
                if (index in badIndexes || index - 1 in badIndexes) coverageMillis = 0
                else coverageMillis += rows[index].recordedAtMillis - rows[index - 1].recordedAtMillis
                if (coverageMillis >= 1000L) enoughData = true
            }
            if (events.isEmpty() && !enoughData) {
                AnalyzedTrip(
                    PostTripAnalysisUiState.InsufficientData(
                        tripId,
                        "At least one continuous second of reliable speed data is required.",
                        issues
                    )
                )
            } else {
                val byId = manoeuvreEvents.associateBy { it.eventId }
                AnalyzedTrip(
                    state = PostTripAnalysisUiState.Ready(
                        tripId = tripId,
                        analysisRevision = revision,
                        chronologicalEvents = events,
                        rankedEvents = result.rankedEvents.mapNotNull { byId[it.eventId] },
                        routePoints = route,
                        issues = issues
                    ),
                    snapshot = snapshot.copy(samples = rows)
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AnalyzedTrip(PostTripAnalysisUiState.Failed(tripId, error.message ?: "Trip analysis failed."))
    }

    private fun applySpeedResult(
        local: PostTripAnalysisUiState.Ready,
        clientResult: SpeedAnalysisClientResult
    ): PostTripAnalysisUiState.Ready {
        return when (clientResult) {
            is SpeedAnalysisClientResult.Failed ->
                local.copy(speedCheck = SpeedCheckState.Failed(clientResult.reason))
            is SpeedAnalysisClientResult.Success -> {
                // Trip Summary "Above the speed limit" must match Active Trip warning
                // episodes written to driving_events — do not append backend speed events.
                local.copy(
                    speedCheck = SpeedCheckState.Done(
                        status = clientResult.result.analysisStatus,
                        checkedSharePercent = clientResult.result.checkedSharePercent
                    )
                )
            }
        }
    }

}

fun mergeReviewEvents(local: PostTripAnalysisUiState.Ready,
    backendEvents: List<PostTripReviewEvent>): List<PostTripReviewEvent> {
    require(backendEvents.all { event ->
        event.tripId == local.tripId && event.analysisRevision == local.analysisRevision &&
            event.type == ReviewEventType.SPEED_LIMIT_EXCEEDANCE &&
            event.startSampleIndex >= 0 && event.endSampleIndex >= event.startSampleIndex &&
            event.endSampleIndex < local.routePoints.size &&
            event.routePoints == local.routePoints.subList(event.startSampleIndex, event.endSampleIndex + 1)
    }) { "Backend events must reference the same trip and snapshot." }
    val combined = local.chronologicalEvents + backendEvents
    require(combined.map { it.eventId }.distinct().size == combined.size) { "Duplicate event IDs." }
    return combined.sortedWith(compareBy<PostTripReviewEvent> { it.startSampleIndex }.thenBy { it.eventId })
}
