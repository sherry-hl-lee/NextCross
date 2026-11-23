package com.fit5120tp10.nextcross.client.recording

import java.util.concurrent.Executor

/** Serializes sample acceptance with completion. The executor must be FIFO and single-threaded. */
class RecordingWriteGate(private val executor: Executor) {
    private class Active(val tripId: String) {
        var stop: (() -> Unit)? = null
        var writeFailed = false // Accessed only on the writer executor.
    }
    private var active: Active? = null

    @Synchronized fun begin(tripId: String, insert: () -> Unit) {
        check(active == null)
        val recording = Active(tripId)
        active = recording
        enqueue(recording, insert)
    }

    /** Starts and registers the tracker atomically, rejecting delayed service starts. */
    @Synchronized fun attach(tripId: String, start: () -> Unit, stop: () -> Unit): Boolean {
        val recording = active?.takeIf { it.tripId == tripId } ?: return false
        recording.stop = stop
        start()
        return true
    }

    @Synchronized fun submit(tripId: String, write: () -> Unit): Boolean {
        val recording = active?.takeIf { it.tripId == tripId } ?: return false
        enqueue(recording, write)
        return true
    }

    @Synchronized fun finish(tripId: String, markComplete: () -> Unit) {
        val recording = requireNotNull(active?.takeIf { it.tripId == tripId })
        active = null // Late callbacks cannot enqueue behind the completion marker.
        recording.stop?.invoke()
        executor.execute {
            // A failed write leaves the database trip incomplete; analysis must refuse it.
            if (!recording.writeFailed) markComplete()
        }
    }

    private fun enqueue(recording: Active, write: () -> Unit) {
        executor.execute {
            try { write() } catch (error: Exception) {
                recording.writeFailed = true
                throw error
            }
        }
    }
}
