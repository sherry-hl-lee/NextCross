package com.fit5120tp10.nextcross.client.posttrip

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

class SpeedAnalysisHttpClient(
    tripsBaseUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : SpeedAnalysisClient {
    private val base = tripsBaseUrl.trim().trimEnd('/')

    init {
        val probe = URL(base)
        require(probe.protocol == "http" || probe.protocol == "https")
    }

    override fun analyze(tripId: String, requestJson: String): SpeedAnalysisClientResult {
        require(tripId.isNotBlank())
        var connection: HttpURLConnection? = null
        return try {
            val body = requestJson.toByteArray(StandardCharsets.UTF_8)
            connection = connectionFactory(URL("$base/$tripId/speed-analysis"))
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val statusCode = connection.responseCode
            val responseBody = readBody(connection, statusCode)
            if (statusCode !in 200..299) {
                return SpeedAnalysisClientResult.Failed("HTTP $statusCode")
            }
            SpeedAnalysisClientResult.Success(parse(responseBody))
        } catch (exception: Exception) {
            SpeedAnalysisClientResult.Failed(exception.message ?: "unavailable")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String): SpeedAnalysisResult {
        val root = JSONObject(body)
        val eventsJson = root.optJSONArray("events")
        val events = buildList {
            if (eventsJson != null) {
                for (i in 0 until eventsJson.length()) {
                    val item = eventsJson.optJSONObject(i) ?: continue
                    if (item.optString("type") != "SPEED_LIMIT_EXCEEDANCE") continue
                    add(
                        SpeedAnalysisBackendEvent(
                            eventId = item.getString("eventId"),
                            startSampleId = item.getString("startSampleId"),
                            endSampleId = item.getString("endSampleId"),
                            durationSeconds = item.getDouble("durationSeconds"),
                            referenceLimitKmh = item.getInt("referenceLimitKmh"),
                            peakSpeedKmh = item.getDouble("peakSpeedKmh"),
                            averageExceedanceKmh = item.getDouble("averageExceedanceKmh"),
                            limitStatus = item.optString("limitStatus", "static"),
                            dataQuality = item.optString("dataQuality", "GOOD"),
                            detectorVersion = item.optString("detectorVersion", "speed-v1")
                        )
                    )
                }
            }
        }
        val timeline = root.optJSONArray("timeline")
        var matched = 0
        val total = timeline?.length() ?: 0
        if (timeline != null) {
            for (i in 0 until timeline.length()) {
                val point = timeline.optJSONObject(i) ?: continue
                if (point.optString("matchStatus") == "MATCHED") matched += 1
            }
        }
        return SpeedAnalysisResult(
            analysisStatus = root.optString("analysisStatus", "COMPLETED"),
            events = events,
            matchedTimelinePoints = matched,
            timelinePoints = total
        )
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
        return stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 60_000
    }
}
