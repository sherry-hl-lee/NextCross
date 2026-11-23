package com.fit5120tp10.nextcross.client.posttrip

sealed interface SpeedCheckState {
    data object Checking : SpeedCheckState
    data object StillChecking : SpeedCheckState
    data class Done(val status: String, val checkedSharePercent: Int? = null) : SpeedCheckState
    data class Failed(val reason: String) : SpeedCheckState
}

data class SpeedAnalysisBackendEvent(
    val eventId: String,
    val startSampleId: String,
    val endSampleId: String,
    val durationSeconds: Double,
    val referenceLimitKmh: Int,
    val peakSpeedKmh: Double,
    val averageExceedanceKmh: Double,
    val limitStatus: String,
    val dataQuality: String,
    val detectorVersion: String
)

data class SpeedAnalysisResult(
    val analysisStatus: String,
    val events: List<SpeedAnalysisBackendEvent>,
    val matchedTimelinePoints: Int = 0,
    val timelinePoints: Int = 0
) {
    val checkedSharePercent: Int?
        get() = if (timelinePoints <= 0) null
        else ((matchedTimelinePoints * 100.0) / timelinePoints).toInt().coerceIn(0, 100)
}

sealed interface SpeedAnalysisClientResult {
    data class Success(val result: SpeedAnalysisResult) : SpeedAnalysisClientResult
    data class Failed(val reason: String) : SpeedAnalysisClientResult
}

fun interface SpeedAnalysisClient {
    fun analyze(tripId: String, requestJson: String): SpeedAnalysisClientResult
}
