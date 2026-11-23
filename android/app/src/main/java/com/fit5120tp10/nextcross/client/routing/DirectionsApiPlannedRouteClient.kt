package com.fit5120tp10.nextcross.client.routing

import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Google Directions API adapter for static origin→destination polylines.
 * Does not start Navigation guidance and does not read device GPS.
 */
class DirectionsApiPlannedRouteClient(
    private val apiKey: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : PlannedRouteClient {
    init {
        require(apiKey.isNotBlank()) { "Directions API key must not be blank." }
    }

    override fun plan(request: PlannedRouteRequest): PlannedRouteResult {
        if (apiKey == PLACEHOLDER_API_KEY || apiKey == "DEFAULT_API_KEY") {
            return PlannedRouteResult.Failed(PlannedRouteFailure.MissingApiKey)
        }
        if (request.origin.isSameEndpointAs(request.destination)) {
            return PlannedRouteResult.Failed(PlannedRouteFailure.InvalidEndpoints)
        }

        val url = URL(buildDirectionsUrl(request))
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
                return PlannedRouteResult.Failed(
                    PlannedRouteFailure.ApiFailure(
                        status = "HTTP_$statusCode",
                        detail = body.take(MAX_ERROR_DETAIL_CHARS).ifBlank { null }
                    )
                )
            }
            parseDirectionsResponse(body)
        } catch (exception: Exception) {
            PlannedRouteResult.Failed(PlannedRouteFailure.TransportFailure(exception))
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildDirectionsUrl(request: PlannedRouteRequest): String {
        val origin = encodeEndpoint(request.origin)
        val destination = encodeEndpoint(request.destination)
        return "$DIRECTIONS_ENDPOINT?origin=$origin&destination=$destination" +
            "&mode=driving&key=${URLEncoder.encode(apiKey, UTF8)}"
    }

    private fun encodeEndpoint(place: SelectedPlace): String {
        val placeId = place.placeId?.trim().orEmpty()
        return if (placeId.isNotEmpty()) {
            URLEncoder.encode("place_id:$placeId", UTF8)
        } else {
            URLEncoder.encode("${place.point.latitude},${place.point.longitude}", UTF8)
        }
    }

    private fun parseDirectionsResponse(body: String): PlannedRouteResult {
        val root = JSONObject(body)
        val status = root.optString("status", "UNKNOWN")
        when (status) {
            "OK" -> Unit
            "ZERO_RESULTS", "NOT_FOUND" ->
                return PlannedRouteResult.Failed(PlannedRouteFailure.NoRouteFound)
            "REQUEST_DENIED", "OVER_DAILY_LIMIT", "OVER_QUERY_LIMIT",
            "INVALID_REQUEST", "UNKNOWN_ERROR" ->
                return PlannedRouteResult.Failed(
                    PlannedRouteFailure.ApiFailure(
                        status = status,
                        detail = root.optString("error_message").ifBlank { null }
                    )
                )
            else ->
                return PlannedRouteResult.Failed(
                    PlannedRouteFailure.ApiFailure(status = status)
                )
        }

        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            return PlannedRouteResult.Failed(PlannedRouteFailure.NoRouteFound)
        }
        val route = routes.getJSONObject(0)
        val encoded = route.optJSONObject("overview_polyline")?.optString("points").orEmpty()
        val points = EncodedPolylineDecoder.decode(encoded)
        if (points.size < 2) {
            return PlannedRouteResult.Failed(PlannedRouteFailure.NoRouteFound)
        }

        var distanceMeters = 0
        var durationSeconds = 0
        val legs = route.optJSONArray("legs")
        if (legs != null) {
            for (index in 0 until legs.length()) {
                val leg = legs.getJSONObject(index)
                distanceMeters += leg.optJSONObject("distance")?.optInt("value") ?: 0
                durationSeconds += leg.optJSONObject("duration")?.optInt("value") ?: 0
            }
        }

        return PlannedRouteResult.Success(
            PlannedRoute(
                points = points,
                distanceMeters = distanceMeters.takeIf { it > 0 },
                durationSeconds = durationSeconds.takeIf { it > 0 }
            )
        )
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode >= 400) {
            connection.errorStream
        } else {
            connection.inputStream
        } ?: return ""
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    companion object {
        private const val DIRECTIONS_ENDPOINT =
            "https://maps.googleapis.com/maps/api/directions/json"
        private const val UTF8 = "UTF-8"
        private const val PLACEHOLDER_API_KEY = "DEFAULT_API_KEY"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
        private const val MAX_ERROR_DETAIL_CHARS = 300
    }
}
