package com.fit5120tp10.nextcross.client.speedlimit

/** Offline fixture: never invents a usable limit (safe for demos without backend). */
class MockSpeedLimitClient(
    private val context: SpeedLimitContext = SpeedLimitContext(
        limitKmh = null,
        status = SpeedLimitStatus.Unknown,
        reason = "mock_unavailable"
    )
) : SpeedLimitClient {
    override fun lookup(request: SpeedLimitLookupRequest): SpeedLimitLookupResult {
        return SpeedLimitLookupResult.Success(context)
    }
}
