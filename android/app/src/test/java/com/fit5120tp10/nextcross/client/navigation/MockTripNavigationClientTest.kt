package com.fit5120tp10.nextcross.client.navigation

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTripNavigationClientTest {
    @Test
    fun startGuidance_reachesGuidingState() {
        val client = MockTripNavigationClient()

        var result: TripNavigationRouteResult? = null
        client.startGuidanceTo(
            TripNavigationDestination(
                point = GeographicPoint(-37.8136, 144.9631),
                displayName = "Melbourne CBD"
            )
        ) { result = it }

        assertEquals(TripNavigationState.Guiding, client.state)
        assertTrue(result is TripNavigationRouteResult.GuidanceStarted)
    }

    @Test
    fun stopGuidance_leavesStoppedState() {
        val client = MockTripNavigationClient()
        client.startGuidanceTo(
            TripNavigationDestination(GeographicPoint(-37.8136, 144.9631))
        ) {}

        client.stopGuidance()

        assertEquals(TripNavigationState.Stopped, client.state)
    }
}
