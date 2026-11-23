package com.fit5120tp10.nextcross.client.aireflection

import org.json.JSONArray
import org.json.JSONObject

/** Path segments appended to the configured `/api/v1/ai/reflection` base. */
const val REFLECTION_QUESTION_PATH: String = "question"
const val REFLECTION_ADVICE_PATH: String = "advice"

/** The backend caps the echoed question at 500 characters. */
const val MAX_QUESTION_CHARACTERS: Int = 500

/** The backend caps `tripId` at 200 characters. */
const val MAX_TRIP_ID_CHARACTERS: Int = 200

/**
 * Builds the two request bodies and parses the two replies.
 *
 * Pure Kotlin on purpose: no Android, no network and no secrets, so every field of the wire
 * contract and every parsing decision is covered by plain JVM unit tests.
 *
 * The backend models are declared with `extra="forbid"`, so a single stray field turns the
 * whole request into HTTP 422. Only the fields below may be sent.
 */
object ReflectionBackendJson {

    /** Turn one: facts in, neutral headline plus one open question out. */
    fun questionRequestBody(tripId: String, facts: ReflectionEventFacts): String =
        envelope(tripId, facts).toString()

    /** Turn two: the same facts, the question already asked, and the driver's own words. */
    fun adviceRequestBody(
        tripId: String,
        facts: ReflectionEventFacts,
        question: ReflectionQuestion,
        driverContext: String
    ): String {
        val sanitised = sanitiseDriverContext(driverContext)
        require(sanitised.isNotEmpty()) { "The driver's description must not be empty." }
        return envelope(tripId, facts)
            .put("questionAsked", question.question.take(MAX_QUESTION_CHARACTERS))
            .put("driverContext", sanitised)
            .toString()
    }

    /**
     * The shared request envelope.
     *
     * `endOffsetSeconds` is derived from the start and the duration rather than measured,
     * because the backend rejects the request unless the three agree to within 0.01s and
     * rounding them independently can break that.
     */
    private fun envelope(tripId: String, facts: ReflectionEventFacts): JSONObject {
        val trip = tripId.trim()
        require(trip.isNotEmpty()) { "A trip id is required." }

        val metrics = metricsOf(facts)

        val startOffset = round3(facts.timeAfterDepartureSeconds)
        val duration = round3(facts.durationSeconds)

        val selectedEvent = JSONObject()
            .put("eventId", facts.eventId)
            .put("type", facts.eventType.wireValue)
            .put("startOffsetSeconds", startOffset)
            .put("endOffsetSeconds", startOffset + duration)
            .put("durationSeconds", duration)
            .put("dataQuality", facts.dataQuality.wireValue)
            .put("detectorVersion", facts.detectorVersion)
            .put("metrics", metrics)

        val body = JSONObject()
            .put("tripId", trip.take(MAX_TRIP_ID_CHARACTERS))
            .put("selectedEvent", selectedEvent)

        facts.roadContext?.let { road ->
            // Event-time reference limit only; never a current-location lookup.
            body.put(
                "roadContext",
                JSONObject()
                    .put("referenceLimitKmh", road.referenceLimitKmh)
                    .put("limitStatus", road.limitStatus.wireValue)
            )
        }

        facts.tripEventCounts?.let { counts ->
            // The backend requires all three counts whenever a summary is supplied, and
            // requires the selected event's own count to be at least one.
            body.put(
                "tripSummary",
                JSONObject()
                    .put("rapidAccelerationCount", counts.rapidAccelerationCount)
                    .put("rapidDecelerationCount", counts.rapidDecelerationCount)
                    .put("speedLimitExceedanceCount", counts.speedLimitExceedanceCount)
            )
        }
        return body
    }

    /**
     * The backend rejects mixed metric sets, so each family sends exactly its own fields.
     * `peakExceedanceKmh` is optional on the backend and is left out: it is derived from the
     * peak and the limit, and sending it only adds another value that must agree after rounding.
     */
    private fun metricsOf(facts: ReflectionEventFacts): JSONObject {
        val limit = facts.speedLimit
        if (facts.eventType == ReflectionEventType.SPEED_LIMIT_EXCEEDANCE && limit != null) {
            val peak = round1(limit.peakSpeedKmh)
            // Rounding must never push the average above the peak exceedance.
            val average = minOf(round1(limit.averageExceedanceKmh), round1(peak - limit.referenceLimitKmh))
            return JSONObject()
                .put("referenceLimitKmh", limit.referenceLimitKmh)
                .put("peakSpeedKmh", peak)
                .put("averageExceedanceKmh", average.coerceAtLeast(0.0))
        }
        return JSONObject()
            .put("speedBeforeKmh", round1(requireNotNull(facts.speedBeforeKmh)))
            .put("speedAfterKmh", round1(requireNotNull(facts.speedAfterKmh)))
            .put("peakAccelerationMs2", round1(requireNotNull(facts.peakAccelerationMps2)))
    }

