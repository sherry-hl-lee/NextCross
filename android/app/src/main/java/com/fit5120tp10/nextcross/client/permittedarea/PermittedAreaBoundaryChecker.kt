package com.fit5120tp10.nextcross.client.permittedarea

enum class BoundaryPosition {
    Inside,
    Outside
}

sealed class PermittedAreaCheckResult {
    /** No current observation was available, so no boundary conclusion was inferred. */
    data object LocationUnavailable : PermittedAreaCheckResult()

    data class LocationAvailable(
        val position: BoundaryPosition,
        val distanceKilometres: Double
    ) : PermittedAreaCheckResult()
}

/** Performs a stateless check against the fixed circular permitted area. */
class PermittedAreaBoundaryChecker(
    private val distanceCalculator: GeographicDistanceCalculator = HaversineDistanceCalculator
) {
    fun check(
        permittedArea: PermittedArea,
        currentLocation: GeographicPoint?
    ): PermittedAreaCheckResult {
        if (currentLocation == null) {
            return PermittedAreaCheckResult.LocationUnavailable
        }

        val distanceKilometres = distanceCalculator.distanceKilometres(
            from = permittedArea.centre,
            to = currentLocation
        )
        check(distanceKilometres.isFinite() && distanceKilometres >= 0.0) {
            "Distance calculator must return a non-negative, finite distance."
        }

        val position = if (distanceKilometres <= permittedArea.radiusKilometres) {
            BoundaryPosition.Inside
        } else {
            BoundaryPosition.Outside
        }

        return PermittedAreaCheckResult.LocationAvailable(
            position = position,
            distanceKilometres = distanceKilometres
        )
    }
}
