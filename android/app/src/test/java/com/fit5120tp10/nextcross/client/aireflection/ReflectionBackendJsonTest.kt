package com.fit5120tp10.nextcross.client.aireflection

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReflectionBackendJsonTest {

    private fun brakingFacts(
        quality: ReflectionDataQuality = ReflectionDataQuality.UNKNOWN,
        counts: TripEventCounts? = TripEventCounts(1, 1, 0)
    ) = ReflectionEventFacts(
        eventId = "trip-1:RAPID_DECELERATION:41:43",
        eventType = ReflectionEventType.RAPID_DECELERATION,
        detectorVersion = CLIENT_DETECTOR_VERSION,
        timeAfterDepartureSeconds = 180.047,
        durationSeconds = 1.013,
        speedBeforeKmh = 60.04,
        speedAfterKmh = 10.02,
        peakAccelerationMps2 = -13.91,
        dataQuality = quality,
        tripEventCounts = counts
    )

    @Test
    fun `question body carries only the fields the backend accepts`() {
        val body = JSONObject(ReflectionBackendJson.questionRequestBody("trip-1", brakingFacts()))
        assertEquals(setOf("tripId", "selectedEvent", "tripSummary"), body.keys().asSequence().toSet())

        val event = body.getJSONObject("selectedEvent")
        assertEquals(
            setOf(
                "eventId", "type", "startOffsetSeconds", "endOffsetSeconds",
                "durationSeconds", "dataQuality", "detectorVersion", "metrics"
            ),
            event.keys().asSequence().toSet()
        )
        val metrics = event.getJSONObject("metrics")
        assertEquals(
            setOf("speedBeforeKmh", "speedAfterKmh", "peakAccelerationMs2"),
            metrics.keys().asSequence().toSet()
        )
    }

    @Test
    fun `fields the backend forbids are never sent`() {
        val body = ReflectionBackendJson.questionRequestBody("trip-1", brakingFacts())
        listOf(
            "averageAccelerationMs2", "speedChangeKmh", "reviewPriorityScore",
            "latitude", "longitude", "timestamp", "analysisRevision", "routePoints"
        ).forEach { forbidden ->
            assertFalse("$forbidden must not be sent", body.contains(forbidden))
        }
    }

    @Test
    fun `end offset always agrees with start plus duration`() {
        val event = JSONObject(ReflectionBackendJson.questionRequestBody("trip-1", brakingFacts()))
            .getJSONObject("selectedEvent")
        val drift = abs(
            event.getDouble("endOffsetSeconds") -
                event.getDouble("startOffsetSeconds") -
                event.getDouble("durationSeconds")
        )
        assertTrue("drift was $drift", drift <= 0.01)
    }

    @Test
    fun `trip summary always carries all three counts`() {
        val summary = JSONObject(ReflectionBackendJson.questionRequestBody("trip-1", brakingFacts()))
            .getJSONObject("tripSummary")
        assertEquals(
            setOf("rapidAccelerationCount", "rapidDecelerationCount", "speedLimitExceedanceCount"),
            summary.keys().asSequence().toSet()
        )
        assertEquals(1, summary.getInt("rapidDecelerationCount"))
        assertEquals(0, summary.getInt("speedLimitExceedanceCount"))
    }

    @Test
    fun `summary is omitted when no counts are supplied`() {
        val body = JSONObject(
            ReflectionBackendJson.questionRequestBody("trip-1", brakingFacts(counts = null))
        )
        assertFalse(body.has("tripSummary"))
    }

    @Test
    fun `advice body adds the question and the driver sentence`() {
        val question = ReflectionQuestion("Headline.", "What happened?", listOf("A car cut in"))
        val body = JSONObject(
            ReflectionBackendJson.adviceRequestBody(
                "trip-1", brakingFacts(), question, "  A car\tcut   into my lane.  "
            )
        )
        assertEquals("What happened?", body.getString("questionAsked"))
        assertEquals("A car cut into my lane.", body.getString("driverContext"))
        assertTrue(body.has("selectedEvent"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `advice body refuses an empty driver sentence`() {
        val question = ReflectionQuestion("Headline.", "What happened?", emptyList())
        ReflectionBackendJson.adviceRequestBody("trip-1", brakingFacts(), question, "   \n  ")
    }

    @Test
    fun `driver context is collapsed and capped`() {
        val raw = "a\u0000b\nc" + " ".repeat(5) + "d".repeat(500)
        val sanitised = ReflectionBackendJson.sanitiseDriverContext(raw)
        assertFalse(sanitised.any { it.isISOControl() })
        assertTrue(sanitised.length <= MAX_DRIVER_CONTEXT_CHARACTERS)
    }

    @Test
    fun `completed question is parsed`() {
        val result = ReflectionBackendJson.parseQuestion(
            """
            {"tripId":"t","eventId":"e","aiStatus":"COMPLETED",
             "question":{"headline":"H","question":"Q","quickReplies":["a","b","c","d"]},
             "reason":null,"disclaimer":"d"}
            """.trimIndent()
        )
        val question = (result as AiReflectionResult.Completed).value
        assertEquals("H", question.headline)
        assertEquals(3, question.quickReplies.size)
    }

    @Test
    fun `unavailable maps onto the matching reason`() {
        val result = ReflectionBackendJson.parseQuestion(
            """{"tripId":"t","eventId":"e","aiStatus":"UNAVAILABLE","reason":"rate_limited"}"""
        )
        assertEquals(
            AiUnavailableReason.RATE_LIMITED,
            (result as AiReflectionResult.Unavailable).reason
        )
    }

    @Test
    fun `an unknown reason degrades instead of throwing`() {
        val result = ReflectionBackendJson.parseQuestion(
            """{"tripId":"t","eventId":"e","aiStatus":"UNAVAILABLE","reason":"something_new"}"""
        )
        assertEquals(
            AiUnavailableReason.PROVIDER_UNAVAILABLE,
            (result as AiReflectionResult.Unavailable).reason
        )
    }

    @Test
    fun `invalid event becomes a rejection, not a failure`() {
        val result = ReflectionBackendJson.parseQuestion(
            """{"tripId":"t","eventId":"e","aiStatus":"INVALID_EVENT","reason":"insufficient_data_quality"}"""
        )
        assertEquals(
            "insufficient_data_quality",
            (result as AiReflectionResult.Rejected).reason
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a missing aiStatus is reported`() {
        ReflectionBackendJson.parseQuestion("""{"tripId":"t","eventId":"e"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `completed without a payload is reported`() {
        ReflectionBackendJson.parseQuestion("""{"aiStatus":"COMPLETED"}""")
    }

    @Test
    fun `completed advice is parsed`() {
        val result = ReflectionBackendJson.parseAdvice(
            """
            {"aiStatus":"COMPLETED",
             "advice":{"acknowledgement":"A","advice":["one","two"],"encouragement":"E"}}
            """.trimIndent()
        )
        val advice = (result as AiReflectionResult.Completed).value
        assertEquals(2, advice.advice.size)
        assertEquals("E", advice.encouragement)
    }

    private fun speedFacts(
        limit: Int = 60,
        peak: Double = 74.04,
        average: Double = 8.02,
        road: ReflectionRoadContext? = ReflectionRoadContext(60, ReflectionLimitStatus.STATIC)
    ) = ReflectionEventFacts(
        eventId = "trip-1:SPEED_LIMIT_EXCEEDANCE:E_SPEED_0001",
        eventType = ReflectionEventType.SPEED_LIMIT_EXCEEDANCE,
        detectorVersion = "speed-v1",
        timeAfterDepartureSeconds = 600.0,
        durationSeconds = 40.0,
        dataQuality = ReflectionDataQuality.GOOD,
        tripEventCounts = TripEventCounts(1, 1, 1),
        speedLimit = ReflectionSpeedLimitMetrics(limit, peak, average),
        roadContext = road
    )

    @Test
    fun `a speed-limit event sends only speed metrics and its own limit`() {
        val body = JSONObject(ReflectionBackendJson.questionRequestBody("trip-1", speedFacts()))
        assertEquals(setOf("tripId", "selectedEvent", "tripSummary", "roadContext"), body.keys().asSequence().toSet())

        val event = body.getJSONObject("selectedEvent")
        assertEquals("SPEED_LIMIT_EXCEEDANCE", event.getString("type"))
        assertEquals("speed-v1", event.getString("detectorVersion"))
        val metrics = event.getJSONObject("metrics")
        assertEquals(
            setOf("referenceLimitKmh", "peakSpeedKmh", "averageExceedanceKmh"),
            metrics.keys().asSequence().toSet()
        )
        assertEquals(60, metrics.getInt("referenceLimitKmh"))
        assertEquals(74.0, metrics.getDouble("peakSpeedKmh"), 1e-9)
        assertEquals(8.0, metrics.getDouble("averageExceedanceKmh"), 1e-9)

        val road = body.getJSONObject("roadContext")
        assertEquals(setOf("referenceLimitKmh", "limitStatus"), road.keys().asSequence().toSet())
        assertEquals(60, road.getInt("referenceLimitKmh"))
        assertEquals("static", road.getString("limitStatus"))
        assertEquals(1, body.getJSONObject("tripSummary").getInt("speedLimitExceedanceCount"))
    }

    @Test
    fun `a manoeuvre during speeding adds the event-time limit and keeps its own metrics`() {
        val facts = brakingFacts().copy(roadContext = ReflectionRoadContext(60, ReflectionLimitStatus.SCHEDULED))
        val body = JSONObject(ReflectionBackendJson.questionRequestBody("trip-1", facts))
        assertEquals("scheduled", body.getJSONObject("roadContext").getString("limitStatus"))
        assertEquals(
            setOf("speedBeforeKmh", "speedAfterKmh", "peakAccelerationMs2"),
            body.getJSONObject("selectedEvent").getJSONObject("metrics").keys().asSequence().toSet()
        )
    }

    @Test
    fun `rounding never pushes the average above the peak exceedance`() {
        val random = java.util.Random(5120)
        repeat(2_000) {
            val limit = 20 + random.nextInt(91)
            val peak = limit + 0.15 + random.nextDouble() * 40.0
            val average = random.nextDouble() * (peak - limit)
            val metrics = JSONObject(
                ReflectionBackendJson.questionRequestBody("trip-1", speedFacts(limit, peak, average, road = null))
            ).getJSONObject("selectedEvent").getJSONObject("metrics")
            val peakExceedance = metrics.getDouble("peakSpeedKmh") - limit
            assertTrue(peakExceedance > 0.0)
            assertTrue(metrics.getDouble("averageExceedanceKmh") <= peakExceedance + 1e-9)
        }
    }

    @Test
    fun `mixed or missing metric sets are refused before anything is sent`() {
        val rejected = listOf<() -> Unit>(
            { brakingFacts().copy(speedLimit = ReflectionSpeedLimitMetrics(60, 70.0, 5.0)) },
            { brakingFacts().copy(peakAccelerationMps2 = null) },
            { speedFacts().copy(speedBeforeKmh = 70.0) },
            { speedFacts().copy(speedLimit = null) },
            { speedFacts(road = ReflectionRoadContext(50, ReflectionLimitStatus.STATIC)) },
            { ReflectionSpeedLimitMetrics(60, 60.0, 0.0) },
            { ReflectionSpeedLimitMetrics(60, 70.0, 10.5) }
        )
        rejected.forEachIndexed { index, build ->
            try {
                build()
                throw AssertionError("case $index should have been rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `limit statuses without a usable limit are not mapped`() {
        assertEquals(ReflectionLimitStatus.SCHEDULED, ReflectionLimitStatus.fromWire("scheduled"))
        listOf("unknown", "dynamic_unknown", "ambiguous", "no_match", "", null).forEach {
            assertEquals(null, ReflectionLimitStatus.fromWire(it))
        }
    }

    @Test
    fun `validation errors are described for the log`() {
        val described = ReflectionBackendJson.describeValidationError(
            """{"aiStatus":"INVALID_EVENT","reflection":null,
                "detail":[{"loc":["body","selectedEvent","metrics"],"type":"extra_forbidden"}]}"""
        )
        assertTrue(described.contains("selectedEvent.metrics"))
        assertTrue(described.contains("extra_forbidden"))
    }
}
