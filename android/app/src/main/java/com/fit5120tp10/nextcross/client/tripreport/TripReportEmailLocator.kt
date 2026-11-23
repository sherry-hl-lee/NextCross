package com.fit5120tp10.nextcross.client.tripreport

import android.content.Context
import com.fit5120tp10.nextcross.R
import com.fit5120tp10.nextcross.client.network.InternetConnection

/** Process-local Path B email sender wired to I2 `trip-reports/email`. */
object TripReportEmailLocator {
    @Volatile
    private var client: TripReportEmailClient? = null

    fun get(context: Context): TripReportEmailClient {
        return client ?: synchronized(this) {
            client ?: create(context.applicationContext).also { client = it }
        }
    }

    fun replaceForTests(replacement: TripReportEmailClient?) {
        synchronized(this) {
            client = replacement
        }
    }

    private fun create(context: Context): TripReportEmailClient {
        val enabled = context.resources.getBoolean(R.bool.prototype_backend_trip_report_email_enabled)
        val endpointUrl = context.getString(R.string.prototype_backend_trip_report_email_url).trim()
        val http = TripReportEmailHttpClient(endpointUrl)
        return TripReportEmailClient(
            repository = TripReportLocator.get(context),
            http = http,
            contentResolver = context.contentResolver,
            sendStore = TripReportEmailSendStore(context),
            enabled = enabled && endpointUrl.isNotBlank(),
            isInternetAvailable = { InternetConnection.isAvailable(context) }
        )
    }
}
