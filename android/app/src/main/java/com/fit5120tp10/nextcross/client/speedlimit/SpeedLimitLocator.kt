package com.fit5120tp10.nextcross.client.speedlimit

import android.content.Context
import com.fit5120tp10.nextcross.R

/** Process-local facade for Active Trip speed-limit lookup. */
object SpeedLimitLocator {
    @Volatile
    private var client: SpeedLimitClient? = null

    fun get(context: Context): SpeedLimitClient {
        return client ?: synchronized(this) {
            client ?: createClient(context.applicationContext).also { client = it }
        }
    }

    fun replaceForTests(replacement: SpeedLimitClient?) {
        synchronized(this) {
            client = replacement
        }
    }

    private fun createClient(context: Context): SpeedLimitClient {
        val useMock = context.resources.getBoolean(R.bool.speed_limit_use_mock)
        if (useMock) {
            return MockSpeedLimitClient()
        }
        val endpointUrl = context.getString(R.string.speed_limit_url).trim()
        require(endpointUrl.isNotEmpty()) {
            "speed_limit_url must be set when mock mode is disabled."
        }
        return SpeedLimitHttpClient(endpointUrl)
    }
}
