package com.fit5120tp10.nextcross.client.routing

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace

/**
 * Static planned driving route between Trip Setup origin and destination.
 * Independent from [com.fit5120tp10.nextcross.client.navigation.TripNavigationClient]:
 * does not use device GPS and does not start turn-by-turn guidance.
 */
data class PlannedRoute(
    val points: List<GeographicPoint>,
    val distanceMeters: Int? = null,
    val durationSeconds: Int? = null
) {
    init {
        require(points.size >= 2) {
            "Planned route must contain at least two ordered coordinates."
        }
    }
}

data class PlannedRouteRequest(
    val origin: SelectedPlace,
    val destination: SelectedPlace
)

sealed class PlannedRouteResult {
    data class Success(val route: PlannedRoute) : PlannedRouteResult()
    data class Failed(val reason: PlannedRouteFailure) : PlannedRouteResult()
}

sealed class PlannedRouteFailure {
    data object InvalidEndpoints : PlannedRouteFailure()
    data object MissingApiKey : PlannedRouteFailure()
    data object NoRouteFound : PlannedRouteFailure()
    data class ApiFailure(val status: String, val detail: String? = null) : PlannedRouteFailure()
    data class TransportFailure(val cause: Exception) : PlannedRouteFailure()
}

fun interface PlannedRouteClient {
    /**
     * Resolves a static route from [request].origin to [request].destination.
     * Intended to be called off the main thread.
     */
    fun plan(request: PlannedRouteRequest): PlannedRouteResult
}
