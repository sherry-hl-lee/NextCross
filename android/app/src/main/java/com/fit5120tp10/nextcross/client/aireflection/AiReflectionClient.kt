package com.fit5120tp10.nextcross.client.aireflection

/** Debug hook: receives the exact text sent to and received from the backend. */
fun interface AiReflectionLogger {
    fun log(message: String)
}

/** Release default: nothing is logged. */
val NoAiReflectionLogging: AiReflectionLogger = AiReflectionLogger { }

/** Two turns of one post-trip reflection. Implementations never throw. */
interface AiReflectionClient {
    /** Turn one: neutral restatement of the numbers plus one open question. */
    fun askForQuestion(
        tripId: String,
        facts: ReflectionEventFacts
    ): AiReflectionResult<ReflectionQuestion>

    /** Turn two: advice that takes the driver's own description of the context into account. */
    fun askForAdvice(
        tripId: String,
        facts: ReflectionEventFacts,
        question: ReflectionQuestion,
        driverContext: String
    ): AiReflectionResult<ReflectionAdvice>
}

/**
 * Talks to the NextCross backend, which owns the provider key, the prompts and the output
 * validation.
 *
 * Every failure mode is turned into [AiReflectionResult.Unavailable], [AiReflectionResult.Rejected]
 * or [AiReflectionResult.Failed] so the trip summary can always keep showing the recorded
 * events. An HTTP 200 alone is never treated as success: the envelope's `aiStatus` decides.
 */
class BackendAiReflectionClient(
    private val http: ReflectionBackendHttpClient,
    private val logger: AiReflectionLogger = NoAiReflectionLogging
) : AiReflectionClient {

    override fun askForQuestion(
        tripId: String,
        facts: ReflectionEventFacts
    ): AiReflectionResult<ReflectionQuestion> = exchange(
        turn = "question",
        path = REFLECTION_QUESTION_PATH,
        requestBody = runCatching { ReflectionBackendJson.questionRequestBody(tripId, facts) },
        parse = ReflectionBackendJson::parseQuestion
    )

    override fun askForAdvice(
        tripId: String,
        facts: ReflectionEventFacts,
        question: ReflectionQuestion,
        driverContext: String
    ): AiReflectionResult<ReflectionAdvice> = exchange(
        turn = "advice",
        path = REFLECTION_ADVICE_PATH,
        requestBody = runCatching {
            ReflectionBackendJson.adviceRequestBody(tripId, facts, question, driverContext)
        },
        parse = ReflectionBackendJson::parseAdvice
    )

    private fun <T> exchange(
        turn: String,
        path: String,
        requestBody: Result<String>,
        parse: (String) -> AiReflectionResult<T>
    ): AiReflectionResult<T> {
        val body = requestBody.getOrElse { error ->
            val detail = error.message ?: "The request could not be built."
            logger.log("invalid-request[$turn] detail=$detail")
            return AiReflectionResult.Failed(detail)
        }
        // Debug builds only. The advice body contains the driver's own sentence.
        logger.log("request[$turn] path=$path body=$body")

        return when (val result = http.post(path, body)) {
            is BackendCallResult.Unavailable -> {
                logger.log(
                    "unavailable[$turn] reason=${result.reason.wireValue} " +
                        "status=${result.statusCode} detail=${result.detail}"
                )
                AiReflectionResult.Unavailable(result.reason)
            }
            is BackendCallResult.ValidationError -> {
                val described = ReflectionBackendJson.describeValidationError(result.body)
                val detail = if (described.isEmpty()) {
                    "The backend rejected the request shape."
                } else {
                    "The backend rejected the request shape ($described)."
                }
                logger.log("invalid-shape[$turn] detail=$detail")
                AiReflectionResult.Failed(detail)
            }
            is BackendCallResult.Success -> try {
                logger.log("response[$turn] body=${result.body}")
                parse(result.body)
            } catch (error: Exception) {
                val detail = error.message ?: "The backend returned an unexpected response."
                logger.log("failed[$turn] detail=$detail")
                AiReflectionResult.Failed(detail)
            }
        }
    }
}

/**
 * Offline stand-in used while the backend's two-turn routes are being deployed, and for UI
 * work without network. Enabled by `ai_reflection_use_mock`.
 *
 * It is deliberately obvious that this is not a real reflection, so a mock answer can never
 * be mistaken for evidence in a demo.
 */
class MockAiReflectionClient : AiReflectionClient {

    override fun askForQuestion(
        tripId: String,
        facts: ReflectionEventFacts
    ): AiReflectionResult<ReflectionQuestion> {
        val duration = "%.1f".format(facts.durationSeconds)
        val limit = facts.speedLimit
        if (limit != null) {
            return AiReflectionResult.Completed(
                ReflectionQuestion(
                    headline = "Sample reflection: the recording suggests a peak of about " +
                        "${limit.peakSpeedKmh.toInt()} km/h against a ${limit.referenceLimitKmh} km/h " +
                        "reference limit, over about $duration seconds.",
                    question = "What was happening around you at that moment?",
                    quickReplies = listOf("I missed the speed sign", "I was keeping up with traffic", "I am not sure")
                )
            )
        }
        val before = (facts.speedBeforeKmh ?: 0.0).toInt()
        val after = (facts.speedAfterKmh ?: 0.0).toInt()
        val slowing = facts.eventType == ReflectionEventType.RAPID_DECELERATION
        val motion = if (slowing) "slowed" else "sped up"
        return AiReflectionResult.Completed(
            ReflectionQuestion(
                headline = "Sample reflection: the recording shows you $motion from about " +
                    "$before to $after km/h in about $duration seconds.",
                question = "What was happening around you at that moment?",
                quickReplies = if (slowing) {
                    listOf("A car cut in front of me", "Traffic stopped suddenly", "I braked for a crossing")
                } else {
                    listOf("I was joining a main road", "I was merging into a gap", "The road opened up")
                }
            )
        )
    }

    override fun askForAdvice(
        tripId: String,
        facts: ReflectionEventFacts,
        question: ReflectionQuestion,
        driverContext: String
    ): AiReflectionResult<ReflectionAdvice> {
        val context = ReflectionBackendJson.sanitiseDriverContext(driverContext)
        if (context.isEmpty()) {
            return AiReflectionResult.Failed("The driver's description was empty.")
        }
        val slowing = facts.eventType == ReflectionEventType.RAPID_DECELERATION
        val speeding = facts.eventType == ReflectionEventType.SPEED_LIMIT_EXCEEDANCE
        val before = facts.speedBeforeKmh ?: 0.0
        return AiReflectionResult.Completed(
            ReflectionAdvice(
                acknowledgement = "Sample reflection: you described the moment as \"$context\".",
                advice = if (speeding) {
                    listOf(
                        "Sample suggestion: glance at the speedometer each time you pass a speed sign.",
                        "Sample suggestion: when the limit drops, ease off before the sign rather than after it."
                    )
                } else if (slowing) {
                    listOf(
                        "Sample suggestion: at ${before.toInt()} km/h a three-second " +
                            "gap is roughly ${(before / 3.6 * 3).toInt()} metres.",
                        "Sample suggestion: start easing off earlier so the brake does less of the work."
                    )
                } else {
                    listOf(
                        "Sample suggestion: build the speed progressively rather than in one push.",
                        "Sample suggestion: pick the gap before you commit to joining."
                    )
                },
                encouragement = "This is offline sample text; connect the backend for real advice."
            )
        )
    }
}
