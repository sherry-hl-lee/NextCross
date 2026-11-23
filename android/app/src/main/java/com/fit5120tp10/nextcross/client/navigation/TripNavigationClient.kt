package com.fit5120tp10.nextcross.client.navigation

import android.app.Activity

/**
 * Thin facade over Google Navigation SDK for Active Trip.
 *
 * UI owner hosts [com.google.android.libraries.navigation.SupportNavigationFragment]
 * and calls [prepare] from an [Activity] context before [startGuidanceTo].
 */
interface TripNavigationClient {
    val state: TripNavigationState

    /**
     * Obtains (or reuses) a [com.google.android.libraries.navigation.Navigator] for [activity].
     * Safe to call once when Active Trip screen becomes visible.
     */
    fun prepare(activity: Activity, listener: TripNavigationListener? = null)

    /**
     * Sets destination and starts turn-by-turn guidance when routing succeeds.
     * If [prepare] has not finished, the request is queued until the Navigator is ready.
     */
    fun startGuidanceTo(
        destination: TripNavigationDestination,
        onResult: (TripNavigationRouteResult) -> Unit
    )

    /** Stops guidance but keeps the Navigator cached for a subsequent trip in the same session. */
    fun stopGuidance()

    /** Stops guidance and releases Navigator resources. Call when leaving Active Trip. */
    fun release()
}
