package com.fit5120tp10.nextcross.client.permittedarea.runtime

import android.content.Context
import com.fit5120tp10.nextcross.client.permittedarea.PermittedAreaBreachMonitor
import com.fit5120tp10.nextcross.client.permittedarea.PermittedAreaMonitoringResult
import com.fit5120tp10.nextcross.client.permittedarea.PermittedTripConfiguration
import com.fit5120tp10.nextcross.client.permittedarea.location.ActiveTripLocationObservation
import com.fit5120tp10.nextcross.client.permittedarea.location.ActiveTripLocationPrerequisiteChecker
import com.fit5120tp10.nextcross.client.permittedarea.location.ActiveTripLocationStartFailure
import com.fit5120tp10.nextcross.client.permittedarea.location.ActiveTripLocationReader
import com.fit5120tp10.nextcross.client.permittedarea.location.AndroidActiveTripLocationPrerequisiteChecker
import com.fit5120tp10.nextcross.client.permittedarea.storage.PermittedTripConfigurationRepository
import com.fit5120tp10.nextcross.client.permittedarea.storage.CurrentPlannedTripIdRepository
import com.fit5120tp10.nextcross.client.permittedarea.storage.TripRecordingBinding
import com.fit5120tp10.nextcross.client.permittedarea.storage.TripRecordingBindingRepository
import com.fit5120tp10.nextcross.client.recording.RecordingLocator
import com.fit5120tp10.nextcross.client.recording.TripRecordingCoordinator
import com.fit5120tp10.nextcross.client.trip.TripCommandRejectReason
import com.fit5120tp10.nextcross.client.trip.TripCommandResult
import com.fit5120tp10.nextcross.client.trip.TripSession

sealed class ClientBTripStartResult {
    /**
     * [recordingSession] is the Client A session created by the same start operation. UI can use
     * it for the existing active-trip/summary flow without reading Client A's internal state.
     */
    data class Started(
        val binding: TripRecordingBinding,
        val recordingSession: TripSession
    ) : ClientBTripStartResult()
    data object MissingPlannedTripConfiguration : ClientBTripStartResult()
    data object ClientBRuntimeAlreadyActive : ClientBTripStartResult()
    data class LocationUnavailable(
        val reason: ActiveTripLocationStartFailure
    ) : ClientBTripStartResult()
    data class RecordingRejected(
        val reason: TripCommandRejectReason
    ) : ClientBTripStartResult()
}

/**
 * Client B lifecycle integration around Client A's recorder and read-only GPS interface.
 *
 * Review persists the fixed planned-trip configuration. Start creates Client A's recording,
 * persists the planned-to-recording id binding, then checks only newly recorded DAO locations.
 * End disables checking before asking Client A to end its recording session.
 */
