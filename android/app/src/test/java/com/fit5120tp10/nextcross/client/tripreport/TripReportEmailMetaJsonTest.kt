package com.fit5120tp10.nextcross.client.tripreport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportEmailMetaJsonTest {
    private val report = TripReport(
        tripId = "trip-a",
        plannedTripId = null,
        startedAtMillis = 1_728_000_000_000L,
        endedAtMillis = 1_728_003_600_000L,
        startingPointLabel = "Home",
        destinationLabel = "School",
        durationSeconds = 3600L,
        distanceMeters = 12_000,
        hardAccelerationCount = 99,
        hardBrakingCount = 99,
        includeDetailedRouteByDefault = true,
        includeReflectionByDefault = false,
        finalAiText = null,
        createdAtMillis = 1_728_003_600_000L,
        expiresAtMillis = 1_728_868_000_000L
    )

    @Test
    fun metaUsesCamelCaseAndNullPlannedTripId() {
        val events = listOf(
            ShareEventLine(
                kind = ShareEventKind.HARD_ACCELERATION,
                startMillis = 1_728_000_100_000L,
                durationSeconds = 1.2
            ),
            ShareEventLine(
                kind = ShareEventKind.HARD_BRAKING,
                startMillis = 1_728_000_200_000L,
                durationSeconds = 3.0
            )
        )
        val json = TripReportEmailMetaJson.build(
            report = report,
            parentEmail = " parent@example.com ",
            selection = TripReportShareSelection(
                includeDetailedRoute = false,
                includeReflectionConversation = true
            ),
            events = events,
            subject = "NextCross report: Home → School",
            plainText = "NextCross trip report\nFrom: Home",
            clientRequestId = "00000000-0000-4000-8000-000000000001"
        )
        val root = JSONObject(json)
        assertEquals("trip-a", root.getString("tripId"))
        assertTrue(root.isNull("plannedTripId"))
        assertEquals("parent@example.com", root.getString("parentEmail"))
        assertEquals(false, root.getBoolean("includeDetailedRoute"))
        assertEquals(true, root.getBoolean("includeReflectionConversation"))
        assertEquals(1_728_000_000_000L, root.getLong("startedAtEpochMillis"))
        assertEquals(1, root.getInt("hardAccelerationCount"))
        assertEquals(1, root.getInt("hardBrakingCount"))
        assertFalse(json.contains("parent_email"))
    }
}
