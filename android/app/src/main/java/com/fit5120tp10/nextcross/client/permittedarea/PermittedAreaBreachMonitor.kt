package com.fit5120tp10.nextcross.client.permittedarea

import java.util.UUID

sealed class PermittedAreaMonitoringResult {
    /** Location checking is disabled before Start Trip and after End Trip. */
    data object TripInactive : PermittedAreaMonitoringResult()

    /** The active-trip data source has no current location; monitor state is unchanged. */
    data object LocationUnavailable : PermittedAreaMonitoringResult()

    data class Inside(val distanceKilometres: Double) : PermittedAreaMonitoringResult()

    /** [breachEvent] is non-null only for a new inside-to-outside transition. */
    data class Outside(
        val distanceKilometres: Double,
        val breachEvent: AreaBreachEvent?
    ) : PermittedAreaMonitoringResult()
}

private enum class BreachState {
    Inside,
    Outside
}

/**
 * Active-trip breach state machine for one trip at a time.
 *
 * A trip begins in the inside state. Missing observations never change that state. End Trip clears
 * the active configuration and makes all later observations inert until another Start Trip.
 */
class PermittedAreaBreachMonitor(
    private val boundaryChecker: PermittedAreaBoundaryChecker = PermittedAreaBoundaryChecker(),
    private val createEventId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    var activeConfiguration: PermittedTripConfiguration? = null
        private set

    val isActive: Boolean
        get() = activeConfiguration != null

    private var breachState: BreachState = BreachState.Inside

    /** Apply the immutable setup configuration to a newly active trip. */
    @Synchronized
    fun startTrip(configuration: PermittedTripConfiguration) {
        check(activeConfiguration == null) {
            "Cannot start permitted-area monitoring while another trip is active."
        }
        activeConfiguration = configuration
        breachState = BreachState.Inside
    }

    /**
     * Observe a location already supplied by Client A's read-only active-trip interface.
     * This class does not obtain or record GPS data itself.
     */
    @Synchronized
    fun onLocation(currentLocation: GeographicPoint?): PermittedAreaMonitoringResult {
        return onLocation(currentLocation, observedAtEpochMillis = null)
    }

    /** Uses the source GPS sample time when Client A supplies it. */
    @Synchronized
    fun onLocation(
        currentLocation: GeographicPoint?,
        observedAtEpochMillis: Long?
    ): PermittedAreaMonitoringResult {
        val configuration = activeConfiguration
            ?: return PermittedAreaMonitoringResult.TripInactive

        return when (
            val checkResult = boundaryChecker.check(
                permittedArea = configuration.permittedArea,
                currentLocation = currentLocation
            )
        ) {
            PermittedAreaCheckResult.LocationUnavailable -> {
                PermittedAreaMonitoringResult.LocationUnavailable
            }

            is PermittedAreaCheckResult.LocationAvailable -> {
                handleAvailableLocation(configuration, checkResult, observedAtEpochMillis)
            }
        }
    }

    /** Stop checking immediately. Safe to call more than once. */
    @Synchronized
    fun endTrip() {
        activeConfiguration = null
        breachState = BreachState.Inside
    }

    private fun handleAvailableLocation(
        configuration: PermittedTripConfiguration,
        checkResult: PermittedAreaCheckResult.LocationAvailable,
        observedAtEpochMillis: Long?
    ): PermittedAreaMonitoringResult {
        return when (checkResult.position) {
            BoundaryPosition.Inside -> {
                breachState = BreachState.Inside
                PermittedAreaMonitoringResult.Inside(checkResult.distanceKilometres)
            }

            BoundaryPosition.Outside -> {
                val breachEvent = if (breachState == BreachState.Inside) {
                    createBreachEvent(configuration, observedAtEpochMillis)
                } else {
                    null
                }
                breachState = BreachState.Outside
                PermittedAreaMonitoringResult.Outside(
                    distanceKilometres = checkResult.distanceKilometres,
                    breachEvent = breachEvent
                )
            }
        }
    }

    private fun createBreachEvent(
        configuration: PermittedTripConfiguration,
        observedAtEpochMillis: Long?
    ): AreaBreachEvent {
        val eventTimestamp = observedAtEpochMillis ?: currentTimeMillis()
        return AreaBreachEvent(
            tripId = configuration.tripId,
            eventId = createEventId(),
            eventType = AreaBreachEventType.PermittedAreaBreach,
            occurredAtEpochMillis = eventTimestamp,
            parentEmail = configuration.parentContact.email
        )
    }
}
