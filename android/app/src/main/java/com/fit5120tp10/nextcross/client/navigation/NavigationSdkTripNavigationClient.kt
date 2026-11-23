package com.fit5120tp10.nextcross.client.navigation

import android.app.Activity
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
import java.lang.ref.WeakReference

/**
 * Real Navigation SDK adapter. Requires an [Activity] that hosts SupportNavigationFragment (UI Phase 5).
 */
class NavigationSdkTripNavigationClient : TripNavigationClient {
    override var state: TripNavigationState = TripNavigationState.Idle
        private set

    private var activityRef: WeakReference<Activity>? = null
    private var listener: TripNavigationListener? = null
    private var navigator: Navigator? = null

    private var pendingStart: PendingStart? = null

    override fun prepare(activity: Activity, listener: TripNavigationListener?) {
        this.listener = listener
        activityRef = WeakReference(activity)

        if (navigator != null) {
            transitionTo(TripNavigationState.Ready)
            return
        }
        if (state == TripNavigationState.Initializing) {
            return
        }

        transitionTo(TripNavigationState.Initializing)
        NavigationApi.getNavigator(
            activity,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    onNavigatorReadyInternal(navigator)
                }

                override fun onError(errorCode: Int) {
                    transitionTo(
                        TripNavigationState.Failed,
                        TripNavigationFailure.SdkInitFailure(errorCode)
                    )
                    pendingStart?.let { pending ->
                        pendingStart = null
                        pending.onResult(
                            TripNavigationRouteResult.Failed(
                                TripNavigationFailure.SdkInitFailure(errorCode)
                            )
                        )
                    }
                }
            }
        )
    }

    override fun startGuidanceTo(
        destination: TripNavigationDestination,
        onResult: (TripNavigationRouteResult) -> Unit
    ) {
        val activeNavigator = navigator
        if (activeNavigator == null) {
            if (state == TripNavigationState.Initializing) {
                pendingStart = PendingStart(destination, onResult)
                return
            }
            onResult(
                TripNavigationRouteResult.Failed(TripNavigationFailure.NavigatorNotReady)
            )
            return
        }

        val waypoint = buildWaypoint(destination)
        if (waypoint == null) {
            onResult(
                TripNavigationRouteResult.Failed(TripNavigationFailure.InvalidDestination)
            )
            return
        }

        transitionTo(TripNavigationState.Routing)
        val pendingRoute = activeNavigator.setDestination(waypoint)
        pendingRoute.setOnResultListener { routeStatus ->
            when (routeStatus) {
                Navigator.RouteStatus.OK -> {
                    activeNavigator.startGuidance()
                    transitionTo(TripNavigationState.Guiding)
                    onResult(TripNavigationRouteResult.GuidanceStarted)
                }
                else -> {
                    transitionTo(
                        TripNavigationState.Ready,
                        TripNavigationFailure.RouteFailure(routeStatus.name)
                    )
                    onResult(
                        TripNavigationRouteResult.Failed(
                            TripNavigationFailure.RouteFailure(routeStatus.name)
                        )
                    )
                }
            }
        }
    }

    override fun stopGuidance() {
        navigator?.stopGuidance()
        if (state == TripNavigationState.Guiding || state == TripNavigationState.Routing) {
            transitionTo(TripNavigationState.Stopped)
        }
    }

    override fun release() {
        stopGuidance()
        navigator?.cleanup()
        navigator = null
        activityRef = null
        listener = null
        pendingStart = null
        state = TripNavigationState.Idle
    }

    private fun onNavigatorReadyInternal(readyNavigator: Navigator) {
        navigator = readyNavigator
        transitionTo(TripNavigationState.Ready)

        val pending = pendingStart ?: return
        pendingStart = null
        startGuidanceTo(pending.destination, pending.onResult)
    }

    private fun buildWaypoint(destination: TripNavigationDestination): Waypoint? {
        val placeId = destination.placeId?.trim().orEmpty()
        if (placeId.isNotEmpty()) {
            try {
                val builder = Waypoint.builder().setPlaceIdString(placeId)
                destination.displayName?.let { builder.setTitle(it) }
                return builder.build()
            } catch (_: Waypoint.UnsupportedPlaceIdException) {
                // Fall back to coordinates when Places ID is not routable.
            }
        }

        return try {
            val builder = Waypoint.builder()
                .setLatLng(destination.point.latitude, destination.point.longitude)
            destination.displayName?.let { builder.setTitle(it) }
            builder.build()
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun transitionTo(
        next: TripNavigationState,
        failure: TripNavigationFailure? = null
    ) {
        state = next
        listener?.onStateChanged(next, failure)
    }

    private data class PendingStart(
        val destination: TripNavigationDestination,
        val onResult: (TripNavigationRouteResult) -> Unit
    )
}
