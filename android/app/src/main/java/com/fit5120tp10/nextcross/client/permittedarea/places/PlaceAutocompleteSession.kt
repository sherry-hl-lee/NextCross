package com.fit5120tp10.nextcross.client.permittedarea.places

import android.os.Handler
import android.os.Looper
import java.util.concurrent.RejectedExecutionException

/** One token-scoped typing-to-selection flow. Close it when the user leaves or clears the input. */
interface PlaceAutocompleteSession : AutoCloseable {
    /**
     * Debounces qualifying input and returns at most three suggestions. Calls with the same query
     * reuse the pending or cached result and never issue another Places request.
     */
    fun updateQuery(
        query: String,
        onResult: (PlacesResult<List<PlaceSuggestion>>) -> Unit
    )

    /** Fetches only display name, Place ID and location, once, then ends this session. */
    fun select(
        suggestion: PlaceSuggestion,
        onResult: (PlacesResult<SelectedPlace>) -> Unit
    )
}

/** Creates sessions explicitly; construction or Compose recomposition never starts a request. */
class ClientBPlacesAutocomplete internal constructor(
    private val gatewayFactory: PlacesSessionGatewayFactory,
    private val scheduler: DebounceScheduler,
    private val usageReporter: PlacesUsageReporter,
    private val debugUsageTracker: PlacesDebugUsageTracker
) {
    fun newSession(): PlaceAutocompleteSession {
        usageReporter.onSessionStarted()
        return DefaultPlaceAutocompleteSession(
            gateway = gatewayFactory.create(),
            scheduler = scheduler,
            usageReporter = usageReporter
        )
    }

    fun debugUsageSnapshot(): PlacesDebugUsageSnapshot? = debugUsageTracker.snapshot()

    companion object {
        const val MINIMUM_QUERY_CHARACTERS = 3
        const val MAXIMUM_SUGGESTIONS = 3
        const val DEFAULT_DEBOUNCE_MILLIS = 350L
    }
}

internal fun interface PendingOperation {
    fun cancel()
}

internal fun interface DebounceScheduler {
    fun schedule(delayMillis: Long, operation: () -> Unit): PendingOperation
}

internal class MainLooperDebounceScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : DebounceScheduler {
    override fun schedule(delayMillis: Long, operation: () -> Unit): PendingOperation {
        val runnable = Runnable(operation)
        if (!handler.postDelayed(runnable, delayMillis)) {
            throw RejectedExecutionException("The main looper rejected a Places debounce task.")
        }
        return PendingOperation { handler.removeCallbacks(runnable) }
    }
}

internal fun interface PlacesSessionGatewayFactory {
    fun create(): PlacesSessionGateway
}

internal interface PlacesSessionGateway : AutoCloseable {
    fun findSuggestions(
        query: String,
        onResult: (PlacesResult<List<PlaceSuggestion>>) -> Unit
    ): PendingOperation

    fun fetchSelectedPlace(
        placeId: String,
        onResult: (PlacesResult<SelectedPlace>) -> Unit
    ): PendingOperation
}

