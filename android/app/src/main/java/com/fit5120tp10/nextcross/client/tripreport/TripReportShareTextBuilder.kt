package com.fit5120tp10.nextcross.client.tripreport

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure text builder so share content can be unit-tested without Room. */
object TripReportShareTextBuilder {
    fun build(
        report: TripReport,
        selection: TripReportShareSelection,
        events: List<ShareEventLine>,
        attachments: List<TripReportShareAttachment> = emptyList()
    ): TripReportSharePayload {
        val lines = mutableListOf<String>()
        lines += "NEXTCROSS TRIP REPORT"
        lines += "====================="
        lines += ""
        lines += "TRIP SUMMARY"
        lines += "------------"
        lines += "From: ${report.startingPointLabel}"
        lines += "To: ${report.destinationLabel}"
        lines += "Started: ${formatInstant(report.startedAtMillis)}"
        lines += "Ended: ${formatInstant(report.endedAtMillis)}"
        report.durationSeconds?.let { lines += "Duration: ${it}s" }
        report.distanceMeters?.let { lines += "Distance: ${it}m" }

        lines += ""
        lines += "DRIVING EVENTS"
        lines += "--------------"
        if (events.isEmpty()) {
            lines += "No acceleration or braking events recorded for this trip."
        } else {
            events.forEach { event ->
                val duration = String.format(Locale.US, "%.1f s", event.durationSeconds)
                lines += "• ${event.label} · ${formatInstant(event.startMillis)} · $duration"
            }
        }
        val hasPdf = attachments.any { it.mimeType.equals("application/pdf", ignoreCase = true) }
        val hasRouteMap = attachments.any { it.mimeType.startsWith("image/", ignoreCase = true) }
        if (hasPdf) lines += "Full report PDF attached."
        if (selection.includeDetailedRoute) {
            lines += ""
            lines += "DETAILED ROUTE"
            lines += "--------------"
            lines += if (hasRouteMap) "Map snapshot attached." else "Map snapshot could not be attached."
        }
        if (selection.includeReflectionConversation) {
            lines += ""
            lines += "AI REFLECTION"
            lines += "-------------"
            val ai = report.finalAiText?.takeIf { it.isNotBlank() }
            lines += if (ai != null) "Reflection: $ai" else "Reflection conversation: not available for this trip."
        }
        lines += ""
        lines += "--------------------------------"
        lines += "Shared from NextCross. Content is from one completed trip only."

        return TripReportSharePayload(
            tripId = report.tripId,
            subject = "NextCross report: ${report.startingPointLabel} → ${report.destinationLabel}",
            plainText = lines.joinToString(separator = "\n"),
            attachments = attachments
        )
    }

    private fun formatInstant(epochMillis: Long): String = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}
