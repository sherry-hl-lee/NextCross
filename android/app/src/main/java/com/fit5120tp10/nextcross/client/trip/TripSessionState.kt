package com.fit5120tp10.nextcross.client.trip

/** Idle: not started. Recording: capturing. Ended: finished, session kept for the summary. */
enum class TripSessionState {
    Idle,
    Recording,
    Ended
}
