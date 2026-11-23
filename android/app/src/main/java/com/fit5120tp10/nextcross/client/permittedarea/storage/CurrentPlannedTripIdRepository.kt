package com.fit5120tp10.nextcross.client.permittedarea.storage

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/** Client B-owned pointer that makes the current planned-trip configuration discoverable. */
class CurrentPlannedTripIdRepository(context: Context) {
    private val atomicFile = AtomicFile(
        File(context.applicationContext.noBackupFilesDir, STORAGE_FILE_NAME)
    )

    fun get(): String? = synchronized(storageLock) {
        if (!atomicFile.baseFile.exists()) return@synchronized null
        try {
            val properties = Properties().also { loaded ->
                atomicFile.openRead().use { input -> loaded.load(input) }
            }
            check(properties.getProperty(KEY_SCHEMA_VERSION) == SCHEMA_VERSION) {
                "Unsupported current planned-trip schema."
            }
            properties.getProperty(KEY_PLANNED_TRIP_ID)
                ?.takeIf { it.isNotBlank() }
                ?: error("Current planned-trip identifier is missing.")
        } catch (exception: Exception) {
            throw CurrentPlannedTripIdStorageException(
                "Could not read the current planned-trip identifier.",
                exception
            )
        }
    }

    fun save(plannedTripId: String) {
        require(plannedTripId.isNotBlank()) { "Planned trip identifier must not be blank." }
        synchronized(storageLock) {
            val parent = atomicFile.baseFile.parentFile
                ?: throw CurrentPlannedTripIdStorageException(
                    "Current planned-trip storage has no parent directory."
                )
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw CurrentPlannedTripIdStorageException(
                    "Could not create the Client B storage directory."
                )
            }
            if (!parent.isDirectory) {
                throw CurrentPlannedTripIdStorageException(
                    "Client B storage path is not a directory."
                )
            }

            val properties = Properties().apply {
                setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                setProperty(KEY_PLANNED_TRIP_ID, plannedTripId)
            }
            var output: FileOutputStream? = null
            try {
                output = atomicFile.startWrite()
                properties.store(output, null)
                atomicFile.finishWrite(output)
            } catch (exception: Exception) {
                output?.let { runCatching { atomicFile.failWrite(it) } }
                throw CurrentPlannedTripIdStorageException(
                    "Could not save the current planned-trip identifier.",
                    exception
                )
            }
        }
    }

    companion object {
        private const val STORAGE_FILE_NAME = "client-b/current-planned-trip.properties"
        private const val SCHEMA_VERSION = "1"
        private const val KEY_SCHEMA_VERSION = "schema.version"
        private const val KEY_PLANNED_TRIP_ID = "plannedTrip.id"
        private val storageLock = Any()
    }
}

class CurrentPlannedTripIdStorageException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
