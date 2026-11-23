package com.fit5120tp10.nextcross.client.routing

import android.content.Context
import com.fit5120tp10.nextcross.BuildConfig

/**
 * Process-local facade for static planned routes (Trip Setup origin → destination).
 * Always uses Google Directions API with [BuildConfig.MAPS_API_KEY]
 * (falls back to [BuildConfig.PLACES_API_KEY]).
 *
 * Independent from Active Trip [com.fit5120tp10.nextcross.client.navigation.TripNavigationClient].
 */
object PlannedRouteLocator {
    @Volatile
    private var client: PlannedRouteClient? = null

    fun get(@Suppress("UNUSED_PARAMETER") context: Context): PlannedRouteClient {
        return client ?: synchronized(this) {
            client ?: DirectionsApiPlannedRouteClient(apiKey = resolveApiKey()).also { client = it }
        }
    }

    fun replaceForTests(replacement: PlannedRouteClient?) {
        synchronized(this) {
            client = replacement
        }
    }

    private fun resolveApiKey(): String {
        val mapsKey = BuildConfig.MAPS_API_KEY.trim()
        if (mapsKey.isNotEmpty() && mapsKey != "DEFAULT_API_KEY") {
            return mapsKey
        }
        return BuildConfig.PLACES_API_KEY.trim()
    }
}
