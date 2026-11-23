package com.fit5120tp10.nextcross.client.permittedarea

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Calculates the fixed circular permitted area from a trip's real endpoints. */
object PermittedAreaCalculator {
    /**
     * Returns a circle centred on the great-circle midpoint between [origin] and [destination].
     *
     * The radius reaches both endpoints and then adds the agreed three-kilometre buffer.
     */
    fun calculate(
        origin: GeographicPoint,
        destination: GeographicPoint
    ): PermittedArea {
        val centre = geographicMidpoint(origin, destination)
        val distanceToOrigin = HaversineDistanceCalculator.distanceKilometres(centre, origin)

        return PermittedArea(
            centre = centre,
            radiusKilometres = distanceToOrigin + EXTRA_RADIUS_KILOMETRES
        )
    }

    private fun geographicMidpoint(
        origin: GeographicPoint,
        destination: GeographicPoint
    ): GeographicPoint {
        val originLatitude = Math.toRadians(origin.latitude)
        val originLongitude = Math.toRadians(origin.longitude)
        val destinationLatitude = Math.toRadians(destination.latitude)
        val longitudeDelta = Math.toRadians(destination.longitude - origin.longitude)

        val destinationX = cos(destinationLatitude) * cos(longitudeDelta)
        val destinationY = cos(destinationLatitude) * sin(longitudeDelta)
        val midpointX = cos(originLatitude) + destinationX
        val midpointY = destinationY
        val midpointZ = sin(originLatitude) + sin(destinationLatitude)
        val horizontalVectorLength = sqrt(midpointX * midpointX + midpointY * midpointY)
        val midpointVectorLength = sqrt(
            horizontalVectorLength * horizontalVectorLength + midpointZ * midpointZ
        )

        require(midpointVectorLength > ANTIPODAL_TOLERANCE) {
            "A unique geographic midpoint cannot be calculated for antipodal endpoints."
        }

        val midpointLatitude = atan2(
            midpointZ,
            horizontalVectorLength
        )
        val midpointLongitude = originLongitude + atan2(midpointY, midpointX)

        return GeographicPoint(
            latitude = Math.toDegrees(midpointLatitude),
            longitude = normalizeLongitude(Math.toDegrees(midpointLongitude))
        )
    }

    private fun normalizeLongitude(longitude: Double): Double {
        val normalized = (longitude + HALF_LONGITUDE_RANGE_DEGREES)
            .mod(FULL_LONGITUDE_RANGE_DEGREES) - HALF_LONGITUDE_RANGE_DEGREES
        return if (normalized == -HALF_LONGITUDE_RANGE_DEGREES && longitude > 0.0) {
            HALF_LONGITUDE_RANGE_DEGREES
        } else {
            normalized
        }
    }

    private const val ANTIPODAL_TOLERANCE = 1e-12
    private const val EXTRA_RADIUS_KILOMETRES = 3.0
    private const val HALF_LONGITUDE_RANGE_DEGREES = 180.0
    private const val FULL_LONGITUDE_RANGE_DEGREES = 360.0
}
