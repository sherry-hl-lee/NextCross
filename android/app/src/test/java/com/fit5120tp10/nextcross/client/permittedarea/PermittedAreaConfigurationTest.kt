package com.fit5120tp10.nextcross.client.permittedarea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PermittedAreaConfigurationTest {
    @Test
    fun geographicPointRejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicPoint(latitude = 90.001, longitude = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeographicPoint(latitude = 0.0, longitude = -180.001)
        }
    }

    @Test
    fun permittedAreaRejectsNonPositiveOrNonFiniteRadius() {
        val centre = GeographicPoint(latitude = 0.0, longitude = 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            PermittedArea(centre = centre, radiusKilometres = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermittedArea(centre = centre, radiusKilometres = Double.NaN)
        }
    }

    @Test
    fun plannedTripConfigurationRequiresTripIdentifier() {
        val area = PermittedArea(
            centre = GeographicPoint(latitude = 0.0, longitude = 0.0),
            radiusKilometres = 10.0
        )

        assertThrows(IllegalArgumentException::class.java) {
            PermittedTripConfiguration(
                tripId = " ",
                permittedArea = area,
                parentContact = ParentNotificationContact("parent@example.com")
            )
        }
    }

    @Test
    fun parentNotificationContactRequiresNonBlankEmail() {
        assertThrows(IllegalArgumentException::class.java) {
            ParentNotificationContact(email = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParentNotificationContact(email = " ")
        }
    }

    @Test
    fun plannedTripConfigurationAcceptsEmailOnlyContact() {
        val configuration = PermittedTripConfiguration(
            tripId = "trip-123",
            permittedArea = PermittedArea(
                centre = GeographicPoint(latitude = 0.0, longitude = 0.0),
                radiusKilometres = 10.0
            ),
            parentContact = ParentNotificationContact(email = "parent@example.com")
        )

        assertEquals("parent@example.com", configuration.parentContact.email)
    }
}
