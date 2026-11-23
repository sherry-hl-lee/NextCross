package com.fit5120tp10.nextcross.client.recording

import android.content.Context
import com.fit5120tp10.nextcross.client.recording.storage.RecordingDatabase
import com.fit5120tp10.nextcross.client.trip.TripSessionController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shared trip state and database. Writes run on a background thread so the UI does not freeze. */
object RecordingLocator {
    val sessionController = TripSessionController()
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    val writeGate = RecordingWriteGate(io)

    @Volatile
    private var database: RecordingDatabase? = null

    fun database(context: Context): RecordingDatabase {
        return database ?: synchronized(this) {
            database ?: RecordingDatabase.create(context).also { database = it }
        }
    }
}
