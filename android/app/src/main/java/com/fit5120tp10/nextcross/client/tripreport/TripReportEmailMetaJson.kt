package com.fit5120tp10.nextcross.client.tripreport

import org.json.JSONObject

/** Builds the camelCase `meta` JSON part for POST /api/v1/trip-reports/email. */
object TripReportEmailMetaJson {
    fun build(
        report: TripReport,
        parentEmail: String,
        selection: TripReportShareSelection,
        events: List<ShareEventLine>,
        subject: String,
        plainText: String,
        clientRequestId: String
    ): String {
        val root = JSONObject()
        root.put("tripId", report.tripId)
        if (report.plannedTripId.isNullOrBlank()) {
            root.put("plannedTripId", JSONObject.NULL)
        } else {
            root.put("plannedTripId", report.plannedTripId)
        }
        root.put("parentEmail", parentEmail.trim())
        root.put("subject", subject)
        root.put("plainText", plainText)
        root.put("includeDetailedRoute", selection.includeDetailedRoute)
        root.put("includeReflectionConversation", selection.includeReflectionConversation)
        root.put("startedAtEpochMillis", report.startedAtMillis)
        root.put("endedAtEpochMillis", report.endedAtMillis)
        root.put("startingPointLabel", report.startingPointLabel)
        root.put("destinationLabel", report.destinationLabel)
        root.put("hardAccelerationCount", events.hardAccelerationCount())
        root.put("hardBrakingCount", events.hardBrakingCount())
        root.put("clientRequestId", clientRequestId)
        return root.toString()
    }
}
