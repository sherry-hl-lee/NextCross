package com.fit5120tp10.nextcross.client.permittedarea.places

import com.fit5120tp10.nextcross.client.permittedarea.ParentNotificationContact
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea
import com.fit5120tp10.nextcross.client.permittedarea.PermittedAreaCalculator
import com.fit5120tp10.nextcross.client.permittedarea.PermittedTripConfiguration

enum class TripEndpoint {
    ORIGIN,
    DESTINATION
}

sealed class TripPlaceSelectionFailure {
    data object MissingOrigin : TripPlaceSelectionFailure()
    data object MissingDestination : TripPlaceSelectionFailure()
}

sealed class TripPlaceSelectionResult<out T> {
    data class Ready<T>(val value: T) : TripPlaceSelectionResult<T>()
    data class NotReady(val reason: TripPlaceSelectionFailure) :
        TripPlaceSelectionResult<Nothing>()
}

/**
 * Immutable setup state. Clear an endpoint as soon as its text is edited so stale coordinates
 * cannot be used to create or save a [PermittedTripConfiguration].
 */
data class TripPlaceSelection(
    val origin: SelectedPlace? = null,
    val destination: SelectedPlace? = null
) {
    fun select(endpoint: TripEndpoint, place: SelectedPlace): TripPlaceSelection {
        return when (endpoint) {
            TripEndpoint.ORIGIN -> copy(origin = place)
            TripEndpoint.DESTINATION -> copy(destination = place)
        }
    }

    fun clear(endpoint: TripEndpoint): TripPlaceSelection {
        return when (endpoint) {
            TripEndpoint.ORIGIN -> copy(origin = null)
            TripEndpoint.DESTINATION -> copy(destination = null)
        }
    }

    fun calculatePermittedArea(): TripPlaceSelectionResult<PermittedArea> {
        val selectedOrigin = origin
            ?: return TripPlaceSelectionResult.NotReady(TripPlaceSelectionFailure.MissingOrigin)
        val selectedDestination = destination
            ?: return TripPlaceSelectionResult.NotReady(
                TripPlaceSelectionFailure.MissingDestination
            )
        return TripPlaceSelectionResult.Ready(
            PermittedAreaCalculator.calculate(
                origin = selectedOrigin.point,
                destination = selectedDestination.point
            )
        )
    }

    /** Returns no configuration until both Places selections contain real coordinates. */
    fun createConfiguration(
        tripId: String,
        parentContact: ParentNotificationContact
    ): TripPlaceSelectionResult<PermittedTripConfiguration> {
        return when (val areaResult = calculatePermittedArea()) {
            is TripPlaceSelectionResult.Ready -> TripPlaceSelectionResult.Ready(
                PermittedTripConfiguration(
                    tripId = tripId,
                    permittedArea = areaResult.value,
                    parentContact = parentContact
                )
            )
            is TripPlaceSelectionResult.NotReady -> areaResult
        }
    }
}
