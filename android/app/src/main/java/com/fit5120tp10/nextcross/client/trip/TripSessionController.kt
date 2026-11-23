package com.fit5120tp10.nextcross.client.trip

import java.util.UUID

enum class TripCommandRejectReason {
    /** Start is rejected because a trip is already being recorded. */
    TripAlreadyActive,

    /** End is rejected because there is no active trip. */
    NoActiveTrip
}

sealed class TripCommandResult {
    data class Success(val session: TripSession) : TripCommandResult()
    data class Rejected(val reason: TripCommandRejectReason) : TripCommandResult()
}

/** Holds the current trip only. UUID and clock can be replaced in tests. */
class TripSessionController(
    private val createTripId: () -> String = { UUID.randomUUID().toString() },
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) {
    var state: TripSessionState = TripSessionState.Idle
        private set

    var session: TripSession? = null
        private set

    val isRecording: Boolean
        get() = state == TripSessionState.Recording

    /** Start a new trip from Idle or Ended. Rejected if already recording. */
    fun startTrip(): TripCommandResult {
        if (state == TripSessionState.Recording) {
            return TripCommandResult.Rejected(TripCommandRejectReason.TripAlreadyActive)
        }

        val newSession = TripSession(
            tripId = createTripId(),
            startedAtMillis = currentTimeMillis()
        )
        session = newSession
        state = TripSessionState.Recording
        return TripCommandResult.Success(newSession)
    }

    /** End the active trip. Rejected if not recording. */
    fun endTrip(): TripCommandResult {
        val activeSession = session
        if (state != TripSessionState.Recording || activeSession == null) {
            return TripCommandResult.Rejected(TripCommandRejectReason.NoActiveTrip)
        }

        val finishedSession = activeSession.copy(endedAtMillis = currentTimeMillis())
        session = finishedSession
        state = TripSessionState.Ended
        return TripCommandResult.Success(finishedSession)
    }
}
