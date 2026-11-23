package com.fit5120tp10.nextcross.client.permittedarea.transport

import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEvent
import com.fit5120tp10.nextcross.client.permittedarea.AreaBreachEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventRequestTest {
    @Test
    fun areaBreachEventMapsToExactBackendContract() {
        val request = NotificationEventRequest.from(event())

        assertEquals("planned-trip-001", request.tripId)
        assertEquals("breach-001", request.eventId)
        assertEquals("PERMITTED_AREA_BREACH", request.eventType)
        assertEquals(1_788_231_600_000L, request.occurredAtEpochMillis)
        assertEquals("parent@example.com", request.parentEmail)
        assertEquals(
            "{\"tripId\":\"planned-trip-001\",\"eventId\":\"breach-001\"," +
                "\"eventType\":\"PERMITTED_AREA_BREACH\"," +
                "\"occurredAtEpochMillis\":1788231600000," +
                "\"parentEmail\":\"parent@example.com\"}",
            request.toJson()
        )
    }

    @Test
    fun jsonEscapesUserSuppliedStringsAndContainsNoLocationFields() {
        val request = NotificationEventRequest.from(
            event(
                tripId = "trip\"\\\n",
                parentEmail = "parent+test@example.com"
            )
        )

        val json = request.toJson()
        assertTrue(json.contains("\"tripId\":\"trip\\\"\\\\\\n\""))
        assertFalse(json.contains("latitude"))
        assertFalse(json.contains("longitude"))
        assertFalse(json.contains("radius"))
        assertFalse(json.contains("parentMobileNumber"))
    }

    private fun event(
        tripId: String = "planned-trip-001",
        parentEmail: String = "parent@example.com"
    ): AreaBreachEvent {
        return AreaBreachEvent(
            tripId = tripId,
            eventId = "breach-001",
            eventType = AreaBreachEventType.PermittedAreaBreach,
            occurredAtEpochMillis = 1_788_231_600_000L,
            parentEmail = parentEmail
        )
    }
}
