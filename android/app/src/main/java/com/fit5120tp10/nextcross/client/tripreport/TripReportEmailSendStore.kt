package com.fit5120tp10.nextcross.client.tripreport

import android.content.Context

/** Local guard for the one-email-per-trip prototype rule. */
class TripReportEmailSendStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun status(tripId: String): Status = when (preferences.getString(tripId, null)) {
        ACCEPTED -> Status.Accepted
        PENDING -> Status.Pending
        else -> Status.NotSent
    }

    /** Persist before any network request. A failed write must never permit an unguarded send. */
    @Synchronized
    fun claim(tripId: String): Boolean {
        if (status(tripId) != Status.NotSent) return false
        return preferences.edit().putString(tripId, PENDING).commit()
    }

    @Synchronized
    fun markAccepted(tripId: String) {
        preferences.edit().putString(tripId, ACCEPTED).commit()
    }

    @Synchronized
    fun releaseKnownFailure(tripId: String) {
        if (status(tripId) == Status.Pending) preferences.edit().remove(tripId).commit()
    }

    enum class Status { NotSent, Pending, Accepted }

    private companion object {
        const val PREFERENCES_NAME = "nextcross_trip_report_email_sends"
        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
    }
}
