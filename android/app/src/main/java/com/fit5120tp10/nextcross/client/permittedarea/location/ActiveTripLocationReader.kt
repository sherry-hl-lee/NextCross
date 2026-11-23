package com.fit5120tp10.nextcross.client.permittedarea.location

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.recording.storage.RecordingDao
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ActiveTripLocationObservation(
    val point: GeographicPoint,
    val recordedAtMillis: Long
) {
    init {
        require(recordedAtMillis >= 0L) { "GPS observation timestamp must not be negative." }
    }
}

/**
 * Read-only adapter over Client A's GPS storage for the currently recording trip.
 *
 * It polls the existing DAO at roughly one-second intervals, advances a constant-size Room entity
 * id cursor, and calls [onLocation] only for newly observed valid rows. It never asks Android for
 * location and never writes GPS data.
 */
class ActiveTripLocationReader(
    private val recordingDao: RecordingDao,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val executor: ScheduledExecutorService = newReaderExecutor()
) : AutoCloseable {
    private val stateLock = Any()
    private var activeRead: ActiveRead? = null
    private var scheduledPoll: ScheduledFuture<*>? = null
    private var closed = false

    init {
        require(pollIntervalMillis > 0L) { "GPS polling interval must be positive." }
    }

    /** Starts one read session. Call [stop] before starting a different recording trip. */
    fun start(
        recordingTripId: String,
        onLocation: (ActiveTripLocationObservation) -> Unit
    ) {
        require(recordingTripId.isNotBlank()) { "Recording trip identifier must not be blank." }

        synchronized(stateLock) {
            check(!closed) { "A closed active-trip location reader cannot be restarted." }
            check(activeRead == null) { "Active-trip location reading has already started." }

            val read = ActiveRead(recordingTripId, onLocation)
            activeRead = read
            scheduledPoll = executor.scheduleWithFixedDelay(
                {
                    try {
                        poll(read)
                    } catch (_: RuntimeException) {
                        // A consumer failure must not cancel all later boundary checks. A future
                        // backend sink must durably enqueue before returning; HTTP does not run here.
                    }
                },
                0L,
                pollIntervalMillis,
                TimeUnit.MILLISECONDS
            )
        }
    }

    /**
     * Stops the active read session. Once this method returns, no further location callback from
     * that session can begin; an already-running callback is allowed to finish before it returns.
     */
    fun stop() {
        synchronized(stateLock) {
            activeRead = null
            scheduledPoll?.cancel(false)
            scheduledPoll = null
        }
    }

    /** Permanently releases this reader's private executor. */
    override fun close() {
        stop()
        synchronized(stateLock) {
            if (!closed) {
                closed = true
                executor.shutdownNow()
            }
        }
    }

    private fun poll(read: ActiveRead) {
        val rows = try {
            recordingDao.gpsPointsForTrip(read.recordingTripId)
        } catch (_: RuntimeException) {
            // A missing/temporarily unreadable observation must not be interpreted as a breach.
            return
        }

        synchronized(stateLock) {
            if (activeRead !== read) {
                return
            }

            val cursorAtPollStart = read.lastSeenEntityId
            rows.filter { row -> row.id > cursorAtPollStart }.forEach { row ->
                if (activeRead !== read) {
                    return@forEach
                }
                read.lastSeenEntityId = maxOf(read.lastSeenEntityId, row.id)

                val observation = try {
                    ActiveTripLocationObservation(
                        point = GeographicPoint(row.latitude, row.longitude),
                        recordedAtMillis = row.recordedAtMillis
                    )
                } catch (_: IllegalArgumentException) {
                    // Corrupt/invalid coordinates are unavailable data, not evidence of a breach.
                    return@forEach
                }
                read.onLocation(observation)
            }
        }
    }

    private class ActiveRead(
        val recordingTripId: String,
        val onLocation: (ActiveTripLocationObservation) -> Unit,
        var lastSeenEntityId: Long = Long.MIN_VALUE
    )

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L

        private fun newReaderExecutor(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "client-b-active-trip-location-reader").apply {
                    isDaemon = true
                }
            }
        }
    }
}
