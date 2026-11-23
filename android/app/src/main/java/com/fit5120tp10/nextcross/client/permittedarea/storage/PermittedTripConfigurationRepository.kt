package com.fit5120tp10.nextcross.client.permittedarea.storage

import android.content.Context
import android.util.AtomicFile
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.ParentNotificationContact
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea
import com.fit5120tp10.nextcross.client.permittedarea.PermittedTripConfiguration
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties

/**
 * Client B-owned local storage for planned-trip permitted-area configuration.
 *
 * Each planned trip is stored in its own [AtomicFile] under [Context.getNoBackupFilesDir]. This
 * deliberately stays outside Client A's Room schema: the recording database continues to contain
 * only active-trip GPS and movement observations.
 */
class PermittedTripConfigurationRepository(context: Context) {
    private val storageDirectory = File(
        context.applicationContext.noBackupFilesDir,
        STORAGE_DIRECTORY_NAME
    )

    /** Atomically creates or replaces the configuration for [configuration.tripId]. */
    fun save(configuration: PermittedTripConfiguration) {
        // Re-constructing validates every persisted domain value even if this repository is later
        // called from Java or through a deserialisation adapter.
        validateConfiguration(configuration)

        synchronized(storageLock) {
            ensureStorageDirectory()
            val properties = Properties().apply {
                setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                setProperty(KEY_TRIP_ID, configuration.tripId)
                setProperty(
                    KEY_CENTRE_LATITUDE,
                    configuration.permittedArea.centre.latitude.toString()
                )
                setProperty(
                    KEY_CENTRE_LONGITUDE,
                    configuration.permittedArea.centre.longitude.toString()
                )
                setProperty(
                    KEY_RADIUS_KILOMETRES,
                    configuration.permittedArea.radiusKilometres.toString()
                )
                setProperty(KEY_PARENT_EMAIL, configuration.parentContact.email)
            }
            writeProperties(atomicFileFor(configuration.tripId), properties)
        }
    }

    /** Returns the validated configuration for [plannedTripId], or null if it has not been saved. */
    fun get(plannedTripId: String): PermittedTripConfiguration? {
        requireTripId(plannedTripId)

        return synchronized(storageLock) {
            val atomicFile = atomicFileFor(plannedTripId)
            if (!atomicFile.baseFile.exists()) {
                return@synchronized null
            }

            val properties = readProperties(atomicFile)
            parseConfiguration(properties, plannedTripId)
        }
    }

    /** Deletes one planned trip without touching Client A's trip-recording database. */
    fun delete(plannedTripId: String): Boolean {
        requireTripId(plannedTripId)

        return synchronized(storageLock) {
            val atomicFile = atomicFileFor(plannedTripId)
            if (!atomicFile.baseFile.exists()) {
                return@synchronized false
            }
            atomicFile.delete()
            if (atomicFile.baseFile.exists()) {
                throw PermittedTripConfigurationStorageException(
                    "Could not delete permitted-area configuration for trip '$plannedTripId'."
                )
            }
            true
        }
    }

    private fun atomicFileFor(plannedTripId: String): AtomicFile {
        return AtomicFile(
            File(storageDirectory, "${sha256Hex(plannedTripId)}.properties")
        )
    }

    private fun ensureStorageDirectory() {
        if (
            !storageDirectory.exists() &&
            !storageDirectory.mkdirs() &&
            !storageDirectory.isDirectory
        ) {
            throw PermittedTripConfigurationStorageException(
                "Could not create Client B configuration directory."
            )
        }
        if (!storageDirectory.isDirectory) {
            throw PermittedTripConfigurationStorageException(
                "Client B configuration path is not a directory."
            )
        }
    }

