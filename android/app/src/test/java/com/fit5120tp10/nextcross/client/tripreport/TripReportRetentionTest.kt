package com.fit5120tp10.nextcross.client.tripreport

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportRetentionTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")

    @Test
    fun expiresAtEndOfTenthCalendarDay() {
        // Trip ended 2026-10-01 15:30 local -> Day 1 = Oct 1, Day 10 = Oct 10 23:59:59
        val endedAt = java.time.ZonedDateTime
            .of(2026, 10, 1, 15, 30, 0, 0, melbourne)
            .toInstant()
            .toEpochMilli()

        val expiresAt = TripReportRetention.expiresAtMillis(endedAt, melbourne)
        val expected = java.time.ZonedDateTime
            .of(2026, 10, 10, 23, 59, 59, 0, melbourne)
            .toInstant()
            .toEpochMilli()

        assertEquals(expected, expiresAt)
    }

    @Test
    fun isExpiredUsesInclusiveExpiryInstant() {
        val expiresAt = 1_000L
        assertFalse(TripReportRetention.isExpired(expiresAt, nowMillis = 999L))
        assertTrue(TripReportRetention.isExpired(expiresAt, nowMillis = 1_000L))
        assertTrue(TripReportRetention.isExpired(expiresAt, nowMillis = 1_001L))
    }
}
