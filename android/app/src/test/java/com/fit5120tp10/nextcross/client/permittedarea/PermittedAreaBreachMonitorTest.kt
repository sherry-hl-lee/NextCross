package com.fit5120tp10.nextcross.client.permittedarea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PermittedAreaBreachMonitorTest {
    private val configuration = PermittedTripConfiguration(
        tripId = "trip-123",
        permittedArea = PermittedArea(
            centre = GeographicPoint(latitude = 0.0, longitude = 0.0),
            radiusKilometres = 10.0
        ),
        parentContact = ParentNotificationContact(
            email = "parent@example.com"
        )
    )

    private val insideLocation = GeographicPoint(latitude = 5.0, longitude = 0.0)
    private val outsideLocation = GeographicPoint(latitude = 15.0, longitude = 0.0)

    @Test
    fun doesNotCheckOrGenerateEventBeforeTripStarts() {
        val monitor = monitor()

        val result = monitor.onLocation(outsideLocation)

        assertSame(PermittedAreaMonitoringResult.TripInactive, result)
        assertFalse(monitor.isActive)
    }

    @Test
    fun locationInsidePermittedAreaDoesNotGenerateEvent() {
        val monitor = monitor()
        monitor.startTrip(configuration)

        val result = monitor.onLocation(insideLocation)
            as PermittedAreaMonitoringResult.Inside

        assertEquals(5.0, result.distanceKilometres, 0.0)
        assertTrue(monitor.isActive)
    }

    @Test
    fun firstInsideToOutsideTransitionGeneratesCompleteEvent() {
        val monitor = monitor(
            eventIds = mutableListOf("breach-1"),
            timestamps = mutableListOf(123_456L)
        )
        monitor.startTrip(configuration)
        monitor.onLocation(insideLocation)

        val result = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside
        val event = result.breachEvent

        assertNotNull(event)
        assertEquals(15.0, result.distanceKilometres, 0.0)
        assertEquals("trip-123", event?.tripId)
        assertEquals("breach-1", event?.eventId)
        assertEquals(AreaBreachEventType.PermittedAreaBreach, event?.eventType)
        assertEquals("PERMITTED_AREA_BREACH", event?.eventType?.wireValue)
        assertEquals(123_456L, event?.occurredAtEpochMillis)
        assertEquals("parent@example.com", event?.parentEmail)
    }

    @Test
    fun breachUsesClientALocationSampleTimestampWhenAvailable() {
        val monitor = monitor(
            eventIds = mutableListOf("breach-1"),
            timestamps = mutableListOf(999_999L)
        )
        monitor.startTrip(configuration)

        val result = monitor.onLocation(
            currentLocation = outsideLocation,
            observedAtEpochMillis = 123_456L
        ) as PermittedAreaMonitoringResult.Outside

        assertEquals(123_456L, result.breachEvent?.occurredAtEpochMillis)
    }

    @Test
    fun remainingOutsideDoesNotGenerateRepeatedEvent() {
        val eventIds = mutableListOf("breach-1", "breach-2")
        val timestamps = mutableListOf(100L, 200L)
        val monitor = monitor(eventIds = eventIds, timestamps = timestamps)
        monitor.startTrip(configuration)

        val firstOutside = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside
        val secondOutside = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside

        assertNotNull(firstOutside.breachEvent)
        assertNull(secondOutside.breachEvent)
        assertEquals(listOf("breach-2"), eventIds)
        assertEquals(listOf(200L), timestamps)
    }

    @Test
    fun returningInsideResetsStateSoNextExitGeneratesNewEvent() {
        val monitor = monitor(
            eventIds = mutableListOf("breach-1", "breach-2"),
            timestamps = mutableListOf(100L, 200L)
        )
        monitor.startTrip(configuration)

        val firstExit = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside
        monitor.onLocation(insideLocation)
        val secondExit = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside

        assertEquals("breach-1", firstExit.breachEvent?.eventId)
        assertEquals("breach-2", secondExit.breachEvent?.eventId)
    }

    @Test
    fun missingLocationDoesNotInferBreachOrChangeExistingOutsideState() {
        val eventIds = mutableListOf("breach-1", "breach-2")
        val monitor = monitor(
            eventIds = eventIds,
            timestamps = mutableListOf(100L, 200L)
        )
        monitor.startTrip(configuration)

        val firstExit = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside
        val missing = monitor.onLocation(currentLocation = null)
        val stillOutside = monitor.onLocation(outsideLocation)
            as PermittedAreaMonitoringResult.Outside

        assertNotNull(firstExit.breachEvent)
        assertSame(PermittedAreaMonitoringResult.LocationUnavailable, missing)
        assertNull(stillOutside.breachEvent)
        assertEquals(listOf("breach-2"), eventIds)
    }

    @Test
    fun endTripStopsCheckingAndSuppressesAllLaterEvents() {
        val eventIds = mutableListOf("breach-1")
        val monitor = monitor(
            eventIds = eventIds,
            timestamps = mutableListOf(100L)
        )
        monitor.startTrip(configuration)

        monitor.endTrip()
        val result = monitor.onLocation(outsideLocation)

        assertSame(PermittedAreaMonitoringResult.TripInactive, result)
        assertFalse(monitor.isActive)
        assertNull(monitor.activeConfiguration)
        assertEquals(listOf("breach-1"), eventIds)
    }

    @Test
    fun cannotReplaceConfigurationWhileTripIsAlreadyActive() {
        val monitor = monitor()
        monitor.startTrip(configuration)

        assertThrows(IllegalStateException::class.java) {
            monitor.startTrip(configuration.copy(tripId = "other-trip"))
        }
        assertEquals("trip-123", monitor.activeConfiguration?.tripId)
    }

    @Test
    fun newTripCanStartAfterPreviousTripEnds() {
        val monitor = monitor()
        monitor.startTrip(configuration)
        monitor.endTrip()

        monitor.startTrip(configuration.copy(tripId = "trip-456"))

        assertTrue(monitor.isActive)
        assertEquals("trip-456", monitor.activeConfiguration?.tripId)
    }

    /** The fake distance is the observed latitude, isolating state-machine tests from Haversine. */
    private fun monitor(
        eventIds: MutableList<String> = mutableListOf("breach-default"),
        timestamps: MutableList<Long> = mutableListOf(1_000L)
    ): PermittedAreaBreachMonitor {
        val checker = PermittedAreaBoundaryChecker(
            distanceCalculator = GeographicDistanceCalculator { _, observed -> observed.latitude }
        )
        return PermittedAreaBreachMonitor(
            boundaryChecker = checker,
            createEventId = { eventIds.removeAt(0) },
            currentTimeMillis = { timestamps.removeAt(0) }
        )
    }
}
