package com.fit5120tp10.nextcross.client.historicalcrash

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * HTTP adapter for the planned Backend Historical Context API.
 * Until Backend ships the endpoint, [HistoricalCrashContextLocator] defaults to the mock client.
 *
 * Contract (for Backend):
 * GET {baseUrl}?latitude=&longitude=&radiusMeters=&timePeriod=morning|daytime|night|late_night
 * Optional: startDate=&endDate=
 * Response JSON matches iteration2_v2 lookup_crashes fields used by [HistoricalCrashJson].
 */
class HistoricalCrashContextHttpClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : HistoricalCrashContextClient {
    private val endpointBase = baseUrl.trim().trimEnd('/')

    init {
        val probe = URL(endpointBase)
        require(probe.protocol == "http" || probe.protocol == "https") {
            "Historical crash endpoint must use HTTP or HTTPS."
        }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive." }
        require(readTimeoutMillis > 0) { "Read timeout must be positive." }
    }

    override fun lookup(request: HistoricalCrashContextRequest): HistoricalCrashContextResult {
        val url = URL("$endpointBase?${HistoricalCrashJson.requestQueryString(request)}")
        var connection: HttpURLConnection? = null
        return try {
            connection = connectionFactory(url)
            connection.requestMethod = HTTP_METHOD_GET
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty(HEADER_ACCEPT, JSON_ACCEPT)

            val statusCode = connection.responseCode
            val body = readBody(connection, statusCode)
            if (statusCode !in HTTP_SUCCESS_RANGE) {
                return HistoricalCrashContextResult.HttpFailure(statusCode)
            }
            try {
                HistoricalCrashContextResult.Success(
                    HistoricalCrashJson.parseResponse(body, request)
                )
            } catch (exception: Exception) {
                HistoricalCrashContextResult.InvalidPayload(
                    exception.message ?: "Unable to parse historical crash response."
                )
            }
        } catch (exception: Exception) {
            HistoricalCrashContextResult.TransportFailure(exception)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode >= HTTP_ERROR_STATUS_START) {
            connection.errorStream
        } else {
            connection.inputStream
        } ?: return ""
        return stream.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000

        private const val HTTP_METHOD_GET = "GET"
        private const val HEADER_ACCEPT = "Accept"
        private const val JSON_ACCEPT = "application/json"
        private const val HTTP_ERROR_STATUS_START = 400
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}
