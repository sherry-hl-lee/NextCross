package com.fit5120tp10.nextcross.client.permittedarea

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Calculates the surface distance between two geographic points. */
fun interface GeographicDistanceCalculator {
    fun distanceKilometres(from: GeographicPoint, to: GeographicPoint): Double
}

/** Great-circle distance using the mean Earth radius defined by IUGG. */
object HaversineDistanceCalculator : GeographicDistanceCalculator {
    private const val MEAN_EARTH_RADIUS_KILOMETRES = 6_371.0088

    override fun distanceKilometres(from: GeographicPoint, to: GeographicPoint): Double {
        val fromLatitudeRadians = Math.toRadians(from.latitude)
        val toLatitudeRadians = Math.toRadians(to.latitude)
        val latitudeDeltaRadians = toLatitudeRadians - fromLatitudeRadians
        val longitudeDeltaRadians = Math.toRadians(to.longitude - from.longitude)

        val latitudeTerm = sin(latitudeDeltaRadians / 2.0)
        val longitudeTerm = sin(longitudeDeltaRadians / 2.0)
        val haversine = latitudeTerm * latitudeTerm +
            cos(fromLatitudeRadians) * cos(toLatitudeRadians) *
            longitudeTerm * longitudeTerm

        // Rounding at antipodal points can otherwise make sqrt(1 - haversine) invalid.
        val boundedHaversine = haversine.coerceIn(0.0, 1.0)
        val angularDistance = 2.0 * asin(sqrt(boundedHaversine))
        return MEAN_EARTH_RADIUS_KILOMETRES * angularDistance
    }
}
