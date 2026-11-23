package com.fit5120tp10.nextcross.client.permittedarea.places

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.HaversineDistanceCalculator
import com.fit5120tp10.nextcross.client.permittedarea.ParentNotificationContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlaceSelectionTest {
    @Test
    fun currentLocationUsesResolvedAddressAsItsSharedDisplayName() {
        val point = GeographicPoint(latitude = -37.913, longitude = 145.134)

        val origin = SelectedPlace.currentLocation(point, "  10 Example St, Clayton VIC 3168  ")

        assertEquals("10 Example St, Clayton VIC 3168", origin.displayName)
        assertEquals(point, origin.point)
        assertEquals(null, origin.placeId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun currentLocationCannotBeSelectedWithoutAnAddress() {
        SelectedPlace.currentLocation(
            GeographicPoint(latitude = -37.913, longitude = 145.134),
            "  "
        )
    }

    @Test
    fun configurationIsNotCreatedWithoutBothRealPlaceSelections() {
        val onlyOrigin = TripPlaceSelection().select(TripEndpoint.ORIGIN, melbourne)

        val result = onlyOrigin.createConfiguration(
            tripId = "trip-1",
            parentContact = parentContact
        )

        assertEquals(
            TripPlaceSelectionResult.NotReady(TripPlaceSelectionFailure.MissingDestination),
            result
        )
    }

    @Test
    fun twoSelectedCoordinatesProduceCalculatedPermittedAreaAndConfiguration() {
        val selections = TripPlaceSelection()
            .select(TripEndpoint.ORIGIN, melbourne)
            .select(TripEndpoint.DESTINATION, sydney)

        val result = selections.createConfiguration(
            tripId = "trip-1",
            parentContact = parentContact
        ) as TripPlaceSelectionResult.Ready
        val configuration = result.value
        val area = configuration.permittedArea

        assertEquals("trip-1", configuration.tripId)
        assertEquals(parentContact, configuration.parentContact)
        assertTrue(
            HaversineDistanceCalculator.distanceKilometres(area.centre, melbourne.point) <=
                area.radiusKilometres
        )
        assertTrue(
            HaversineDistanceCalculator.distanceKilometres(area.centre, sydney.point) <=
                area.radiusKilometres
        )
    }

    @Test
    fun editingSelectedTextCanClearCoordinatesAndPreventConfiguration() {
        val selections = TripPlaceSelection(origin = melbourne, destination = sydney)
            .clear(TripEndpoint.ORIGIN)

        val result = selections.calculatePermittedArea()

        assertEquals(
            TripPlaceSelectionResult.NotReady(TripPlaceSelectionFailure.MissingOrigin),
            result
        )
    }

    private companion object {
        val melbourne = SelectedPlace(
            displayName = "Melbourne",
            placeId = "melbourne-place-id",
            point = GeographicPoint(latitude = -37.8136, longitude = 144.9631)
        )
        val sydney = SelectedPlace(
            displayName = "Sydney",
            placeId = "sydney-place-id",
            point = GeographicPoint(latitude = -33.8688, longitude = 151.2093)
        )
        val parentContact = ParentNotificationContact(
            email = "parent@example.com"
        )
    }
}
