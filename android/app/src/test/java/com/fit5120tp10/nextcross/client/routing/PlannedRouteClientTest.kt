package com.fit5120tp10.nextcross.client.routing

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import com.fit5120tp10.nextcross.client.permittedarea.places.SelectedPlace
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPlannedRouteClientTest {
    @Test
    fun returnsPolylineWithAtLeastTwoPoints() {
        val client = MockPlannedRouteClient()
        val result = client.plan(
            PlannedRouteRequest(
                origin = place("A", -37.8136, 144.9631),
                destination = place("B", -37.8768, 145.0440)
            )
        ) as PlannedRouteResult.Success

        assertTrue(result.route.points.size >= 2)
        assertEquals(-37.8136, result.route.points.first().latitude, 0.0)
        assertEquals(-37.8768, result.route.points.last().latitude, 0.0)
    }
}

class EncodedPolylineDecoderTest {
    @Test
    fun decodesGoogleSamplePolyline() {
        // Encoded form of a short known segment used in Google docs examples.
        val points = EncodedPolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertTrue(points.size >= 2)
    }
}

class DirectionsApiPlannedRouteClientTest {
    @Test
    fun parsesOkDirectionsResponse() {
        val body = """
            {
              "status": "OK",
              "routes": [
                {
                  "overview_polyline": { "points": "_p~iF~ps|U_ulLnnqC_mqNvxq`@" },
                  "legs": [
                    {
                      "distance": { "value": 1234 },
                      "duration": { "value": 456 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val connection = FakeHttpURLConnection(200, body)
        val client = DirectionsApiPlannedRouteClient(
            apiKey = "test-key",
            connectionFactory = { connection }
        )

        val result = client.plan(
            PlannedRouteRequest(
                origin = place("A", -37.81, 144.96),
                destination = place("B", -37.87, 145.04)
            )
        ) as PlannedRouteResult.Success

        assertTrue(result.route.points.size >= 2)
        assertEquals(1234, result.route.distanceMeters)
        assertEquals(456, result.route.durationSeconds)
    }

    @Test
    fun mapsZeroResultsToNoRouteFound() {
        val body = """{ "status": "ZERO_RESULTS", "routes": [] }"""
        val client = DirectionsApiPlannedRouteClient(
            apiKey = "test-key",
            connectionFactory = { FakeHttpURLConnection(200, body) }
        )

        val result = client.plan(
            PlannedRouteRequest(
                origin = place("A", -37.81, 144.96),
                destination = place("B", -37.87, 145.04)
            )
        ) as PlannedRouteResult.Failed

        assertEquals(PlannedRouteFailure.NoRouteFound, result.reason)
    }

    private class FakeHttpURLConnection(
        private val code: Int,
        private val body: String
    ) : HttpURLConnection(URL("https://maps.googleapis.com/maps/api/directions/json")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream() =
            ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))
        override fun getErrorStream() =
            ByteArrayInputStream("{}".toByteArray(StandardCharsets.UTF_8))
    }
}

private fun place(name: String, lat: Double, lng: Double): SelectedPlace {
    return SelectedPlace(
        displayName = name,
        placeId = null,
        point = GeographicPoint(lat, lng)
    )
}
