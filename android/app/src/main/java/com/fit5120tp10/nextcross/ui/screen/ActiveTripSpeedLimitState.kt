package com.fit5120tp10.nextcross.ui.screen

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.fit5120tp10.nextcross.BuildConfig
import com.fit5120tp10.nextcross.client.recording.RecordingLocator
import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import com.fit5120tp10.nextcross.client.speedlimit.SpeedExceedanceRecorderLocator
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitContext
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitExceedanceSeverity
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitLocator
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitLookupRequest
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitLookupResult
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitWarningEvaluator
import com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitWarningState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt

/** UI-owned presentation state derived from the current recording trip. */
@Immutable
data class ActiveTripSpeedLimitUiState(
    val currentSpeedKmh: Int? = null,
    val limitKmh: Int? = null,
    val warningState: SpeedLimitWarningState = SpeedLimitWarningState.Inactive,
    val severity: SpeedLimitExceedanceSeverity? = null,
    val isLimitAvailable: Boolean = false
)

/**
 * Reads the most recent GPS sample already written by the recording service and performs a
 * throttled speed-limit lookup. This consumes Sherry's public client API without duplicating its
 * request, parsing, or warning rules.
 *
 * Active↔Inactive transitions are persisted into local driving_events for Trip Summary.
 */
@Composable
fun rememberActiveTripSpeedLimitState(recordingTripId: String?): State<ActiveTripSpeedLimitUiState> {
    val appContext = LocalContext.current.applicationContext
    val state = remember(recordingTripId) { mutableStateOf(ActiveTripSpeedLimitUiState()) }

    LaunchedEffect(recordingTripId, appContext) {
        if (recordingTripId.isNullOrBlank()) return@LaunchedEffect

        val dao = RecordingLocator.database(appContext).recordingDao()
        val client = SpeedLimitLocator.get(appContext)
        val recorder = SpeedExceedanceRecorderLocator.get(appContext)
        var lastObservedPointId = Long.MIN_VALUE
        var lastLookupElapsedMillis = Long.MIN_VALUE
        var speedLimitContext: SpeedLimitContext? = null

        while (isActive) {
            val uiUpdate = withContext(Dispatchers.IO) {
                val latestPoint = dao.gpsPointsForTrip(recordingTripId).lastOrNull()
                    ?: return@withContext null
                if (latestPoint.id == lastObservedPointId) return@withContext null
                lastObservedPointId = latestPoint.id

                val shouldLookup = lastLookupElapsedMillis == Long.MIN_VALUE ||
                    SystemClock.elapsedRealtime() - lastLookupElapsedMillis >= LOOKUP_INTERVAL_MILLIS
                if (shouldLookup) {
                    lastLookupElapsedMillis = SystemClock.elapsedRealtime()
                    speedLimitContext = lookupSpeedLimit(
                        client = client,
                        point = latestPoint
                    )
                }

                val assessment = SpeedLimitWarningEvaluator.assess(
                    context = speedLimitContext,
                    recordedSpeedMps = latestPoint.speedMetersPerSecond?.toDouble()
                )
                recorder.onAssessment(
                    tripId = recordingTripId,
                    point = latestPoint,
                    context = speedLimitContext,
                    assessment = assessment
                )
                ActiveTripSpeedLimitUiState(
                    currentSpeedKmh = latestPoint.speedMetersPerSecond?.toKmh(),
                    limitKmh = speedLimitContext?.limitKmh,
                    warningState = assessment.state,
                    severity = assessment.severity,
                    isLimitAvailable = speedLimitContext?.isUsableForWarning == true
                )
            }
            if (uiUpdate != null) {
                state.value = uiUpdate
            }

            delay(POLL_INTERVAL_MILLIS)
        }
    }

    return state
}

private suspend fun lookupSpeedLimit(
    client: com.fit5120tp10.nextcross.client.speedlimit.SpeedLimitClient,
    point: GpsPointEntity
): SpeedLimitContext? = withContext(Dispatchers.IO) {
    val result = client.lookup(
        SpeedLimitLookupRequest(
            latitude = point.latitude,
            longitude = point.longitude,
            observedAtIso = Instant.ofEpochMilli(point.recordedAtMillis).toString(),
            headingDegrees = null,
            radiusMeters = 30.0
        )
    )
    if (BuildConfig.DEBUG) {
        val outcome = when (result) {
            is SpeedLimitLookupResult.Success ->
                "status=${result.context.status.wireValue} limit=${result.context.limitKmh ?: "none"}"
            is SpeedLimitLookupResult.HttpFailure -> "http=${result.statusCode}"
            is SpeedLimitLookupResult.TransportFailure ->
                "transport=${result.cause.javaClass.simpleName}"
            is SpeedLimitLookupResult.InvalidPayload -> "invalid_payload"
        }
        Log.d("NextCrossSpeedLimit", outcome)
    }
    (result as? SpeedLimitLookupResult.Success)
        ?.context
        ?.takeIf { it.isUsableForWarning }
}

private fun Float.toKmh(): Int = (this * 3.6f).roundToInt()

private const val POLL_INTERVAL_MILLIS = 1_000L
private const val LOOKUP_INTERVAL_MILLIS = 8_000L
