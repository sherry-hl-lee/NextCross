package com.fit5120tp10.nextcross.client.aireflection

/** The three stages of one reflection, plus the three ways it can stop. */
sealed interface AiReflectionUiState {
    /** No detected manoeuvre is available to discuss. */
    data object Idle : AiReflectionUiState

    /**
     * An event is selected but nothing has been sent. The first request goes out only when
     * the driver taps "Start reflection": switching events, opening the summary or a late
     * speed-analysis result never sends anything on its own (and never spends AI quota).
     */
    data object NotStarted : AiReflectionUiState

    /** Turn one is in flight. */
    data object AskingQuestion : AiReflectionUiState

    /** The backend asked something; the driver has not answered yet. */
    data class AwaitingDriver(val question: ReflectionQuestion) : AiReflectionUiState

    /** Turn two is in flight with the driver's own words. */
    data class Advising(
        val question: ReflectionQuestion,
        val driverContext: String
    ) : AiReflectionUiState

    /** The complete exchange: numbers, question, the driver's context and the advice. */
    data class Advised(
        val question: ReflectionQuestion,
        val driverContext: String,
        val advice: ReflectionAdvice
    ) : AiReflectionUiState

    /** The backend or its provider could not be reached; events stay visible. */
    data class Unavailable(
        val reason: AiUnavailableReason,
        val question: ReflectionQuestion? = null
    ) : AiReflectionUiState

    /**
     * The backend validated this event and declined to discuss it, for example because the
     * recording quality is too poor. Retrying will not change the answer.
     */
    data class NotAvailableForEvent(val reason: String) : AiReflectionUiState

    /**
     * The backend answered with something this app will not show. [detail] is diagnostic
     * wording for the developer log; it is not shown to the driver.
     */
    data class Failed(
        val detail: String,
        val question: ReflectionQuestion? = null
    ) : AiReflectionUiState
}

/**
 * Short message shown to the driver when a reflection cannot be produced.
 *
 * A driver reads these, not a developer, so they never name a configuration key, a URL, a
 * hosting provider or a wire-level reason code. Only the two causes a driver can actually
 * act on - no connection, and a service that is busy - ask them to do anything. The
 * technical cause is already written to the developer log by the client, which is where it
 * is useful.
 */
fun AiUnavailableReason.userMessage(): String = when (this) {
    AiUnavailableReason.NETWORK_ERROR ->
        "No connection. Check your internet and try again."
    AiUnavailableReason.TIMEOUT ->
        "That took too long to answer. Try again."
    AiUnavailableReason.RATE_LIMITED ->
        "Reflection is busy right now. Try again in a moment."
    // Setup and provider problems are not the driver's to fix, so do not send them looking
    // for a switch they cannot see. Reassure them that the recording itself is intact.
    AiUnavailableReason.NOT_CONFIGURED,
    AiUnavailableReason.PROVIDER_AUTHENTICATION,
    AiUnavailableReason.PROVIDER_UNAVAILABLE,
    AiUnavailableReason.PROVIDER_REQUEST_REJECTED,
    AiUnavailableReason.ENDPOINT_NOT_FOUND ->
        "Reflection is unavailable right now. Your recorded events are unaffected."
}

/** Why this one event cannot be discussed. Never quotes the wire reason back at the driver. */
fun reasonMessageForEvent(reason: String): String = when (reason.trim()) {
    "insufficient_data_quality" ->
        "The recording for this event is not detailed enough to discuss reliably."
    else -> "This event cannot be discussed."
}
