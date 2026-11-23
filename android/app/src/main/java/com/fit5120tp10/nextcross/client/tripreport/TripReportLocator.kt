package com.fit5120tp10.nextcross.client.tripreport

import android.content.Context
import com.fit5120tp10.nextcross.client.permittedarea.storage.PermittedTripConfigurationRepository
import com.fit5120tp10.nextcross.client.permittedarea.storage.TripRecordingBindingRepository
import com.fit5120tp10.nextcross.client.recording.RecordingLocator

/** Process-local facade for on-device trip report history. */
object TripReportLocator {
    @Volatile
    private var repository: TripReportRepository? = null

    fun get(context: Context): TripReportRepository {
        return repository ?: synchronized(this) {
            repository ?: create(context.applicationContext).also { repository = it }
        }
    }

    fun replaceForTests(replacement: TripReportRepository?) {
        synchronized(this) {
            repository = replacement
        }
    }

    private fun create(context: Context): TripReportRepository {
        val dao = RecordingLocator.database(context).recordingDao()
        val store = TripReportRepository(
            recordingDao = dao,
            bindingRepository = TripRecordingBindingRepository(context),
            configurationRepository = PermittedTripConfigurationRepository(context)
        )
        //Clear anything that expired while the app was closed.
        runCatching { store.purgeExpiredReports() }
        return store
    }
}
