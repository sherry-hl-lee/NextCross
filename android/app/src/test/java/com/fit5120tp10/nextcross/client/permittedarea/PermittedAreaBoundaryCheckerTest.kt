package com.fit5120tp10.nextcross.client.permittedarea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class PermittedAreaBoundaryCheckerTest {
    private val centre = GeographicPoint(latitude = -37.8136, longitude = 144.9631)
    private val observedLocation = GeographicPoint(latitude = -37.8, longitude = 145.0)

    @Test
    fun distanceLessThanRadiusIsInside() {
        val checker = checkerReturning(distanceKilometres = 9.999)

        val result = checker.check(area(radiusKilometres = 10.0), observedLocation)
            as PermittedAreaCheckResult.LocationAvailable

        assertEquals(BoundaryPosition.Inside, result.position)
        assertEquals(9.999, result.distanceKilometres, 0.0)
    }

    @Test
    fun distanceEqualToRadiusIsInside() {
        val checker = checkerReturning(distanceKilometres = 10.0)

        val result = checker.check(area(radiusKilometres = 10.0), observedLocation)
            as PermittedAreaCheckResult.LocationAvailable

        assertEquals(BoundaryPosition.Inside, result.position)
    }

    @Test
    fun distanceGreaterThanRadiusIsOutside() {
        val checker = checkerReturning(distanceKilometres = 10.001)

        val result = checker.check(area(radiusKilometres = 10.0), observedLocation)
            as PermittedAreaCheckResult.LocationAvailable

        assertEquals(BoundaryPosition.Outside, result.position)
    }

    @Test
    fun missingLocationDoesNotInvokeDistanceCalculatorOrInferPosition() {
        var calculatorInvoked = false
        val checker = PermittedAreaBoundaryChecker(
            distanceCalculator = GeographicDistanceCalculator { _, _ ->
                calculatorInvoked = true
                100.0
            }
        )

        val result = checker.check(area(radiusKilometres = 10.0), currentLocation = null)

        assertSame(PermittedAreaCheckResult.LocationUnavailable, result)
        assertFalse(calculatorInvoked)
    }

    private fun checkerReturning(distanceKilometres: Double): PermittedAreaBoundaryChecker {
        return PermittedAreaBoundaryChecker(
            distanceCalculator = GeographicDistanceCalculator { _, _ -> distanceKilometres }
        )
    }

    private fun area(radiusKilometres: Double): PermittedArea {
        return PermittedArea(centre = centre, radiusKilometres = radiusKilometres)
    }
}
