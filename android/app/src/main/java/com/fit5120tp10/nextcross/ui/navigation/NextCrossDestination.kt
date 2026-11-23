package com.fit5120tp10.nextcross.ui.navigation

import android.net.Uri

object NextCrossDestination {
    const val Splash = "splash"
    const val Home = "home"
    const val TripSetup = "tripSetup"
    const val ReviewTripDetails = "reviewTripDetails"
    const val PreTripMap = "preTripMap"
    const val ActiveTrip = "activeTrip"
    const val TripSummary = "tripSummary"
    const val ReportHistory = "reportHistory"
    const val TripReportArgument = "tripId"
    const val TripReportPattern = "tripReport/{$TripReportArgument}"

    fun tripReport(tripId: String): String = "tripReport/${Uri.encode(tripId)}"
}
