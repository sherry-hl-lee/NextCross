package com.fit5120tp10.nextcross.client.historicalcrash

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Local fixture client so Client 1 can develop against US4.1 / US4.2 before Backend exposes the API.
 * Hotspots are placed relative to the requested permitted-area centre.
 */
class MockHistoricalCrashContextClient : HistoricalCrashContextClient {
    override fun lookup(request: HistoricalCrashContextRequest): HistoricalCrashContextResult {
        if (request.radiusMeters < EMPTY_RADIUS_THRESHOLD_METERS) {
            return HistoricalCrashContextResult.Success(
                HistoricalCrashContext(
                    status = HistoricalCrashContextStatus.NoRecordedCrashes,
                    timePeriod = request.timePeriod,
                    areaCentre = request.centre,
                    radiusMeters = request.radiusMeters,
                    totals = CrashTotals(crashCount = 0, fatalities = 0, seriousInjuries = 0),
                    clusters = emptyList(),
                    individualPoints = emptyList(),
                    periodStart = DATASET_START,
                    periodEnd = DATASET_END,
                    buildId = MOCK_BUILD_ID,
                    countScope = COUNT_SCOPE
                )
            )
        }

        val clusters = listOf(
            offsetCluster(
                request = request,
                hotspotId = "mock-hotspot-1",
                bearingDegrees = 35.0,
                distanceFraction = 0.35,
                label = "King St / Flinders St",
                crashCount = 8,
                fatalities = 0,
                seriousInjuries = 3
            ),
            offsetCluster(
                request = request,
                hotspotId = "mock-hotspot-2",
                bearingDegrees = 210.0,
                distanceFraction = 0.55,
                label = "Punt Rd corridor",
                crashCount = 5,
                fatalities = 1,
                seriousInjuries = 2
            )
        )
        val individual = listOf(
            offsetIndividual(
                request = request,
                accidentId = "MOCK-ACC-001",
                bearingDegrees = 120.0,
                distanceFraction = 0.25,
                label = "Swanston St",
                fatalities = 0,
                seriousInjuries = 1
            )
        )
        val crashCount = clusters.sumOf { it.crashCount } + individual.sumOf { it.crashCount }

        return HistoricalCrashContextResult.Success(
            HistoricalCrashContext(
                status = HistoricalCrashContextStatus.Recorded,
                timePeriod = request.timePeriod,
                areaCentre = request.centre,
                radiusMeters = request.radiusMeters,
                totals = CrashTotals(
                    crashCount = crashCount,
                    fatalities = clusters.sumOf { it.fatalities } + individual.sumOf { it.fatalities },
                    seriousInjuries = clusters.sumOf { it.seriousInjuries } +
                        individual.sumOf { it.seriousInjuries }
                ),
                clusters = clusters,
                individualPoints = individual,
                periodStart = DATASET_START,
                periodEnd = DATASET_END,
                buildId = MOCK_BUILD_ID,
                countScope = COUNT_SCOPE
            )
        )
    }

    private fun offsetCluster(
        request: HistoricalCrashContextRequest,
        hotspotId: String,
        bearingDegrees: Double,
        distanceFraction: Double,
        label: String,
        crashCount: Int,
        fatalities: Int,
        seriousInjuries: Int
    ): CrashCluster {
        val point = offsetPoint(request.centre, request.radiusMeters * distanceFraction, bearingDegrees)
        return CrashCluster(
            hotspotId = hotspotId,
            latitude = point.latitude,
            longitude = point.longitude,
            locationLabel = label,
            crashCount = crashCount,
            fatalities = fatalities,
            seriousInjuries = seriousInjuries,
            displayPointSource = "cluster_representative"
        )
    }

    private fun offsetIndividual(
        request: HistoricalCrashContextRequest,
        accidentId: String,
        bearingDegrees: Double,
        distanceFraction: Double,
        label: String,
        fatalities: Int,
        seriousInjuries: Int
    ): CrashIndividualPoint {
        val point = offsetPoint(request.centre, request.radiusMeters * distanceFraction, bearingDegrees)
        return CrashIndividualPoint(
            accidentId = accidentId,
            crashDate = "2024-06-15",
            timePeriod = request.timePeriod,
            latitude = point.latitude,
            longitude = point.longitude,
            locationLabel = label,
            fatalities = fatalities,
            seriousInjuries = seriousInjuries
        )
    }

    private fun offsetPoint(
        centre: GeographicPoint,
        distanceMeters: Double,
        bearingDegrees: Double
    ): GeographicPoint {
        val bearing = Math.toRadians(bearingDegrees)
        val northMeters = distanceMeters * cos(bearing)
        val eastMeters = distanceMeters * sin(bearing)
        val latitude = centre.latitude + (northMeters / METRES_PER_DEGREE_LATITUDE)
        val longitude = centre.longitude +
            (eastMeters / (METRES_PER_DEGREE_LATITUDE * cos(Math.toRadians(centre.latitude))))
        return GeographicPoint(latitude = latitude, longitude = longitude)
    }

    companion object {
        private const val DATASET_START = "2021-01-01"
        private const val DATASET_END = "2025-12-31"
        private const val MOCK_BUILD_ID = "mock-iteration2-v2"
        private const val COUNT_SCOPE =
            "Counts are based on actual crash points inside the circle."
        private const val EMPTY_RADIUS_THRESHOLD_METERS = 50.0
        private const val METRES_PER_DEGREE_LATITUDE = 111_320.0
    }
}
