package com.fit5120tp10.nextcross.client.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.fit5120tp10.nextcross.client.recording.storage.TripEntity
import com.fit5120tp10.nextcross.client.speedlimit.SpeedExceedanceRecorderLocator
import com.fit5120tp10.nextcross.client.trip.TripCommandResult
import com.fit5120tp10.nextcross.client.trip.TripSessionController

/**
 * UI calls this to start or end recording.
 * Does not show a permission dialog. The foreground service starts only if location is granted.
 */
class TripRecordingCoordinator(
    context: Context,
    private val sessions: TripSessionController = RecordingLocator.sessionController
) {
    private val appContext = context.applicationContext

    fun startRecording(): TripCommandResult {
        val result = sessions.startTrip()
        if (result is TripCommandResult.Success) {
            val session = result.session
            RecordingLocator.writeGate.begin(session.tripId) {
                RecordingLocator.database(appContext).recordingDao().insertTrip(
                    TripEntity(
                        tripId = session.tripId,
                        startedAtMillis = session.startedAtMillis,
                        endedAtMillis = null
                    )
                )
            }
            startServiceIfRecording()
        }
        return result
    }

    fun endRecording(): TripCommandResult {
        val result = sessions.endTrip()
        if (result is TripCommandResult.Success) {
            val session = result.session
            val endedAt = session.endedAtMillis
            if (endedAt != null) {
                RecordingLocator.writeGate.finish(session.tripId) {
                    val dao = RecordingLocator.database(appContext).recordingDao()
                    val lastPoint = dao.gpsPointsForTrip(session.tripId).lastOrNull()
                    SpeedExceedanceRecorderLocator.get(appContext)
                        .closeOpenSegment(session.tripId, lastPoint)
                    dao.endTrip(session.tripId, endedAt)
                }
            }
            RecordingForegroundService.stop(appContext)
        }
        return result
    }

    /** Stops an active recording and removes all of its local, incomplete data. */
    fun cancelRecording(): TripCommandResult {
        val result = sessions.endTrip()
        if (result is TripCommandResult.Success) {
            val session = result.session
            RecordingForegroundService.stop(appContext)
            SpeedExceedanceRecorderLocator.get(appContext).discardOpenSegment(session.tripId)
            RecordingLocator.writeGate.finish(session.tripId) {
                RecordingLocator.database(appContext).recordingDao().apply {
                    deleteTripReport(session.tripId)
                    deleteGpsPointsForTrip(session.tripId)
                    deleteDrivingEventsForTrip(session.tripId)
                    deleteTrip(session.tripId)
                }
            }
        }
        return result
    }

    /** Start the foreground service if a trip is already recording and location is now granted. */
    fun startServiceIfRecording() {
        val session = sessions.session
        if (!sessions.isRecording || session == null || !hasFineLocationPermission()) {
            return
        }
        RecordingForegroundService.start(appContext, session.tripId)
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
