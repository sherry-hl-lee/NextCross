package com.fit5120tp10.nextcross.client.permittedarea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermittedAreaCalculatorTest {
    @Test
    fun calculatesGeographicMidpointAndBufferedRadiusForOrdinaryEndpoints() {
        val origin = GeographicPoint(latitude = 0.0, longitude = 0.0)
        val destination = GeographicPoint(latitude = 0.0, longitude = 2.0)

        val area = PermittedAreaCalculator.calculate(origin, destination)

        assertEquals(0.0, area.centre.latitude, COORDINATE_TOLERANCE)
        assertEquals(1.0, area.centre.longitude, COORDINATE_TOLERANCE)
        assertEquals(
            111.195 + EXTRA_RADIUS_KILOMETRES,
            area.radiusKilometres,
            DISTANCE_TOLERANCE_KILOMETRES
        )
    }

    @Test
    fun radiusIsPositiveWhenEndpointsAreTheSame() {
        val endpoint = GeographicPoint(latitude = -37.8136, longitude = 144.9631)

        val area = PermittedAreaCalculator.calculate(endpoint, endpoint)

        assertTrue(area.radiusKilometres > 0.0)
        assertEquals(
            EXTRA_RADIUS_KILOMETRES,
            area.radiusKilometres,
            DISTANCE_TOLERANCE_KILOMETRES
        )
    }

    @Test
    fun bothEndpointsAreInsideCalculatedArea() {
        val origin = GeographicPoint(latitude = -37.8136, longitude = 144.9631)
        val destination = GeographicPoint(latitude = -33.8688, longitude = 151.2093)

        val area = PermittedAreaCalculator.calculate(origin, destination)

        val originDistance = HaversineDistanceCalculator.distanceKilometres(
            area.centre,
            origin
        )
        val destinationDistance = HaversineDistanceCalculator.distanceKilometres(
            area.centre,
            destination
        )
        assertTrue(originDistance <= area.radiusKilometres)
        assertTrue(destinationDistance <= area.radiusKilometres)
    }

    @Test
    fun midpointUsesGeographyAcrossTheDateLine() {
        val origin = GeographicPoint(latitude = 10.0, longitude = 179.0)
        val destination = GeographicPoint(latitude = 10.0, longitude = -179.0)

        val area = PermittedAreaCalculator.calculate(origin, destination)

        assertEquals(180.0, area.centre.longitude, COORDINATE_TOLERANCE)
        assertTrue(area.centre.latitude > 10.0)
    }

    private companion object {
        const val EXTRA_RADIUS_KILOMETRES = 3.0
        const val COORDINATE_TOLERANCE = 1e-9
        const val DISTANCE_TOLERANCE_KILOMETRES = 0.001
    }
}
