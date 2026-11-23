package com.fit5120tp10.nextcross.client.navigation

import android.app.Activity

/**
 * No-op navigation client for builds/emulators without Play Services or before UI wires the map.
 */
class MockTripNavigationClient : TripNavigationClient {
    override var state: TripNavigationState = TripNavigationState.Idle
        private set

    private var listener: TripNavigationListener? = null

    override fun prepare(activity: Activity, listener: TripNavigationListener?) {
        this.listener = listener
        transitionTo(TripNavigationState.Ready)
    }

    override fun startGuidanceTo(
        destination: TripNavigationDestination,
        onResult: (TripNavigationRouteResult) -> Unit
    ) {
        if (state == TripNavigationState.Failed) {
            onResult(
                TripNavigationRouteResult.Failed(TripNavigationFailure.NavigatorNotReady)
            )
            return
        }

        transitionTo(TripNavigationState.Routing)
        transitionTo(TripNavigationState.Guiding)
        onResult(TripNavigationRouteResult.GuidanceStarted)
    }

    override fun stopGuidance() {
        if (state == TripNavigationState.Guiding || state == TripNavigationState.Routing) {
            transitionTo(TripNavigationState.Stopped)
        }
    }

    override fun release() {
        listener = null
        state = TripNavigationState.Idle
    }

    private fun transitionTo(next: TripNavigationState) {
        state = next
        listener?.onStateChanged(next, null)
    }
}