private class DefaultPlaceAutocompleteSession(
    private val gateway: PlacesSessionGateway,
    private val scheduler: DebounceScheduler,
    private val usageReporter: PlacesUsageReporter
) : PlaceAutocompleteSession {
    private val stateLock = Any()
    private var isClosed = false
    private var selectionStarted = false
    private var revision = 0L
    private var lastQuery: String? = null
    private var cachedSuggestions: PlacesResult<List<PlaceSuggestion>>? = null
    private var latestQueryCallback: ((PlacesResult<List<PlaceSuggestion>>) -> Unit)? = null
    private var pendingDebounce: PendingOperation? = null
    private var pendingLookup: PendingOperation? = null
    private var pendingSelection: PendingOperation? = null

    override fun updateQuery(
        query: String,
        onResult: (PlacesResult<List<PlaceSuggestion>>) -> Unit
    ) {
        val normalizedQuery = query.trim()
        var immediateResult: PlacesResult<List<PlaceSuggestion>>? = null

        synchronized(stateLock) {
            when {
                isClosed -> immediateResult = PlacesResult.Failure(PlacesFailure.SessionClosed)
                selectionStarted -> immediateResult =
                    PlacesResult.Failure(PlacesFailure.SelectionAlreadyStarted)
                normalizedQuery.codePointCount(0, normalizedQuery.length) <
                    ClientBPlacesAutocomplete.MINIMUM_QUERY_CHARACTERS -> {
                    cancelQueryWorkLocked()
                    lastQuery = null
                    cachedSuggestions = null
                    latestQueryCallback = null
                    usageReporter.onBelowMinimumQuerySuppressed()
                    immediateResult = PlacesResult.Success(emptyList())
                }
                normalizedQuery == lastQuery -> {
                    usageReporter.onDuplicateQuerySuppressed()
                    latestQueryCallback = onResult
                    immediateResult = cachedSuggestions
                }
                else -> {
                    cancelQueryWorkLocked()
                    lastQuery = normalizedQuery
                    cachedSuggestions = null
                    latestQueryCallback = onResult
                    val requestRevision = revision
                    try {
                        pendingDebounce = scheduler.schedule(
                            ClientBPlacesAutocomplete.DEFAULT_DEBOUNCE_MILLIS
                        ) {
                            startSuggestionLookup(normalizedQuery, requestRevision)
                        }
                    } catch (exception: Exception) {
                        lastQuery = null
                        latestQueryCallback = null
                        immediateResult = serviceFailure(exception)
                    }
                }
            }
        }

        immediateResult?.let(onResult)
    }

    override fun select(
        suggestion: PlaceSuggestion,
        onResult: (PlacesResult<SelectedPlace>) -> Unit
    ) {
        var immediateFailure: PlacesResult<SelectedPlace>? = null

        synchronized(stateLock) {
            when {
                isClosed -> immediateFailure = PlacesResult.Failure(PlacesFailure.SessionClosed)
                selectionStarted -> {
                    usageReporter.onDuplicateSelectionSuppressed()
                    immediateFailure =
                        PlacesResult.Failure(PlacesFailure.SelectionAlreadyStarted)
                }
                !isCurrentSuggestionLocked(suggestion) -> immediateFailure =
                    PlacesResult.Failure(PlacesFailure.SuggestionIsNotCurrent)
                else -> {
                    selectionStarted = true
                    cancelQueryWorkLocked()
                }
            }
        }

        if (immediateFailure != null) {
            onResult(immediateFailure!!)
            return
        }

        usageReporter.onPlaceDetailsRequest()
        val selectionOperation = try {
            gateway.fetchSelectedPlace(suggestion.placeId) { result ->
                completeSelection(result, onResult)
            }
        } catch (exception: Exception) {
            completeSelection(serviceFailure(exception), onResult)
            return
        }

        synchronized(stateLock) {
            if (isClosed) {
                selectionOperation.cancel()
            } else {
                pendingSelection = selectionOperation
            }
        }
    }

    override fun close() {
        val shouldFinish = synchronized(stateLock) {
            if (isClosed) {
                false
            } else {
                isClosed = true
                cancelQueryWorkLocked()
                pendingSelection?.cancel()
                pendingSelection = null
                true
            }
        }
        if (shouldFinish) {
            gateway.close()
            usageReporter.onSessionEnded()
        }
    }

    private fun startSuggestionLookup(query: String, requestRevision: Long) {
        val shouldStart = synchronized(stateLock) {
            pendingDebounce = null
            !isClosed && !selectionStarted && revision == requestRevision && lastQuery == query
        }
        if (!shouldStart) return

        usageReporter.onAutocompleteRequest()
        val lookupOperation = try {
            gateway.findSuggestions(query) { result ->
                completeSuggestionLookup(requestRevision, result)
            }
        } catch (exception: Exception) {
            completeSuggestionLookup(requestRevision, serviceFailure(exception))
            return
        }

        synchronized(stateLock) {
            if (
                isClosed || selectionStarted || revision != requestRevision ||
                cachedSuggestions != null
            ) {
                lookupOperation.cancel()
            } else {
                pendingLookup = lookupOperation
            }
        }
    }

    private fun completeSuggestionLookup(
        requestRevision: Long,
        result: PlacesResult<List<PlaceSuggestion>>
    ) {
        var callback: ((PlacesResult<List<PlaceSuggestion>>) -> Unit)? = null
        var deliveredResult: PlacesResult<List<PlaceSuggestion>>? = null

        synchronized(stateLock) {
            if (isClosed || selectionStarted || revision != requestRevision) return
            pendingLookup = null
            deliveredResult = when (result) {
                is PlacesResult.Success -> PlacesResult.Success(
                    result.value.take(ClientBPlacesAutocomplete.MAXIMUM_SUGGESTIONS)
                )
                is PlacesResult.Failure -> result
            }
            cachedSuggestions = deliveredResult
            callback = latestQueryCallback
        }

        if (callback != null && deliveredResult != null) {
            callback!!(deliveredResult!!)
        }
    }

    private fun completeSelection(
        result: PlacesResult<SelectedPlace>,
        onResult: (PlacesResult<SelectedPlace>) -> Unit
    ) {
        val shouldDeliver = synchronized(stateLock) {
            if (isClosed) {
                false
            } else {
                isClosed = true
                pendingSelection = null
                true
            }
        }
        if (shouldDeliver) {
            gateway.close()
            usageReporter.onSessionEnded()
            onResult(result)
        }
    }

    private fun isCurrentSuggestionLocked(suggestion: PlaceSuggestion): Boolean {
        val current = cachedSuggestions as? PlacesResult.Success ?: return false
        return current.value.any { candidate -> candidate.placeId == suggestion.placeId }
    }

    private fun cancelQueryWorkLocked() {
        revision += 1
        pendingDebounce?.cancel()
        pendingDebounce = null
        pendingLookup?.cancel()
        pendingLookup = null
    }

    private fun <T> serviceFailure(exception: Exception): PlacesResult<T> {
        return PlacesResult.Failure(
            PlacesFailure.ServiceError(statusCode = null, cause = exception)
        )
    }
}
