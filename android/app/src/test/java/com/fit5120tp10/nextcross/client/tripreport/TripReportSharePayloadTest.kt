package com.fit5120tp10.nextcross.client.tripreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportSharePayloadTest {
    @Test
    fun onlyNonSpeedReflectionsAreEligibleForReports() {
        assertTrue(isReportableReflectionEventId("trip-1:RAPID_DECELERATION:1:2"))
        assertFalse(isReportableReflectionEventId("trip-1:SPEED_LIMIT_EXCEEDANCE:E_SPEED_0001"))
    }

    private val report = TripReport(
        tripId = "trip-a",
        plannedTripId = "planned-a",
        startedAtMillis = 1_000L,
        endedAtMillis = 2_000L,
        startingPointLabel = "Home",
        destinationLabel = "School",
        durationSeconds = 1L,
        distanceMeters = 100,
        hardAccelerationCount = 99,
        hardBrakingCount = 99,
        includeDetailedRouteByDefault = true,
        includeReflectionByDefault = false,
        finalAiText = "Try smoother acceleration.",
        createdAtMillis = 2_000L,
        expiresAtMillis = 99_000L
    )

    private val analysedEvents = listOf(
        ShareEventLine(
            kind = ShareEventKind.HARD_BRAKING,
            startMillis = 1_500L,
            durationSeconds = 1.5
        ),
        ShareEventLine(
            kind = ShareEventKind.HARD_ACCELERATION,
            startMillis = 1_800L,
            durationSeconds = 4.0
        )
    )

    @Test
    fun shareTextIncludesOnlySelectedOptionalSections() {
        val withReflection = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(
                includeDetailedRoute = false,
                includeReflectionConversation = true
            ),
            events = analysedEvents
        )
        assertTrue(withReflection.plainText.contains("Home"))
        assertTrue(withReflection.plainText.contains("School"))
        assertTrue(withReflection.plainText.contains("Hard braking"))
        assertTrue(withReflection.plainText.contains("Hard acceleration"))
        assertFalse(withReflection.plainText.contains("speed limit", ignoreCase = true))
        assertTrue(withReflection.plainText.contains("Try smoother acceleration."))
        assertTrue(
            withReflection.plainText.contains(
                "Shared from NextCross. Content is from one completed trip only."
            )
        )
        assertFalse(withReflection.plainText.contains("DETAILED ROUTE"))
        assertFalse(withReflection.plainText.contains("99"))
        assertEquals("trip-a", withReflection.tripId)

        val summaryOnly = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(),
            events = analysedEvents
        )
        assertFalse(summaryOnly.plainText.contains("DETAILED ROUTE"))
        assertFalse(summaryOnly.plainText.contains("Try smoother acceleration."))
    }

    @Test
    fun shareTextUsesAnalysedEventsNotStoredDetectorCounts() {
        val payload = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(),
            events = analysedEvents
        )
        assertEquals(1, analysedEvents.hardBrakingCount())
        assertEquals(1, analysedEvents.hardAccelerationCount())
        assertTrue(payload.plainText.contains("Hard braking"))
        assertTrue(payload.plainText.contains("Hard acceleration"))
        assertFalse(payload.plainText.contains("Above the speed limit"))
        assertFalse(payload.plainText.contains("Hard acceleration: 99"))
        assertFalse(payload.plainText.contains("Hard braking: 99"))
    }

    @Test
    fun emptyEventsShowsNoDrivingEventsMessage() {
        val payload = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(),
            events = emptyList()
        )
        assertTrue(payload.plainText.contains("No acceleration or braking events recorded for this trip."))
    }

    @Test
    fun emptyReportDoesNotMentionSpeedCheck() {
        val payload = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(),
            events = emptyList()
        )
        assertTrue(payload.plainText.contains("No acceleration or braking events recorded for this trip."))
        assertFalse(payload.plainText.contains("speed", ignoreCase = true))
    }

    @Test
    fun detailedRouteUsesMapAttachmentNotCoordinates() {
        val map = TripReportShareAttachment(
            uriString = "content://nextcross/trip-a-route.png",
            mimeType = "image/png",
            displayName = "route-map.png"
        )
        val payload = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(includeDetailedRoute = true),
            events = analysedEvents,
            attachments = listOf(map)
        )
        assertTrue(payload.plainText.contains("Map snapshot attached"))
        assertFalse(payload.plainText.contains("latitude"))
        assertFalse(payload.plainText.contains("-37"))
        assertEquals(1, payload.attachments.size)
    }

    @Test
    fun pdfAttachmentIsNotedInShareText() {
        val pdf = TripReportShareAttachment(
            uriString = "content://nextcross/trip-a-report.pdf",
            mimeType = "application/pdf",
            displayName = "trip-report.pdf"
        )
        val payload = TripReportShareTextBuilder.build(
            report = report,
            selection = TripReportShareSelection(includeDetailedRoute = true),
            events = analysedEvents,
            attachments = listOf(pdf)
        )
        assertTrue(payload.plainText.contains("Full report PDF attached"))
        assertTrue(payload.plainText.contains("Map snapshot could not be attached"))
    }
}