class ClientBTripRuntime(
    context: Context,
    private val eventSink: AreaBreachEventSink,
    private val configurationRepository: PermittedTripConfigurationRepository =
        PermittedTripConfigurationRepository(context),
    private val currentPlannedTripIdRepository: CurrentPlannedTripIdRepository =
        CurrentPlannedTripIdRepository(context),
    private val bindingRepository: TripRecordingBindingRepository =
        TripRecordingBindingRepository(context),
    private val recordingCoordinator: TripRecordingCoordinator =
        TripRecordingCoordinator(context),
    private val breachMonitor: PermittedAreaBreachMonitor = PermittedAreaBreachMonitor(),
    private val locationPrerequisiteChecker: ActiveTripLocationPrerequisiteChecker =
        AndroidActiveTripLocationPrerequisiteChecker(context),
    private val locationReader: ActiveTripLocationReader = ActiveTripLocationReader(
        RecordingLocator.database(context.applicationContext).recordingDao()
    ),
    private val afterTripCloudCleanup: ((plannedTripId: String) -> Unit)? = null
) : AutoCloseable {
    private val lifecycleOperationLock = Any()
    private val activeStateLock = Any()
    private var activeBinding: TripRecordingBinding? = null

    val currentBinding: TripRecordingBinding?
        get() = synchronized(activeStateLock) { activeBinding }

    /** Called by Review Trip Details after UI validation and the one-shot centre capture. */
    fun saveReviewedTrip(configuration: PermittedTripConfiguration) {
        configurationRepository.save(configuration)
    }

    fun getReviewedTrip(plannedTripId: String): PermittedTripConfiguration? {
        return configurationRepository.get(plannedTripId)
    }

    fun getCurrentPlannedTripId(): String? = currentPlannedTripIdRepository.get()

    fun selectCurrentPlannedTrip(plannedTripId: String) {
        currentPlannedTripIdRepository.save(plannedTripId)
    }

    /**
     * Starts Client A's recorder, binds its generated id to [plannedTripId], and starts checking.
     */
    fun startTrip(plannedTripId: String): ClientBTripStartResult {
        require(plannedTripId.isNotBlank()) { "Planned trip identifier must not be blank." }

        return synchronized(lifecycleOperationLock) {
            if (currentBinding != null) {
                return@synchronized ClientBTripStartResult.ClientBRuntimeAlreadyActive
            }

            val configuration = configurationRepository.get(plannedTripId)
                ?: return@synchronized ClientBTripStartResult.MissingPlannedTripConfiguration

            locationPrerequisiteChecker.findFailure()?.let { failure ->
                return@synchronized ClientBTripStartResult.LocationUnavailable(failure)
            }

            val recordingResult = try {
                recordingCoordinator.startRecording()
            } catch (exception: Exception) {
                // Client A may have advanced its in-memory session before a service-start failure.
                runCatching { recordingCoordinator.endRecording() }
                throw exception
            }

            when (recordingResult) {
                is TripCommandResult.Rejected -> {
                    ClientBTripStartResult.RecordingRejected(recordingResult.reason)
                }

                is TripCommandResult.Success -> {
                    startChecking(configuration, recordingResult.session)
                }
            }
        }
    }

    /**
     * Makes breach checking inert first, then ends Client A's active recording. Safe if checking
     * was already inactive; Client A's command result reports whether a recording existed.
     */
    fun endTrip(): TripCommandResult {
        return synchronized(lifecycleOperationLock) {
            val plannedTripId = synchronized(activeStateLock) {
                // Block any callback that has not already entered the handoff critical section.
                val id = activeBinding?.plannedTripId
                activeBinding = null
                id
            }
            locationReader.stop()
            synchronized(activeStateLock) {
                breachMonitor.endTrip()
            }
            val result = recordingCoordinator.endRecording()
            requestCloudCleanup(plannedTripId)
            result
        }
    }

    /** Stops an active recording without retaining its trip data or planned-to-recording binding. */
    fun cancelTrip(): TripCommandResult {
        return synchronized(lifecycleOperationLock) {
            val cancelledBinding = synchronized(activeStateLock) {
                activeBinding.also { activeBinding = null }
            }
            locationReader.stop()
            synchronized(activeStateLock) {
                breachMonitor.endTrip()
            }
            val result = recordingCoordinator.cancelRecording()
            if (result is TripCommandResult.Success && cancelledBinding != null) {
                runCatching { bindingRepository.deleteByPlannedTripId(cancelledBinding.plannedTripId) }
            }
            requestCloudCleanup(cancelledBinding?.plannedTripId)
            result
        }
    }

    private fun requestCloudCleanup(plannedTripId: String?) {
        val tripId = plannedTripId?.takeIf { it.isNotBlank() } ?: return
        runCatching { afterTripCloudCleanup?.invoke(tripId) }
    }

    /** Stops checking/recording and permanently releases the reader executor. */
    override fun close() {
        synchronized(lifecycleOperationLock) {
            synchronized(activeStateLock) {
                activeBinding = null
            }
            locationReader.stop()
            synchronized(activeStateLock) {
                breachMonitor.endTrip()
            }
            recordingCoordinator.endRecording()
            locationReader.close()
        }
    }

    private fun startChecking(
        configuration: PermittedTripConfiguration,
        recordingSession: TripSession
    ): ClientBTripStartResult {
        val binding = TripRecordingBinding(
            plannedTripId = configuration.tripId,
            recordingTripId = recordingSession.tripId
        )
        var previousBinding: TripRecordingBinding? = null
        var previousBindingWasRead = false

        try {
            previousBinding = bindingRepository.getByPlannedTripId(configuration.tripId)
            previousBindingWasRead = true
            bindingRepository.save(binding)
            synchronized(activeStateLock) {
                breachMonitor.startTrip(configuration)
                activeBinding = binding
            }
            locationReader.start(recordingSession.tripId) { observation ->
                handleLocation(binding, observation)
            }
            return ClientBTripStartResult.Started(
                binding = binding,
                recordingSession = recordingSession
            )
        } catch (exception: Exception) {
            // Keep both Client B and Client A inactive if integration setup could not complete.
            synchronized(activeStateLock) {
                activeBinding = null
            }
            locationReader.stop()
            synchronized(activeStateLock) {
                breachMonitor.endTrip()
            }
            recordingCoordinator.endRecording()
            if (previousBindingWasRead) {
                restorePreviousBinding(configuration.tripId, previousBinding)
            }
            throw exception
        }
    }

    private fun handleLocation(
        expectedBinding: TripRecordingBinding,
        observation: ActiveTripLocationObservation
    ) {
        val event = synchronized(activeStateLock) {
            if (activeBinding != expectedBinding) {
                return
            }
            val result = breachMonitor.onLocation(
                currentLocation = observation.point,
                observedAtEpochMillis = observation.recordedAtMillis
            )
            (result as? PermittedAreaMonitoringResult.Outside)?.breachEvent
        }
        if (event != null) {
            // The reader's callback gate remains held until this synchronous handoff returns, so
            // End Trip cannot return while a previously generated event is still being handed off.
            eventSink.onAreaBreach(event)
        }
    }

    private fun restorePreviousBinding(
        plannedTripId: String,
        previousBinding: TripRecordingBinding?
    ) {
        runCatching {
            if (previousBinding == null) {
                bindingRepository.deleteByPlannedTripId(plannedTripId)
            } else {
                bindingRepository.save(previousBinding)
            }
        }
    }
}
