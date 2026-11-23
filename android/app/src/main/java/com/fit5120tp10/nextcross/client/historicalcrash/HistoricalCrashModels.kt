package com.fit5120tp10.nextcross.client.historicalcrash

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.PermittedArea

/**
 * Fixed seasonal time bands required by the iteration2_v2 crash lookup contract.
 * The API does not accept a free-form timestamp for crash aggregation.
 */
enum class CrashTimePeriod(val wireValue: String) {
    Morning("morning"),
    Daytime("daytime"),
    Night("night"),
    LateNight("late_night");

    companion object {
        fun fromWireValue(value: String): CrashTimePeriod? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

/** Query inputs for historical young-driver injury-crash context. */
data class HistoricalCrashContextRequest(
    val centre: GeographicPoint,
    val radiusMeters: Double,
    val timePeriod: CrashTimePeriod,
    val startDate: String? = null,
    val endDate: String? = null
) {
    init {
        require(radiusMeters.isFinite() && radiusMeters > 0.0) {
            "Query radius must be a positive, finite number of metres."
        }
        require(radiusMeters <= MAX_RADIUS_METERS) {
            "Query radius must be at most $MAX_RADIUS_METERS metres."
        }
    }

    companion object {
        const val MAX_RADIUS_METERS = 50_000.0

        /** Selected area for US4.1 is the existing permitted-area circle. */
        fun fromPermittedArea(
            permittedArea: PermittedArea,
            timePeriod: CrashTimePeriod,
            startDate: String? = null,
            endDate: String? = null
        ): HistoricalCrashContextRequest {
            return HistoricalCrashContextRequest(
                centre = permittedArea.centre,
                radiusMeters = permittedArea.radiusKilometres * METRES_PER_KILOMETRE,
                timePeriod = timePeriod,
                startDate = startDate,
                endDate = endDate
            )
        }

        private const val METRES_PER_KILOMETRE = 1_000.0
    }
}

enum class HistoricalCrashContextStatus(val wireValue: String) {
    Recorded("recorded"),
    NoRecordedCrashes("no_recorded_crashes"),
    OutsideDatasetPeriod("outside_dataset_period"),
    NoDataset("no_dataset");

    companion object {
        fun fromWireValue(value: String): HistoricalCrashContextStatus? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

data class CrashTotals(
    val crashCount: Int,
    val fatalities: Int,
    val seriousInjuries: Int
)

data class CrashCluster(
    val hotspotId: String,
    val latitude: Double,
    val longitude: Double,
    val locationLabel: String,
    val crashCount: Int,
    val fatalities: Int,
    val seriousInjuries: Int,
    val displayPointSource: String? = null
)

data class CrashIndividualPoint(
    val accidentId: String,
    val crashDate: String?,
    val timePeriod: CrashTimePeriod?,
    val latitude: Double,
    val longitude: Double,
    val locationLabel: String,
    val crashCount: Int = 1,
    val fatalities: Int,
    val seriousInjuries: Int
)

/**
 * Historical context only — never a prediction or live-tracking feed.
 * [dataType] is always [HISTORICAL_DATA_TYPE] for UI disclaimer wiring (AC4.2.4).
 */
data class HistoricalCrashContext(
    val status: HistoricalCrashContextStatus,
    val timePeriod: CrashTimePeriod,
    val areaCentre: GeographicPoint,
    val radiusMeters: Double,
    val totals: CrashTotals?,
    val clusters: List<CrashCluster>,
    val individualPoints: List<CrashIndividualPoint>,
    val periodStart: String?,
    val periodEnd: String?,
    val buildId: String?,
    val countScope: String?,
    val dataType: String = HISTORICAL_DATA_TYPE
) {
    val available: Boolean
        get() = status == HistoricalCrashContextStatus.Recorded &&
            ((totals?.crashCount ?: 0) > 0 || clusters.isNotEmpty() || individualPoints.isNotEmpty())

    companion object {
        const val HISTORICAL_DATA_TYPE = "historical"
    }
}

sealed class HistoricalCrashContextResult {
    data class Success(val value: HistoricalCrashContext) : HistoricalCrashContextResult()
    data class HttpFailure(val statusCode: Int) : HistoricalCrashContextResult()
    data class TransportFailure(val cause: Exception) : HistoricalCrashContextResult()
    data class InvalidPayload(val detail: String) : HistoricalCrashContextResult()
}
