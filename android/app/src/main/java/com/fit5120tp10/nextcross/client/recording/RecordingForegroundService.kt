package com.fit5120tp10.nextcross.client.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Records GPS and hard accel/brake events while a trip is active.
 * Uses a foreground service so recording can continue when the screen is off.
 */
class RecordingForegroundService : Service() {
    private var tripId: String? = null
    private var locationTracker: LocationManagerTracker? = null
    private var sampleProcessor: TripSampleProcessor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        val nextTripId = intent?.getStringExtra(EXTRA_TRIP_ID)
            ?: RecordingLocator.sessionController.session?.tripId
        if (nextTripId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (tripId != nextTripId) {
            stopTrackers()
            tripId = nextTripId
            startTrackers(nextTripId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTrackers()
        super.onDestroy()
    }

    private fun startAsForeground() {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NextCross")
            .setContentText("Recording trip")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Trip recording",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startTrackers(activeTripId: String) {
        val dao = RecordingLocator.database(this).recordingDao()
        val processor = TripSampleProcessor(dao)
        sampleProcessor = processor

        locationTracker = LocationManagerTracker(this) { location ->
            if (!RecordingLocator.sessionController.isRecording) return@LocationManagerTracker
            RecordingLocator.writeGate.submit(activeTripId) {
                processor.record(
                    TripLocationSample(
                        tripId = activeTripId,
                        recordedAtMillis = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                    )
                )
            }
        }.also { tracker ->
            RecordingLocator.writeGate.attach(activeTripId, tracker::start, tracker::stop)
        }
    }

    private fun stopTrackers() {
        locationTracker?.stop()
        locationTracker = null
        sampleProcessor?.let { processor -> RecordingLocator.io.execute { processor.reset() } }
        sampleProcessor = null
    }

    companion object {
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_TRIP_ID = "tripId"

        fun start(context: Context, tripId: String) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}
