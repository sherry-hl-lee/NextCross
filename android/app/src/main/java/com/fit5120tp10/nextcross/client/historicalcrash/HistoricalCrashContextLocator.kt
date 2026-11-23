package com.fit5120tp10.nextcross.client.historicalcrash

import android.content.Context
import com.fit5120tp10.nextcross.R

/**
 * Process-local facade for historical crash context lookups.
 * Defaults to the mock client until Backend publishes the HTTP endpoint.
 */
object HistoricalCrashContextLocator {
    @Volatile
    private var client: HistoricalCrashContextClient? = null

    fun get(context: Context): HistoricalCrashContextClient {
        return client ?: synchronized(this) {
            client ?: createClient(context.applicationContext).also { client = it }
        }
    }

    /** Test / debug override. Pass null to recreate from resources on next [get]. */
    fun replaceForTests(replacement: HistoricalCrashContextClient?) {
        synchronized(this) {
            client = replacement
        }
    }

    private fun createClient(context: Context): HistoricalCrashContextClient {
        val useMock = context.resources.getBoolean(R.bool.historical_crash_context_use_mock)
        if (useMock) {
            return MockHistoricalCrashContextClient()
        }
        val endpointUrl = context.getString(R.string.historical_crash_context_url).trim()
        require(endpointUrl.isNotEmpty()) {
            "historical_crash_context_url must be set when mock mode is disabled."
        }
        return HistoricalCrashContextHttpClient(endpointUrl)
    }
}
