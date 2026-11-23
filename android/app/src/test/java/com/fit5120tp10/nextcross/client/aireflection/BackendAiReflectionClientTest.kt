package com.fit5120tp10.nextcross.client.aireflection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captures what the client sent and replays a canned backend answer. */
private class FakeConnection(
    url: URL,
    private val status: Int,
    private val body: String = "",
    private val errorBody: String = "",
    private val throwTimeout: Boolean = false
) : HttpURLConnection(url) {
    val sent = ByteArrayOutputStream()
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getOutputStream(): OutputStream = sent
    override fun getResponseCode(): Int {
        if (throwTimeout) throw SocketTimeoutException("read timed out")
        return status
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(errorBody.toByteArray())
}

class BackendAiReflectionClientTest {

    private val facts = ReflectionEventFacts(
        eventId = "trip-1:RAPID_DECELERATION:41:43",
        eventType = ReflectionEventType.RAPID_DECELERATION,
        detectorVersion = CLIENT_DETECTOR_VERSION,
        timeAfterDepartureSeconds = 180.0,
        durationSeconds = 1.0,
        speedBeforeKmh = 60.0,
        speedAfterKmh = 10.0,
        peakAccelerationMps2 = -13.9,
        dataQuality = ReflectionDataQuality.UNKNOWN,
        tripEventCounts = TripEventCounts(1, 1, 0)
    )

    private val question = ReflectionQuestion("H", "What happened?", listOf("A car cut in"))

    private var lastConnection: FakeConnection? = null
    private var lastUrl: URL? = null

    private fun clientReturning(
        status: Int,
        body: String = "",
        errorBody: String = "",
        throwTimeout: Boolean = false
    ): BackendAiReflectionClient = BackendAiReflectionClient(
        ReflectionBackendHttpClient(
            baseUrl = "https://backend.example.com/api/v1/ai/reflection",
            connectionFactory = { url ->
                lastUrl = url
                FakeConnection(url, status, body, errorBody, throwTimeout).also { lastConnection = it }
            }
        )
    )

    private val completedQuestion = """
        {"tripId":"trip-1","eventId":"e","aiStatus":"COMPLETED",
         "question":{"headline":"H","question":"Q","quickReplies":["a","b","c"]}}
    """.trimIndent()

    @Test
    fun `a completed question comes back parsed`() {
        val result = clientReturning(200, completedQuestion).askForQuestion("trip-1", facts)
        assertTrue(result is AiReflectionResult.Completed)
        assertEquals("Q", (result as AiReflectionResult.Completed).value.question)
    }

    @Test
    fun `each turn posts to its own path`() {
        clientReturning(200, completedQuestion).askForQuestion("trip-1", facts)
        assertTrue(lastUrl.toString().endsWith("/ai/reflection/question"))

        clientReturning(
            200,
            """{"aiStatus":"COMPLETED","advice":{"acknowledgement":"A","advice":["x"],"encouragement":"E"}}"""
        ).askForAdvice("trip-1", facts, question, "A car cut into my lane.")
        assertTrue(lastUrl.toString().endsWith("/ai/reflection/advice"))
    }

    @Test
    fun `no provider credential is ever attached`() {
        clientReturning(200, completedQuestion).askForQuestion("trip-1", facts)
        assertNull(lastConnection?.getRequestProperty("Authorization"))
    }

    @Test
    fun `the posted body carries the trip id and no forbidden fields`() {
        clientReturning(200, completedQuestion).askForQuestion("trip-1", facts)
        val sent = lastConnection?.sent?.toString("UTF-8").orEmpty()
        assertEquals("trip-1", JSONObject(sent).getString("tripId"))
        assertFalse(sent.contains("averageAccelerationMs2"))
        assertFalse(sent.contains("reviewPriorityScore"))
    }

    @Test
    fun `an old deployment without the new routes is reported clearly`() {
        val result = clientReturning(404).askForQuestion("trip-1", facts)
        assertEquals(
            AiUnavailableReason.ENDPOINT_NOT_FOUND,
            (result as AiReflectionResult.Unavailable).reason
        )
    }

    @Test
    fun `transport failures map onto their reasons`() {
        listOf(
            429 to AiUnavailableReason.RATE_LIMITED,
            401 to AiUnavailableReason.PROVIDER_AUTHENTICATION,
            403 to AiUnavailableReason.PROVIDER_AUTHENTICATION,
            503 to AiUnavailableReason.PROVIDER_UNAVAILABLE,
            418 to AiUnavailableReason.PROVIDER_REQUEST_REJECTED
        ).forEach { (status, expected) ->
            val result = clientReturning(status).askForQuestion("trip-1", facts)
            assertEquals(
                "status $status",
                expected,
                (result as AiReflectionResult.Unavailable).reason
            )
        }
    }

    @Test
    fun `a read timeout is reported as a timeout`() {
        val result = clientReturning(200, throwTimeout = true).askForQuestion("trip-1", facts)
        assertEquals(
            AiUnavailableReason.TIMEOUT,
            (result as AiReflectionResult.Unavailable).reason
        )
    }

    @Test
    fun `a rejected request shape names the offending field`() {
        val result = clientReturning(
            422,
            errorBody = """{"aiStatus":"INVALID_EVENT","detail":[{"loc":["body","selectedEvent"],"type":"extra_forbidden"}]}"""
        ).askForQuestion("trip-1", facts)
        val detail = (result as AiReflectionResult.Failed).detail
        assertTrue(detail.contains("selectedEvent"))
        assertTrue(detail.contains("extra_forbidden"))
    }

    @Test
    fun `a backend refusal of this event is a rejection, not a failure`() {
        val result = clientReturning(
            200,
            """{"aiStatus":"INVALID_EVENT","reason":"insufficient_data_quality"}"""
        ).askForQuestion("trip-1", facts)
        assertTrue(result is AiReflectionResult.Rejected)
    }

    @Test
    fun `an unparseable body never throws at the caller`() {
        val result = clientReturning(200, "not json at all").askForQuestion("trip-1", facts)
        assertTrue(result is AiReflectionResult.Failed)
    }

    @Test
    fun `an empty driver sentence fails before any request is made`() {
        lastUrl = null
        val result = clientReturning(200, completedQuestion)
            .askForAdvice("trip-1", facts, question, "   ")
        assertTrue(result is AiReflectionResult.Failed)
        assertNull(lastUrl)
    }

    @Test
    fun `the mock client answers both turns offline`() {
        val mock = MockAiReflectionClient()
        val first = mock.askForQuestion("trip-1", facts)
        assertTrue(first is AiReflectionResult.Completed)
        val second = mock.askForAdvice("trip-1", facts, question, "A car cut into my lane.")
        assertTrue(second is AiReflectionResult.Completed)
        assertTrue(
            (second as AiReflectionResult.Completed).value.advice.isNotEmpty()
        )
    }
}
