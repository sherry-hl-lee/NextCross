package com.fit5120tp10.nextcross.client.permittedarea.transport

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent
import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEventType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventsHttpClientTest {
    @Test
    fun postsJsonAndAcceptsCreatedResponse() {
        val connection = FakeHttpURLConnection(201)
        val client = NotificationEventsHttpClient(
            endpointUrl = ENDPOINT,
            connectionFactory = { connection }
        )

        val result = client.send(event())

        assertEquals(NotificationDeliveryResult.Accepted(201), result)
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals(
            NotificationEventRequest.from(event()).toJson(),
            connection.requestBody.toString(StandardCharsets.UTF_8.name())
        )
        assertTrue(connection.wasDisconnected)
    }

    @Test
    fun classifiesNonSuccessStatusAsRejected() {
        val client = NotificationEventsHttpClient(
            endpointUrl = ENDPOINT,
            connectionFactory = { FakeHttpURLConnection(422) }
        )

        assertEquals(NotificationDeliveryResult.Rejected(422), client.send(event()))
    }

    @Test
    fun classifiesConnectionFailureWithoutThrowingToCaller() {
        val client = NotificationEventsHttpClient(
            endpointUrl = ENDPOINT,
            connectionFactory = { throw IOException("offline") }
        )

        assertTrue(client.send(event()) is NotificationDeliveryResult.Failed)
    }

    @Test
    fun deleteEventsForTripUsesDeleteOnTripPath() {
        val connection = FakeHttpURLConnection(200)
        var requestedUrl: String? = null
        val client = NotificationEventsHttpClient(
            endpointUrl = ENDPOINT,
            connectionFactory = { url ->
                requestedUrl = url.toString()
                connection
            }
        )

        val result = client.deleteEventsForTrip("planned-trip-001")

        assertEquals(NotificationDeliveryResult.Accepted(200), result)
        assertEquals("DELETE", connection.requestMethod)
        assertEquals("$ENDPOINT/planned-trip-001", requestedUrl)
        assertTrue(connection.wasDisconnected)
    }

    private fun event(): AreaBreachEvent {
        return AreaBreachEvent(
            tripId = "planned-trip-001",
            eventId = "breach-001",
            eventType = AreaBreachEventType.PermittedAreaBreach,
            occurredAtEpochMillis = 1_788_231_600_000L,
            parentEmail = "parent@example.com"
        )
    }

    private class FakeHttpURLConnection(
        private val fakeResponseCode: Int
    ) : HttpURLConnection(URL(ENDPOINT)) {
        val requestBody = ByteArrayOutputStream()
        var wasDisconnected: Boolean = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            wasDisconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): ByteArrayOutputStream = requestBody

        override fun getResponseCode(): Int = fakeResponseCode

        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())

        override fun getErrorStream() = ByteArrayInputStream("{}".toByteArray())
    }

    companion object {
        private const val ENDPOINT = "http://10.0.2.2:8000/api/v1/notification-events"
    }
}
