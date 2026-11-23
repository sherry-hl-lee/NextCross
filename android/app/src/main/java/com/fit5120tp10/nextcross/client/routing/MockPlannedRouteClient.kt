package com.fit5120tp10.nextcross.client.routing

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Offline fixture: straight-line samples between origin and destination.
 * Enough for UI to wire polyline rendering before Directions API is enabled.
 */
class MockPlannedRouteClient(
    private val intermediatePointCount: Int = DEFAULT_INTERMEDIATE_POINTS
) : PlannedRouteClient {
    override fun plan(request: PlannedRouteRequest): PlannedRouteResult {
        val origin = request.origin.point
        val destination = request.destination.point
        if (request.origin.isSameEndpointAs(request.destination)) {
            return PlannedRouteResult.Failed(PlannedRouteFailure.InvalidEndpoints)
        }

        val points = buildList {
            add(origin)
            for (index in 1..intermediatePointCount) {
                val fraction = index.toDouble() / (intermediatePointCount + 1)
                add(interpolate(origin, destination, fraction))
            }
            add(destination)
        }

        val distanceMeters = haversineMeters(origin, destination).toInt()
        val durationSeconds = (distanceMeters / ASSUMED_SPEED_METERS_PER_SECOND).toInt().coerceAtLeast(1)

        return PlannedRouteResult.Success(
            PlannedRoute(
                points = points,
                distanceMeters = distanceMeters,
                durationSeconds = durationSeconds
            )
        )
    }

    private fun interpolate(
        from: GeographicPoint,
        to: GeographicPoint,
        fraction: Double
    ): GeographicPoint {
        return GeographicPoint(
            latitude = from.latitude + (to.latitude - from.latitude) * fraction,
            longitude = from.longitude + (to.longitude - from.longitude) * fraction
        )
    }

    private fun haversineMeters(from: GeographicPoint, to: GeographicPoint): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * Math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadiusMeters * c
    }

    companion object {
        private const val DEFAULT_INTERMEDIATE_POINTS = 6
        private const val ASSUMED_SPEED_METERS_PER_SECOND = 11.1 // ~40 km/h for mock ETA only
    }
}
