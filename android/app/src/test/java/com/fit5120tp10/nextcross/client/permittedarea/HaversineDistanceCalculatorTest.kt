package com.fit5120tp10.nextcross.client.permittedarea

import org.junit.Assert.assertEquals
import org.junit.Test

class HaversineDistanceCalculatorTest {
    @Test
    fun samePointHasZeroDistance() {
        val point = GeographicPoint(latitude = -37.8136, longitude = 144.9631)

        val distance = HaversineDistanceCalculator.distanceKilometres(point, point)

        assertEquals(0.0, distance, 0.0)
    }

    @Test
    fun oneDegreeOfLongitudeAtEquatorIsAbout111Point195Kilometres() {
        val from = GeographicPoint(latitude = 0.0, longitude = 0.0)
        val to = GeographicPoint(latitude = 0.0, longitude = 1.0)

        val distance = HaversineDistanceCalculator.distanceKilometres(from, to)

        assertEquals(111.195, distance, 0.001)
    }

    @Test
    fun distanceIsSymmetric() {
        val melbourne = GeographicPoint(latitude = -37.8136, longitude = 144.9631)
        val sydney = GeographicPoint(latitude = -33.8688, longitude = 151.2093)

        val outbound = HaversineDistanceCalculator.distanceKilometres(melbourne, sydney)
        val returnDistance = HaversineDistanceCalculator.distanceKilometres(sydney, melbourne)

        assertEquals(outbound, returnDistance, 0.000001)
    }
}