    private fun writeProperties(atomicFile: AtomicFile, properties: Properties) {
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            properties.store(output, null)
            atomicFile.finishWrite(output)
        } catch (exception: Exception) {
            output?.let { runCatching { atomicFile.failWrite(it) } }
            throw PermittedTripConfigurationStorageException(
                "Could not save permitted-area configuration.",
                exception
            )
        }
    }

    private fun readProperties(atomicFile: AtomicFile): Properties {
        return try {
            Properties().also { properties ->
                atomicFile.openRead().use { input -> properties.load(input) }
            }
        } catch (exception: Exception) {
            throw PermittedTripConfigurationStorageException(
                "Could not read permitted-area configuration.",
                exception
            )
        }
    }

    private fun parseConfiguration(
        properties: Properties,
        requestedTripId: String
    ): PermittedTripConfiguration {
        try {
            val schemaVersion = properties.required(KEY_SCHEMA_VERSION)
            check(schemaVersion == SCHEMA_VERSION) {
                "Unsupported configuration schema version '$schemaVersion'."
            }

            val storedTripId = properties.required(KEY_TRIP_ID)
            check(storedTripId == requestedTripId) {
                "Stored trip identifier does not match the requested trip."
            }

            // Read only the supported properties; legacy parent.mobile is ignored.
            return PermittedTripConfiguration(
                tripId = storedTripId,
                permittedArea = PermittedArea(
                    centre = GeographicPoint(
                        latitude = properties.required(KEY_CENTRE_LATITUDE).toDouble(),
                        longitude = properties.required(KEY_CENTRE_LONGITUDE).toDouble()
                    ),
                    radiusKilometres = properties.required(KEY_RADIUS_KILOMETRES).toDouble()
                ),
                parentContact = ParentNotificationContact(
                    email = properties.required(KEY_PARENT_EMAIL)
                )
            )
        } catch (exception: Exception) {
            if (exception is PermittedTripConfigurationStorageException) {
                throw exception
            }
            throw PermittedTripConfigurationStorageException(
                "Stored permitted-area configuration is invalid for trip '$requestedTripId'.",
                exception
            )
        }
    }

    private fun validateConfiguration(configuration: PermittedTripConfiguration) {
        try {
            PermittedTripConfiguration(
                tripId = configuration.tripId,
                permittedArea = PermittedArea(
                    centre = GeographicPoint(
                        latitude = configuration.permittedArea.centre.latitude,
                        longitude = configuration.permittedArea.centre.longitude
                    ),
                    radiusKilometres = configuration.permittedArea.radiusKilometres
                ),
                parentContact = ParentNotificationContact(
                    email = configuration.parentContact.email
                )
            )
        } catch (exception: IllegalArgumentException) {
            throw PermittedTripConfigurationStorageException(
                "Permitted-area configuration is invalid.",
                exception
            )
        }
    }

    private fun Properties.required(key: String): String {
        return getProperty(key)
            ?: throw PermittedTripConfigurationStorageException(
                "Stored permitted-area configuration is missing '$key'."
            )
    }

    private fun requireTripId(plannedTripId: String) {
        require(plannedTripId.isNotBlank()) { "Planned trip identifier must not be blank." }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0f])
            }
        }
    }

    companion object {
        private const val STORAGE_DIRECTORY_NAME =
            "client-b/permitted-trip-configurations"
        private const val SCHEMA_VERSION = "1"
        private const val KEY_SCHEMA_VERSION = "schema.version"
        private const val KEY_TRIP_ID = "trip.id"
        private const val KEY_CENTRE_LATITUDE = "permittedArea.centre.latitude"
        private const val KEY_CENTRE_LONGITUDE = "permittedArea.centre.longitude"
        private const val KEY_RADIUS_KILOMETRES = "permittedArea.radiusKilometres"
        private const val KEY_PARENT_EMAIL = "parent.email"
        private const val HEX_DIGITS = "0123456789abcdef"

        /** Serialises read-modify-write work across repository instances in the app process. */
        private val storageLock = Any()
    }
}

class PermittedTripConfigurationStorageException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
