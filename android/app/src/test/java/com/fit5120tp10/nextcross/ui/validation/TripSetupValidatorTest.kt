package com.fit5120tp10.nextcross.ui.validation

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSetupValidatorTest {
    @Test
    fun samePlaceIdOrCoordinatesAreTheSameEndpoint() {
        val origin = SelectedPlace("Campus", "place-1", GeographicPoint(-37.9, 145.1))
        val sameId = SelectedPlace("Campus entrance", "place-1", GeographicPoint(-37.9001, 145.1001))
        val samePoint = SelectedPlace("Current location", null, origin.point)
        val different = SelectedPlace("City", "place-2", GeographicPoint(-37.8, 144.9))

        assertTrue(origin.isSameEndpointAs(sameId))
        assertTrue(origin.isSameEndpointAs(samePoint))
        assertFalse(origin.isSameEndpointAs(different))
    }

    @Test
    fun sameEndpointsBlockReviewWithDestinationMessage() {
        val errors = validateTripSetup(
            startingPoint = "Campus",
            startingPointSelected = true,
            destination = "Campus",
            destinationSelected = true,
            sameEndpoints = true,
            email = "parent@example.com"
        )

        assertEquals("Choose a different destination.", errors.destination)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun differentEndpointsAndNormalEmailCanProceed() {
        val errors = validateTripSetup(
            startingPoint = "Campus",
            startingPointSelected = true,
            destination = "City",
            destinationSelected = true,
            sameEndpoints = false,
            email = "parent@example.com"
        )

        assertFalse(errors.hasErrors)
    }

    @Test
    fun unresolvedCurrentLocationCannotProceedToReview() {
        val errors = validateTripSetup(
            startingPoint = "Current location",
            startingPointSelected = false,
            destination = "City",
            destinationSelected = true,
            sameEndpoints = false,
            email = "parent@example.com"
        )

        assertEquals("Select a starting point suggestion.", errors.startingPoint)
    }

    @Test
    fun setupRejectsEmailThatSenderWouldReject() {
        assertFalse(isValidEmailAddress("a@b.c"))
    }
}
