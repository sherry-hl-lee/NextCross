package com.fit5120tp10.nextcross.client.permittedarea.places

import android.content.Context
import android.util.Log
import com.fit5120tp10.nextcross.BuildConfig
import com.google.android.libraries.places.api.Places

/** Process-local entry point for Places SDK for Android (New). It never starts a request itself. */
object ClientBPlaces {
    private val initializationLock = Any()

    @Volatile
    private var autocomplete: ClientBPlacesAutocomplete? = null

    fun get(context: Context): PlacesResult<ClientBPlacesAutocomplete> {
        autocomplete?.let { existing -> return PlacesResult.Success(existing) }

        val apiKey = BuildConfig.PLACES_API_KEY.trim()
        if (apiKey.isEmpty() || apiKey == DEFAULT_API_KEY) {
            return PlacesResult.Failure(PlacesFailure.MissingApiKey)
        }

        return synchronized(initializationLock) {
            autocomplete?.let { existing -> return@synchronized PlacesResult.Success(existing) }
            try {
                val applicationContext = context.applicationContext
                if (!Places.isInitialized()) {
                    Places.initializeWithNewPlacesApiEnabled(applicationContext, apiKey)
                }
                val usageTracker = PlacesDebugUsageTracker(
                    enabled = BuildConfig.DEBUG,
                    logger = { message -> Log.d(DEBUG_USAGE_LOG_TAG, message) }
                )
                PlacesResult.Success(
                    ClientBPlacesAutocomplete(
                        gatewayFactory = GooglePlacesSessionGatewayFactory(
                            Places.createClient(applicationContext)
                        ),
                        scheduler = MainLooperDebounceScheduler(),
                        usageReporter = usageTracker,
                        debugUsageTracker = usageTracker
                    ).also { autocomplete = it }
                )
            } catch (exception: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(DEBUG_ERROR_LOG_TAG, "Places SDK initialisation failed", exception)
                }
                PlacesResult.Failure(
                    PlacesFailure.ServiceError(statusCode = null, cause = exception)
                )
            }
        }
    }

    private const val DEFAULT_API_KEY = "DEFAULT_API_KEY"
    private const val DEBUG_USAGE_LOG_TAG = "ClientBPlacesUsage"
    private const val DEBUG_ERROR_LOG_TAG = "ClientBPlacesError"
}
