package com.fit5120tp10.nextcross.client.permittedarea.places

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceAutocompleteSessionTest {
    @Test
    fun queryBelowThreeCharactersNeverSchedulesOrCallsPlaces() {
        val fixture = Fixture()
        var result: PlacesResult<List<PlaceSuggestion>>? = null

        fixture.session.updateQuery("ab") { result = it }

        assertEquals(PlacesResult.Success(emptyList<PlaceSuggestion>()), result)
        assertEquals(0, fixture.scheduler.operations.size)
        assertTrue(fixture.gateway.queries.isEmpty())
        assertEquals(1L, fixture.autocomplete.debugUsageSnapshot()!!.belowMinimumQueriesSuppressed)
    }

    @Test
    fun debounceKeepsLatestQueryAndReturnsAtMostThreeSuggestions() {
        val fixture = Fixture()
        var result: PlacesResult<List<PlaceSuggestion>>? = null

        fixture.session.updateQuery("Mel") { result = it }
        fixture.session.updateQuery("Melb") { result = it }
        fixture.scheduler.runPending()

        assertEquals(listOf("Melb"), fixture.gateway.queries)
        fixture.gateway.completeSuggestions((1..5).map(::suggestion))

        val suggestions = (result as PlacesResult.Success).value
        assertEquals(3, suggestions.size)
        assertEquals(listOf("place-1", "place-2", "place-3"), suggestions.map { it.placeId })
        assertEquals(1L, fixture.autocomplete.debugUsageSnapshot()!!.autocompleteRequests)
    }

    @Test
    fun duplicateQueryUsesCachedResultWithoutAnotherRequest() {
        val fixture = Fixture()
        var firstResult: PlacesResult<List<PlaceSuggestion>>? = null
        var repeatedResult: PlacesResult<List<PlaceSuggestion>>? = null

        fixture.session.updateQuery("Melbourne") { firstResult = it }
        fixture.scheduler.runPending()
        fixture.gateway.completeSuggestions(listOf(suggestion(1)))
        fixture.session.updateQuery("Melbourne") { repeatedResult = it }

        assertEquals(firstResult, repeatedResult)
        assertEquals(listOf("Melbourne"), fixture.gateway.queries)
        assertEquals(1L, fixture.autocomplete.debugUsageSnapshot()!!.duplicateQueriesSuppressed)
    }

    @Test
    fun selectionIsRequestedOnceAndCompletingItEndsTheSession() {
        val fixture = Fixture()
        val currentSuggestion = suggestion(1)
        var selectedResult: PlacesResult<SelectedPlace>? = null
        var duplicateResult: PlacesResult<SelectedPlace>? = null

        fixture.session.updateQuery("Melbourne") {}
        fixture.scheduler.runPending()
        fixture.gateway.completeSuggestions(listOf(currentSuggestion))
        fixture.session.select(currentSuggestion) { selectedResult = it }
        fixture.session.select(currentSuggestion) { duplicateResult = it }

        assertEquals(listOf(currentSuggestion.placeId), fixture.gateway.fetchedPlaceIds)
        assertEquals(
            PlacesResult.Failure(PlacesFailure.SelectionAlreadyStarted),
            duplicateResult
        )

        val selectedPlace = SelectedPlace(
            displayName = "Melbourne Central",
            placeId = currentSuggestion.placeId,
            point = GeographicPoint(latitude = -37.8102, longitude = 144.9628)
        )
        fixture.gateway.completeSelection(selectedPlace)

        assertEquals(PlacesResult.Success(selectedPlace), selectedResult)
        assertEquals(1, fixture.gateway.closeCount)
        assertEquals(1L, fixture.autocomplete.debugUsageSnapshot()!!.placeDetailsRequests)
        assertEquals(1L, fixture.autocomplete.debugUsageSnapshot()!!.duplicateSelectionsSuppressed)
        assertEquals(1L, fixture.autocomplete.debugUsageSnapshot()!!.sessionsEnded)
    }

    @Test
    fun eachInputFlowCreatesItsOwnGatewaySession() {
        val scheduler = ManualDebounceScheduler()
        val tracker = PlacesDebugUsageTracker(enabled = true, logger = {})
        val gateways = mutableListOf<FakePlacesSessionGateway>()
        val autocomplete = ClientBPlacesAutocomplete(
            gatewayFactory = PlacesSessionGatewayFactory {
                FakePlacesSessionGateway().also(gateways::add)
            },
            scheduler = scheduler,
            usageReporter = tracker,
            debugUsageTracker = tracker
        )

        autocomplete.newSession()
        autocomplete.newSession()

        assertEquals(2, gateways.size)
        assertEquals(2L, autocomplete.debugUsageSnapshot()!!.sessionsStarted)
    }

    private class Fixture {
        val scheduler = ManualDebounceScheduler()
        val gateway = FakePlacesSessionGateway()
        private val tracker = PlacesDebugUsageTracker(enabled = true, logger = {})
        val autocomplete = ClientBPlacesAutocomplete(
            gatewayFactory = PlacesSessionGatewayFactory { gateway },
            scheduler = scheduler,
            usageReporter = tracker,
            debugUsageTracker = tracker
        )
        val session = autocomplete.newSession()
    }

    private class ManualDebounceScheduler : DebounceScheduler {
        data class ScheduledOperation(
            val operation: () -> Unit,
            var cancelled: Boolean = false
        )

        val operations = mutableListOf<ScheduledOperation>()

        override fun schedule(delayMillis: Long, operation: () -> Unit): PendingOperation {
            assertEquals(ClientBPlacesAutocomplete.DEFAULT_DEBOUNCE_MILLIS, delayMillis)
            val scheduled = ScheduledOperation(operation)
            operations += scheduled
            return PendingOperation { scheduled.cancelled = true }
        }

        fun runPending() {
            operations.toList().forEach { scheduled ->
                if (!scheduled.cancelled) scheduled.operation()
            }
        }
    }

    private class FakePlacesSessionGateway : PlacesSessionGateway {
        val queries = mutableListOf<String>()
        val fetchedPlaceIds = mutableListOf<String>()
        var closeCount = 0
        private var suggestionsCallback:
            ((PlacesResult<List<PlaceSuggestion>>) -> Unit)? = null
        private var selectionCallback: ((PlacesResult<SelectedPlace>) -> Unit)? = null

        override fun findSuggestions(
            query: String,
            onResult: (PlacesResult<List<PlaceSuggestion>>) -> Unit
        ): PendingOperation {
            queries += query
            suggestionsCallback = onResult
            return PendingOperation {}
        }

        override fun fetchSelectedPlace(
            placeId: String,
            onResult: (PlacesResult<SelectedPlace>) -> Unit
        ): PendingOperation {
            fetchedPlaceIds += placeId
            selectionCallback = onResult
            return PendingOperation {}
        }

        override fun close() {
            closeCount += 1
        }

        fun completeSuggestions(suggestions: List<PlaceSuggestion>) {
            suggestionsCallback!!(PlacesResult.Success(suggestions))
        }

        fun completeSelection(place: SelectedPlace) {
            selectionCallback!!(PlacesResult.Success(place))
        }
    }

    private companion object {
        fun suggestion(index: Int): PlaceSuggestion {
            return PlaceSuggestion(
                placeId = "place-$index",
                primaryText = "Place $index",
                secondaryText = "Victoria"
            )
        }
    }
}
