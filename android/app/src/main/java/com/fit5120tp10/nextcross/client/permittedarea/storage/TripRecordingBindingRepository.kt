package com.fit5120tp10.nextcross.client.permittedarea.storage

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/** Stable association between a parent-planned trip and Client A's recording session. */
data class TripRecordingBinding(
    val plannedTripId: String,
    val recordingTripId: String
) {
    init {
        require(plannedTripId.isNotBlank()) { "Planned trip identifier must not be blank." }
        require(recordingTripId.isNotBlank()) { "Recording trip identifier must not be blank." }
    }
}

/**
 * Atomic, Client B-owned planned-trip to recording-trip index.
 *
 * The complete one-to-one index lives in one file so replacement of either side is atomic. This
 * avoids adding a mapping column/table to Client A's Room database.
 */
class TripRecordingBindingRepository(context: Context) {
    private val atomicFile = AtomicFile(
        File(
            context.applicationContext.noBackupFilesDir,
            STORAGE_FILE_NAME
        )
    )

    /** Saves [binding], replacing any previous binding containing either identifier. */
    fun save(binding: TripRecordingBinding) {
        validateBinding(binding)

        synchronized(storageLock) {
            val updated = readBindings().filterNot {
                it.plannedTripId == binding.plannedTripId ||
                    it.recordingTripId == binding.recordingTripId
            } + binding
            writeBindings(updated)
        }
    }

    fun getByPlannedTripId(plannedTripId: String): TripRecordingBinding? {
        require(plannedTripId.isNotBlank()) { "Planned trip identifier must not be blank." }
        return synchronized(storageLock) {
            readBindings().firstOrNull { it.plannedTripId == plannedTripId }
        }
    }

    fun getByRecordingTripId(recordingTripId: String): TripRecordingBinding? {
        require(recordingTripId.isNotBlank()) { "Recording trip identifier must not be blank." }
        return synchronized(storageLock) {
            readBindings().firstOrNull { it.recordingTripId == recordingTripId }
        }
    }

    fun deleteByPlannedTripId(plannedTripId: String): Boolean {
        require(plannedTripId.isNotBlank()) { "Planned trip identifier must not be blank." }
        return synchronized(storageLock) {
            val current = readBindings()
            val updated = current.filterNot { it.plannedTripId == plannedTripId }
            if (updated.size == current.size) {
                return@synchronized false
            }
            if (updated.isEmpty()) {
                deleteStorageFile()
            } else {
                writeBindings(updated)
            }
            true
        }
    }

    private fun validateBinding(binding: TripRecordingBinding) {
        try {
            TripRecordingBinding(binding.plannedTripId, binding.recordingTripId)
        } catch (exception: IllegalArgumentException) {
            throw TripRecordingBindingStorageException("Trip binding is invalid.", exception)
        }
    }

    private fun readBindings(): List<TripRecordingBinding> {
        if (!atomicFile.baseFile.exists()) {
            return emptyList()
        }

        val properties = try {
            Properties().also { loaded ->
                atomicFile.openRead().use { input -> loaded.load(input) }
            }
        } catch (exception: Exception) {
            throw TripRecordingBindingStorageException(
                "Could not read planned-to-recording trip bindings.",
                exception
            )
        }

        try {
            val schemaVersion = properties.getProperty(KEY_SCHEMA_VERSION)
                ?: error("Missing binding schema version.")
            check(schemaVersion == SCHEMA_VERSION) {
                "Unsupported binding schema version '$schemaVersion'."
            }

            val count = properties.getProperty(KEY_BINDING_COUNT)?.toInt()
                ?: error("Missing binding count.")
            require(count >= 0) { "Binding count must not be negative." }

            val bindings = (0 until count).map { index ->
                TripRecordingBinding(
                    plannedTripId = properties.getProperty("binding.$index.plannedTripId")
                        ?: error("Missing planned trip identifier at binding $index."),
                    recordingTripId = properties.getProperty("binding.$index.recordingTripId")
                        ?: error("Missing recording trip identifier at binding $index.")
                )
            }
            check(bindings.map { it.plannedTripId }.toSet().size == bindings.size) {
                "Duplicate planned trip identifier in binding storage."
            }
            check(bindings.map { it.recordingTripId }.toSet().size == bindings.size) {
                "Duplicate recording trip identifier in binding storage."
            }
            return bindings
        } catch (exception: Exception) {
            throw TripRecordingBindingStorageException(
                "Stored planned-to-recording trip bindings are invalid.",
                exception
            )
        }
    }

    private fun writeBindings(bindings: List<TripRecordingBinding>) {
        val parent = atomicFile.baseFile.parentFile
            ?: throw TripRecordingBindingStorageException(
                "Binding storage has no parent directory."
            )
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw TripRecordingBindingStorageException(
                "Could not create Client B binding directory."
            )
        }
        if (!parent.isDirectory) {
            throw TripRecordingBindingStorageException(
                "Client B binding path is not a directory."
            )
        }

        val properties = Properties().apply {
            setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            setProperty(KEY_BINDING_COUNT, bindings.size.toString())
            bindings.sortedBy { it.plannedTripId }.forEachIndexed { index, binding ->
                setProperty("binding.$index.plannedTripId", binding.plannedTripId)
                setProperty("binding.$index.recordingTripId", binding.recordingTripId)
            }
        }

        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            properties.store(output, null)
            atomicFile.finishWrite(output)
        } catch (exception: Exception) {
            output?.let { runCatching { atomicFile.failWrite(it) } }
            throw TripRecordingBindingStorageException(
                "Could not save planned-to-recording trip bindings.",
                exception
            )
        }
    }

    private fun deleteStorageFile() {
        atomicFile.delete()
        if (atomicFile.baseFile.exists()) {
            throw TripRecordingBindingStorageException(
                "Could not delete planned-to-recording trip binding storage."
            )
        }
    }

    companion object {
        private const val STORAGE_FILE_NAME = "client-b/trip-recording-bindings.properties"
        private const val SCHEMA_VERSION = "1"
        private const val KEY_SCHEMA_VERSION = "schema.version"
        private const val KEY_BINDING_COUNT = "binding.count"

        private val storageLock = Any()
    }
}

class TripRecordingBindingStorageException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
