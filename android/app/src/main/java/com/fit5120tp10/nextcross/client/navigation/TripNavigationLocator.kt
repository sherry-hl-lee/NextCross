package com.fit5120tp10.nextcross.client.navigation

import android.content.Context
import com.fit5120tp10.nextcross.R

object TripNavigationLocator {
    @Volatile
    private var client: TripNavigationClient? = null

    fun get(context: Context): TripNavigationClient {
        return client ?: synchronized(this) {
            client ?: createClient(context.applicationContext).also { client = it }
        }
    }

    /** Test / debug override. Pass null to recreate from resources on next [get]. */
    fun replaceForTests(replacement: TripNavigationClient?) {
        synchronized(this) {
            client = replacement
        }
    }

    private fun createClient(context: Context): TripNavigationClient {
        val useMock = context.resources.getBoolean(R.bool.trip_navigation_use_mock)
        return if (useMock) {
            MockTripNavigationClient()
        } else {
            NavigationSdkTripNavigationClient()
        }
    }
}
