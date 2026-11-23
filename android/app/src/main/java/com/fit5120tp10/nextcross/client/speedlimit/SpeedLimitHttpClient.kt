package com.fit5120tp10.nextcross.client.speedlimit

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * GET /api/v1/speed-limit adapter.
 * Does not invent a limit when the backend status is unknown/ambiguous/no_match.
 */
class SpeedLimitHttpClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : SpeedLimitClient {
    private val endpointBase = baseUrl.trim().trimEnd('/')

    init {
        val probe = URL(endpointBase)
        require(probe.protocol == "http" || probe.protocol == "https") {
            "Speed-limit endpoint must use HTTP or HTTPS."
        }
    }

    override fun lookup(request: SpeedLimitLookupRequest): SpeedLimitLookupResult {
        require(request.latitude in -90.0..90.0)
        require(request.longitude in -180.0..180.0)
        require(request.radiusMeters > 0.0 && request.radiusMeters <= 1000.0)

        val url = URL(buildUrl(request))
        var connection: HttpURLConnection? = null
        return try {
            connection = connectionFactory(url)
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Accept", "application/json")

            val statusCode = connection.responseCode
            val body = readBody(connection, statusCode)
            if (statusCode !in 200..299) {
                return SpeedLimitLookupResult.HttpFailure(statusCode)
            }
            try {
                SpeedLimitLookupResult.Success(parseBody(body))
            } catch (exception: Exception) {
                SpeedLimitLookupResult.InvalidPayload(exception.message ?: "invalid payload")
            }
        } catch (exception: Exception) {
            SpeedLimitLookupResult.TransportFailure(exception)
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildUrl(request: SpeedLimitLookupRequest): String {
        val utf8 = StandardCharsets.UTF_8.name()
        val parts = mutableListOf(
            "latitude=${request.latitude}",
            "longitude=${request.longitude}",
            "observedAt=${URLEncoder.encode(request.observedAtIso, utf8)}",
            "radiusMeters=${request.radiusMeters}"
        )
        request.headingDegrees?.let { parts += "heading=$it" }
        return "$endpointBase?${parts.joinToString("&")}"
    }

    private fun parseBody(body: String): SpeedLimitContext {
        val root = JSONObject(body)
        val statusWire = root.optString("status", "unknown")
        val status = SpeedLimitStatus.fromWireValue(statusWire)
            ?: throw IllegalArgumentException("Unknown speed-limit status: $statusWire")
        val limit = if (root.isNull("speedKmh")) null else root.optInt("speedKmh").takeIf { it > 0 }
        return SpeedLimitContext(
            limitKmh = limit,
            status = status,
            sourceSpeedZoneId = root.optStringOrNull("sourceSpeedZoneId"),
            reason = root.optStringOrNull("reason"),
            dataVersion = root.optStringOrNull("dataVersion")
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            ?: return ""
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
    }
}
