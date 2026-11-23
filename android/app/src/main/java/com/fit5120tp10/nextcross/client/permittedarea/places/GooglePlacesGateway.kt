package com.fit5120tp10.nextcross.client.permittedarea.places

import android.util.Log
import com.fit5120tp10.nextcross.BuildConfig
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient

internal class GooglePlacesSessionGatewayFactory(
    private val placesClient: PlacesClient
) : PlacesSessionGatewayFactory {
    override fun create(): PlacesSessionGateway {
        return GooglePlacesSessionGateway(
            placesClient = placesClient,
            sessionToken = AutocompleteSessionToken.newInstance()
        )
    }
}

private class GooglePlacesSessionGateway(
    private val placesClient: PlacesClient,
    private val sessionToken: AutocompleteSessionToken
) : PlacesSessionGateway {
    private val operationLock = Any()
    private val cancellationSources = mutableSetOf<CancellationTokenSource>()
    private var isClosed = false

    override fun findSuggestions(
        query: String,
        onResult: (PlacesResult<List<PlaceSuggestion>>) -> Unit
    ): PendingOperation {
        val cancellationSource = registerOperation()
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(sessionToken)
            .setCancellationToken(cancellationSource.token)
            .setCountries(listOf(AUSTRALIA_COUNTRY_CODE))
            .setLocationRestriction(GREATER_MELBOURNE_VIEWPORT)
            .build()

        try {
            placesClient.findAutocompletePredictions(request)
                .addOnSuccessListener { response ->
                    completeOperation(cancellationSource) {
                        val result = try {
                            PlacesResult.Success(
                                response.autocompletePredictions
                                    .take(ClientBPlacesAutocomplete.MAXIMUM_SUGGESTIONS)
                                    .map { prediction ->
                                        PlaceSuggestion(
                                            placeId = prediction.placeId,
                                            primaryText = prediction.getPrimaryText(null).toString(),
                                            secondaryText = prediction.getSecondaryText(null).toString()
                                        )
                                    }
                            )
                        } catch (exception: Exception) {
                            serviceFailure(exception)
                        }
                        onResult(result)
                    }
                }
                .addOnFailureListener { exception ->
                    completeOperation(cancellationSource) {
                        onResult(serviceFailure(exception))
                    }
                }
        } catch (exception: Exception) {
            unregisterOperation(cancellationSource)
            throw exception
        }

        return cancellationOperation(cancellationSource)
    }

    override fun fetchSelectedPlace(
        placeId: String,
        onResult: (PlacesResult<SelectedPlace>) -> Unit
    ): PendingOperation {
        val cancellationSource = registerOperation()
        val request = FetchPlaceRequest.builder(placeId, REQUIRED_SELECTED_PLACE_FIELDS)
            .setSessionToken(sessionToken)
            .setCancellationToken(cancellationSource.token)
            .build()

        try {
            placesClient.fetchPlace(request)
                .addOnSuccessListener { response ->
                    completeOperation(cancellationSource) {
                        val result = try {
                            mapSelectedPlace(placeId, response.place)
                        } catch (exception: Exception) {
                            serviceFailure(exception)
                        }
                        onResult(result)
                    }
                }
                .addOnFailureListener { exception ->
                    completeOperation(cancellationSource) {
                        onResult(serviceFailure(exception))
                    }
                }
        } catch (exception: Exception) {
            unregisterOperation(cancellationSource)
            throw exception
        }

        return cancellationOperation(cancellationSource)
    }

    override fun close() {
        val sources = synchronized(operationLock) {
            if (isClosed) return
            isClosed = true
            cancellationSources.toList().also { cancellationSources.clear() }
        }
        sources.forEach(CancellationTokenSource::cancel)
    }

    private fun registerOperation(): CancellationTokenSource {
        val source = CancellationTokenSource()
        synchronized(operationLock) {
            check(!isClosed) { "This Places session has already ended." }
            cancellationSources += source
        }
        return source
    }

    private fun unregisterOperation(source: CancellationTokenSource) {
        synchronized(operationLock) {
            cancellationSources -= source
        }
    }

    private fun completeOperation(
        source: CancellationTokenSource,
        completion: () -> Unit
    ) {
        val shouldComplete = synchronized(operationLock) {
            cancellationSources.remove(source) && !isClosed
        }
        if (shouldComplete) completion()
    }

    private fun cancellationOperation(source: CancellationTokenSource): PendingOperation {
        return PendingOperation {
            val shouldCancel = synchronized(operationLock) {
                cancellationSources.remove(source)
            }
            if (shouldCancel) source.cancel()
        }
    }

    private fun mapSelectedPlace(
        requestedPlaceId: String,
        place: Place
    ): PlacesResult<SelectedPlace> {
        val displayName = place.displayName
        val returnedPlaceId = place.id
        val location = place.location
        if (
            displayName.isNullOrBlank() || returnedPlaceId.isNullOrBlank() || location == null
        ) {
            if (BuildConfig.DEBUG) {
                Log.w(
                    DEBUG_ERROR_LOG_TAG,
                    "Place details were incomplete; requestedPlaceId=$requestedPlaceId, " +
                        "returnedPlaceId=$returnedPlaceId, displayName=$displayName, " +
                        "hasLocation=${location != null}"
                )
            }
            return PlacesResult.Failure(
                PlacesFailure.IncompletePlaceDetails(requestedPlaceId)
            )
        }

        return PlacesResult.Success(
            SelectedPlace(
                displayName = displayName,
                placeId = returnedPlaceId,
                point = GeographicPoint(
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            )
        )
    }

    private fun <T> serviceFailure(exception: Exception): PlacesResult<T> {
        val statusCode = (exception as? ApiException)?.statusCode
        if (BuildConfig.DEBUG) {
            Log.e(
                DEBUG_ERROR_LOG_TAG,
                "Google Places request failed; statusCode=$statusCode, message=${exception.message}",
                exception
            )
        }
        return PlacesResult.Failure(
            PlacesFailure.ServiceError(
                statusCode = statusCode,
                cause = exception
            )
        )
    }
}

private const val AUSTRALIA_COUNTRY_CODE = "AU"
private const val DEBUG_ERROR_LOG_TAG = "ClientBPlacesError"

/**
 * Greater Melbourne search area. This is intentionally a practical rectangular approximation
 * because the Places SDK location restriction does not accept a
 * metropolitan administrative-boundary polygon.
 */
private val GREATER_MELBOURNE_VIEWPORT = RectangularBounds.newInstance(
    LatLng(-38.50, 144.40),
    LatLng(-37.25, 145.75)
)

internal val REQUIRED_SELECTED_PLACE_FIELDS = listOf(
    Place.Field.DISPLAY_NAME,
    Place.Field.ID,
    Place.Field.LOCATION
)
