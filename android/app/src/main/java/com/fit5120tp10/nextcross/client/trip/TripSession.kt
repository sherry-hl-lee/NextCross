package com.fit5120tp10.nextcross.client.trip

/** One trip. [endedAtMillis] is null until End Trip. */
data class TripSession(
    val tripId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null
) {
    /** True until End Trip. Use [TripSessionController.isRecording] in the UI. */
    val isActive: Boolean
        get() = endedAtMillis == null
}
