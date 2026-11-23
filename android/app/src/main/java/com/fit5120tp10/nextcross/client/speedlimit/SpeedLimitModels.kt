package com.fit5120tp10.nextcross.client.speedlimit

/**
 * Point speed-limit lookup result for Active Trip.
 * [limitKmh] is only present when the backend status is usable for comparison.
 */
data class SpeedLimitContext(
    val limitKmh: Int?,
    val status: SpeedLimitStatus,
    val sourceSpeedZoneId: String? = null,
    val reason: String? = null,
    val dataVersion: String? = null
) {
    /** True when Client may compare recorded speed against [limitKmh]. */
    val isUsableForWarning: Boolean
        get() = limitKmh != null &&
            limitKmh > 0 &&
            status in setOf(SpeedLimitStatus.Static, SpeedLimitStatus.Scheduled)
}

enum class SpeedLimitStatus(val wireValue: String) {
    Static("static"),
    Scheduled("scheduled"),
    Inactive("inactive"),
    Unknown("unknown"),
    DynamicUnknown("dynamic_unknown"),
    Ambiguous("ambiguous"),
    NoMatch("no_match");

    companion object {
        fun fromWireValue(value: String): SpeedLimitStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class SpeedLimitLookupRequest(
    val latitude: Double,
    val longitude: Double,
    /** Offset-aware ISO-8601 instant, e.g. 2026-10-03T05:00:00Z */
    val observedAtIso: String,
    val headingDegrees: Double? = null,
    val radiusMeters: Double = 30.0
)

sealed class SpeedLimitLookupResult {
    data class Success(val context: SpeedLimitContext) : SpeedLimitLookupResult()
    data class HttpFailure(val statusCode: Int) : SpeedLimitLookupResult()
    data class TransportFailure(val cause: Exception) : SpeedLimitLookupResult()
    data class InvalidPayload(val detail: String) : SpeedLimitLookupResult()
}

fun interface SpeedLimitClient {
    fun lookup(request: SpeedLimitLookupRequest): SpeedLimitLookupResult
}

/** Active-trip warning state derived only from usable limits. */
enum class SpeedLimitWarningState {
    Inactive,
    Active
}

/**
 * How far current speed is over a verified limit (excess km/h).
 * Only set when [SpeedLimitWarningState.Active].
 *
 * Bands (excess over limit):
 * - Low: &lt; 10 km/h
 * - Moderate: 10–24 km/h
 * - High: 25–34 km/h
 * - Severe: 35–44 km/h
 * - Extreme: ≥ 45 km/h
 */
enum class SpeedLimitExceedanceSeverity {
    Low,
    Moderate,
    High,
    Severe,
    Extreme
}

/** Full warning assessment for Active Trip UI. */
data class SpeedLimitWarningAssessment(
    val state: SpeedLimitWarningState,
    val severity: SpeedLimitExceedanceSeverity? = null,
    /** Speed minus limit in km/h when overspeeding; null when inactive. */
    val excessKmh: Double? = null
) {
    val isActive: Boolean get() = state == SpeedLimitWarningState.Active
}

object SpeedLimitWarningEvaluator {
    /**
     * Returns [SpeedLimitWarningState.Active] only when a verified limit exists and
     * recorded speed exceeds it. Unverified contexts never warn.
     */
    fun evaluate(
        context: SpeedLimitContext?,
        recordedSpeedMps: Double?
    ): SpeedLimitWarningState = assess(context, recordedSpeedMps).state

    /**
     * Same rules as [evaluate], plus exceedance severity for UI grading.
     */
    fun assess(
        context: SpeedLimitContext?,
        recordedSpeedMps: Double?
    ): SpeedLimitWarningAssessment {
        if (context == null || !context.isUsableForWarning) {
            return SpeedLimitWarningAssessment(SpeedLimitWarningState.Inactive)
        }
        val speedMps = recordedSpeedMps ?: return SpeedLimitWarningAssessment(SpeedLimitWarningState.Inactive)
        if (!speedMps.isFinite() || speedMps < 0.0) {
            return SpeedLimitWarningAssessment(SpeedLimitWarningState.Inactive)
        }
        val limitKmh = context.limitKmh!!.toDouble()
        val speedKmh = speedMps * 3.6
        val excessKmh = speedKmh - limitKmh
        if (excessKmh <= 0.0) {
            return SpeedLimitWarningAssessment(SpeedLimitWarningState.Inactive)
        }
        return SpeedLimitWarningAssessment(
            state = SpeedLimitWarningState.Active,
            severity = severityForExcessKmh(excessKmh),
            excessKmh = excessKmh
        )
    }

    fun severityForExcessKmh(excessKmh: Double): SpeedLimitExceedanceSeverity {
        require(excessKmh.isFinite() && excessKmh > 0.0) {
            "Excess must be a positive finite km/h value."
        }
        return when {
            excessKmh < 10.0 -> SpeedLimitExceedanceSeverity.Low
            excessKmh < 25.0 -> SpeedLimitExceedanceSeverity.Moderate
            excessKmh < 35.0 -> SpeedLimitExceedanceSeverity.High
            excessKmh < 45.0 -> SpeedLimitExceedanceSeverity.Severe
            else -> SpeedLimitExceedanceSeverity.Extreme
        }
    }
}
