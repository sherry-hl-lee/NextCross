package com.fit5120tp10.nextcross.client.posttrip

import com.fit5120tp10.nextcross.client.recording.storage.GpsPointEntity
import org.json.JSONArray
import org.json.JSONObject

object SpeedAnalysisRequestBuilder {
    const val MAX_SAMPLES = 20_000

    fun buildJson(
        tripId: String,
        startedAtMillis: Long,
        endedAtMillis: Long,
        samples: List<GpsPointEntity>
    ): String {
        require(tripId.isNotBlank())
        require(samples.isNotEmpty()) { "Speed analysis needs GPS samples." }
        val indexes = selectedIndexes(samples.size)
        val firstTime = samples[indexes.first()].recordedAtMillis
        val lastTime = samples[indexes.last()].recordedAtMillis
        val startedAt = minOf(startedAtMillis, firstTime)
        val endedAt = maxOf(endedAtMillis, lastTime)
        require(endedAt >= startedAt)
        require(endedAt - startedAt <= 86_400_000L) { "Trip longer than 24 hours." }

        val sampleArray = JSONArray()
        for (index in indexes) {
            val point = samples[index]
            val sample = JSONObject()
            sample.put("sampleId", index.toString())
            sample.put("observedAtEpochMillis", point.recordedAtMillis)
            sample.put("latitude", point.latitude)
            sample.put("longitude", point.longitude)
            if (point.speedMetersPerSecond == null) sample.put("speedMps", JSONObject.NULL)
            else sample.put("speedMps", point.speedMetersPerSecond.toDouble())
            if (point.accuracyMeters == null) sample.put("accuracyMeters", JSONObject.NULL)
            else sample.put("accuracyMeters", point.accuracyMeters.toDouble())
            sample.put("bearingDegrees", JSONObject.NULL)
            sampleArray.put(sample)
        }
        return JSONObject()
            .put("tripId", tripId)
            .put("startedAtEpochMillis", startedAt)
            .put("endedAtEpochMillis", endedAt)
            .put("samples", sampleArray)
            .toString()
    }

    fun selectedIndexes(size: Int): List<Int> {
        if (size <= MAX_SAMPLES) return (0 until size).toList()
        if (size <= 0) return emptyList()
        val picked = LinkedHashSet<Int>()
        picked += 0
        picked += size - 1
        val steps = MAX_SAMPLES - 2
        for (i in 1..steps) {
            val index = ((i.toLong() * (size - 1)) / (steps + 1)).toInt()
            picked += index.coerceIn(0, size - 1)
        }
        return picked.sorted()
    }
}