    /** One line, no control characters, capped length. Never rejects the driver's wording. */
    fun sanitiseDriverContext(raw: String): String {
        val singleLine = raw.map { character ->
            if (character.isISOControl()) ' ' else character
        }.joinToString(separator = "")
        val collapsed = singleLine.split(' ').filter { it.isNotEmpty() }.joinToString(separator = " ")
        return if (collapsed.length <= MAX_DRIVER_CONTEXT_CHARACTERS) {
            collapsed
        } else {
            collapsed.substring(0, MAX_DRIVER_CONTEXT_CHARACTERS).trimEnd()
        }
    }

    /**
     * Turn one response. HTTP 200 alone is never success: the envelope's `aiStatus` decides.
     * Throws when the body is not the documented shape, so the caller can report it.
     */
    fun parseQuestion(responseBody: String): AiReflectionResult<ReflectionQuestion> =
        parseEnvelope(responseBody, "question") { payload ->
            ReflectionQuestion(
                headline = requiredText(payload, "headline"),
                question = requiredText(payload, "question"),
                quickReplies = stringList(payload.optJSONArray("quickReplies"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(3)
            )
        }

    /** Turn two response, same envelope rules. */
    fun parseAdvice(responseBody: String): AiReflectionResult<ReflectionAdvice> =
        parseEnvelope(responseBody, "advice") { payload ->
            val suggestions = stringList(payload.optJSONArray("advice"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
            require(suggestions.isNotEmpty()) { "The backend returned no suggestions." }
            ReflectionAdvice(
                acknowledgement = requiredText(payload, "acknowledgement"),
                advice = suggestions,
                encouragement = requiredText(payload, "encouragement")
            )
        }

    private fun <T> parseEnvelope(
        responseBody: String,
        payloadKey: String,
        read: (JSONObject) -> T
    ): AiReflectionResult<T> {
        val body = JSONObject(responseBody)
        return when (val status = body.optString("aiStatus", "").trim()) {
            "COMPLETED" -> {
                val payload = body.optJSONObject(payloadKey)
                require(payload != null) { "The backend omitted \"$payloadKey\"." }
                AiReflectionResult.Completed(read(payload))
            }
            "UNAVAILABLE" ->
                AiReflectionResult.Unavailable(
                    AiUnavailableReason.fromWire(body.optString("reason", ""))
                )
            "INVALID_EVENT" ->
                AiReflectionResult.Rejected(
                    body.optString("reason", "").ifBlank { "insufficient_data_quality" }
                )
            "FAILED" ->
                AiReflectionResult.Failed(
                    body.optString("reason", "").ifBlank { "invalid_or_unexpected_response" }
                )
            else -> throw IllegalArgumentException(
                if (status.isEmpty()) {
                    "The backend returned no aiStatus."
                } else {
                    "The backend returned an unknown aiStatus ($status)."
                }
            )
        }
    }

    /** HTTP 422 bodies list sanitised field locations; surface them so the shape can be fixed. */
    fun describeValidationError(responseBody: String): String = runCatching {
        val details = JSONObject(responseBody).optJSONArray("detail") ?: return@runCatching ""
        (0 until details.length()).mapNotNull { index ->
            val entry = details.optJSONObject(index) ?: return@mapNotNull null
            val location = stringList(entry.optJSONArray("loc")).joinToString(".")
            val type = entry.optString("type", "")
            listOf(location, type).filter { it.isNotEmpty() }.joinToString(": ")
        }.filter { it.isNotEmpty() }.joinToString("; ")
    }.getOrDefault("")

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val value = array.opt(index)
            if (value is String) value else value?.toString()
        }
    }

    private fun requiredText(payload: JSONObject, name: String): String {
        val value = payload.optString(name, "").trim()
        require(value.isNotEmpty()) { "The backend omitted \"$name\"." }
        return value
    }

    /** One decimal place keeps the payload readable and hides false precision. */
    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    /** Offsets keep three decimals so the duration consistency check stays well inside 0.01s. */
    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}
