package com.fit5120tp10.nextcross.client.permittedarea.places

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint

/** Text returned by Autocomplete (New); no coordinates are trusted until selection completes. */
data class PlaceSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String
) {
    init {
        require(placeId.isNotBlank()) { "Place ID must not be blank." }
        require(primaryText.isNotBlank()) { "Place suggestion text must not be blank." }
    }
}

/**
 * A trip endpoint resolved either from Place Details (New) or from a one-shot device location.
 * A device location has no Google Place ID, so [placeId] is null in that case.
 */
data class SelectedPlace(
    val displayName: String,
    val placeId: String?,
    val point: GeographicPoint
) {
    init {
        require(displayName.isNotBlank()) { "Selected place display name must not be blank." }
    }

    /** Match the endpoint rule used by route planning, regardless of display text. */
    fun isSameEndpointAs(other: SelectedPlace): Boolean {
        val samePlaceId = !placeId.isNullOrBlank() && placeId == other.placeId
        val samePoint = point.latitude == other.point.latitude &&
            point.longitude == other.point.longitude
        return samePlaceId || samePoint
    }

    companion object {
        fun currentLocation(point: GeographicPoint, address: String): SelectedPlace {
            return SelectedPlace(
                displayName = address.trim(),
                placeId = null,
                point = point
            )
        }
    }
}

sealed class PlacesResult<out T> {
    data class Success<T>(val value: T) : PlacesResult<T>()
    data class Failure(val reason: PlacesFailure) : PlacesResult<Nothing>()
}

/** Failures exposed to the future UI integration without leaking Google SDK model types. */
sealed class PlacesFailure {
    data object MissingApiKey : PlacesFailure()
    data object SessionClosed : PlacesFailure()
    data object SelectionAlreadyStarted : PlacesFailure()
    data object SuggestionIsNotCurrent : PlacesFailure()
    data class IncompletePlaceDetails(val requestedPlaceId: String) : PlacesFailure()
    data class ServiceError(
        val statusCode: Int?,
        val cause: Exception
    ) : PlacesFailure()
}
