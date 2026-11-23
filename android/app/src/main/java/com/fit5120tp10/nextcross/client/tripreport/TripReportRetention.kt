package com.fit5120tp10.nextcross.client.tripreport

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Retention: trip end calendar date is Day 1; report expires at 23:59:59 on Day 10
 * in the device time zone.
 */
object TripReportRetention {
    const val RETENTION_CALENDAR_DAYS = 10

    fun expiresAtMillis(
        tripEndedAtMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        require(tripEndedAtMillis >= 0L) { "Trip end time must not be negative." }
        val endDate = Instant.ofEpochMilli(tripEndedAtMillis).atZone(zoneId).toLocalDate()
        val expiryDate = endDate.plusDays((RETENTION_CALENDAR_DAYS - 1).toLong())
        return expiryDate
            .atTime(LocalTime.of(23, 59, 59))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    fun isExpired(
        expiresAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = nowMillis >= expiresAtMillis
}
