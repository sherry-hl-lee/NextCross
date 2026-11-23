package com.fit5120tp10.nextcross.client.posttrip

import android.content.Context
import com.fit5120tp10.nextcross.R
import com.fit5120tp10.nextcross.client.detection.DrivingEventType
import com.fit5120tp10.nextcross.client.recording.RecordingLocator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PostTripAnalysisLocator {
    @Volatile
    private var coordinator: PostTripAnalysisCoordinator? = null

    fun create(context: Context): PostTripAnalysisCoordinator {
        coordinator?.let { return it }

        val appContext = context.applicationContext
        val speedClient = runCatching {
            val base = appContext.getString(R.string.speed_analysis_trips_url).trim()
            if (base.isBlank()) null else SpeedAnalysisHttpClient(base)
        }.getOrNull()
        return synchronized(this) {
            coordinator ?: PostTripAnalysisCoordinator(
            source = CompletedTripSnapshotSource { tripId ->
                suspendCancellableCoroutine { continuation ->
                    RecordingLocator.io.execute {
                        if (continuation.isActive) {
                            try {
                                val database = RecordingLocator.database(appContext)
                                var snapshot: CompletedTripGpsSnapshot? = null
                                database.runInTransaction {
                                    val dao = database.recordingDao()
                                    val trip = requireNotNull(dao.tripById(tripId)) { "Trip not found." }
                                    val ended = requireNotNull(trip.endedAtMillis) { "Trip recording is not fully complete." }
                                    snapshot = CompletedTripGpsSnapshot(
                                        tripId = tripId,
                                        samples = dao.gpsPointsForTrip(tripId).toList(),
                                        startedAtMillis = trip.startedAtMillis,
                                        endedAtMillis = ended,
                                        localSpeedExceedances = dao.drivingEventsForTripByType(
                                            tripId,
                                            DrivingEventType.SpeedLimitExceedance.name
                                        ).toList()
                                    )
                                }
                                continuation.resume(requireNotNull(snapshot))
                            } catch (error: Exception) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            },
                speedClient = speedClient
            ).also { coordinator = it }
        }
    }
}
