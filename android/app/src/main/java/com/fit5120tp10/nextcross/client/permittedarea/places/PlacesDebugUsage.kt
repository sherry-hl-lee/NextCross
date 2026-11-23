package com.fit5120tp10.nextcross.client.permittedarea.places

import java.util.concurrent.atomic.AtomicLong

/** Request-volume counters exposed only by debug builds. They never contain query or place data. */
data class PlacesDebugUsageSnapshot(
    val sessionsStarted: Long,
    val sessionsEnded: Long,
    val autocompleteRequests: Long,
    val placeDetailsRequests: Long,
    val belowMinimumQueriesSuppressed: Long,
    val duplicateQueriesSuppressed: Long,
    val duplicateSelectionsSuppressed: Long
)

internal interface PlacesUsageReporter {
    fun onSessionStarted()
    fun onSessionEnded()
    fun onAutocompleteRequest()
    fun onPlaceDetailsRequest()
    fun onBelowMinimumQuerySuppressed()
    fun onDuplicateQuerySuppressed()
    fun onDuplicateSelectionSuppressed()
}

internal class PlacesDebugUsageTracker(
    private val enabled: Boolean,
    private val logger: (String) -> Unit
) : PlacesUsageReporter {
    private val sessionsStarted = AtomicLong()
    private val sessionsEnded = AtomicLong()
    private val autocompleteRequests = AtomicLong()
    private val placeDetailsRequests = AtomicLong()
    private val belowMinimumQueriesSuppressed = AtomicLong()
    private val duplicateQueriesSuppressed = AtomicLong()
    private val duplicateSelectionsSuppressed = AtomicLong()

    fun snapshot(): PlacesDebugUsageSnapshot? {
        if (!enabled) return null
        return PlacesDebugUsageSnapshot(
            sessionsStarted = sessionsStarted.get(),
            sessionsEnded = sessionsEnded.get(),
            autocompleteRequests = autocompleteRequests.get(),
            placeDetailsRequests = placeDetailsRequests.get(),
            belowMinimumQueriesSuppressed = belowMinimumQueriesSuppressed.get(),
            duplicateQueriesSuppressed = duplicateQueriesSuppressed.get(),
            duplicateSelectionsSuppressed = duplicateSelectionsSuppressed.get()
        )
    }

    override fun onSessionStarted() {
        record(sessionsStarted, "autocomplete session started")
    }

    override fun onSessionEnded() {
        record(sessionsEnded, "autocomplete session ended")
    }

    override fun onAutocompleteRequest() {
        record(autocompleteRequests, "Autocomplete (New) request issued")
    }

    override fun onPlaceDetailsRequest() {
        record(placeDetailsRequests, "Place Details (New) request issued")
    }

    override fun onBelowMinimumQuerySuppressed() {
        record(belowMinimumQueriesSuppressed, "query below minimum suppressed")
    }

    override fun onDuplicateQuerySuppressed() {
        record(duplicateQueriesSuppressed, "duplicate query suppressed")
    }

    override fun onDuplicateSelectionSuppressed() {
        record(duplicateSelectionsSuppressed, "duplicate selection suppressed")
    }

    private fun record(counter: AtomicLong, event: String) {
        if (!enabled) return
        val value = counter.incrementAndGet()
        logger("$event; counter=$value")
    }
}
