package com.fit5120tp10.nextcross.client.navigation

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace

/** Destination for turn-by-turn guidance on Active Trip. */
data class TripNavigationDestination(
    val point: GeographicPoint,
    val placeId: String? = null,
    val displayName: String? = null
) {
    init {
        require(displayName == null || displayName.isNotBlank()) {
            "Display name must not be blank when provided."
        }
    }

    companion object {
        fun fromSelectedPlace(place: SelectedPlace): TripNavigationDestination {
            return TripNavigationDestination(
                point = place.point,
                placeId = place.placeId,
                displayName = place.displayName
            )
        }
    }
}

enum class TripNavigationState {
    /** No Activity prepared and no Navigator obtained. */
    Idle,

    /** NavigationApi.getNavigator is in flight. */
    Initializing,

    /** Navigator is available; guidance has not started. */
    Ready,

    /** setDestination route calculation in progress. */
    Routing,

    /** startGuidance is active. */
    Guiding,

    /** stopGuidance was called; Navigator may still be cached. */
    Stopped,

    /** Terminal failure (API key, terms, etc.). Call [TripNavigationClient.release] before retry. */
    Failed
}

sealed class TripNavigationFailure {
    data object NavigatorNotReady : TripNavigationFailure()
    data object InvalidDestination : TripNavigationFailure()
    data class SdkInitFailure(val errorCode: Int) : TripNavigationFailure()
    data class RouteFailure(val routeStatus: String) : TripNavigationFailure()
}

sealed class TripNavigationRouteResult {
    data object GuidanceStarted : TripNavigationRouteResult()
    data class Failed(val reason: TripNavigationFailure) : TripNavigationRouteResult()
}

fun interface TripNavigationListener {
    fun onStateChanged(state: TripNavigationState, failure: TripNavigationFailure?)
}
