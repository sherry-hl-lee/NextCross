package com.fit5120tp10.nextcross.client.permittedarea.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import java.util.concurrent.atomic.AtomicBoolean

sealed class SetupLocationResult {
    data class Success(val point: GeographicPoint) : SetupLocationResult()
    data class Unavailable(val reason: String) : SetupLocationResult()
}

/** Cancels an outstanding one-shot request. Cancellation completes it as unavailable once. */
fun interface SetupLocationRequest {
    fun cancel()
}

interface SetupLocationProvider {
    fun captureCurrentLocation(
        onResult: (SetupLocationResult) -> Unit
    ): SetupLocationRequest
}

/**
 * Captures one GPS fix for trip setup and then unregisters immediately.
 *
 * The UI remains responsible for requesting runtime location permission. A missing permission,
 * disabled provider, timeout, cancellation, or invalid fix produces [SetupLocationResult.Unavailable]
 * and never starts a continuous tracking pipeline.
 */
class AndroidSetupLocationProvider(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : SetupLocationProvider {
    private val locationManager = context.applicationContext
        .getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val callbackHandler = Handler(Looper.getMainLooper())

    init {
        require(timeoutMillis > 0L) { "Location timeout must be positive." }
    }

    override fun captureCurrentLocation(
        onResult: (SetupLocationResult) -> Unit
    ): SetupLocationRequest {
        return PendingLocationRequest(onResult).also(PendingLocationRequest::start)
    }

    private inner class PendingLocationRequest(
        private val onResult: (SetupLocationResult) -> Unit
    ) : SetupLocationRequest, LocationListener, Runnable {
        private val completed = AtomicBoolean(false)

        fun start() {
            if (!callbackHandler.postDelayed(this, timeoutMillis)) {
                complete(SetupLocationResult.Unavailable(REASON_CALLBACK_LOOPER_UNAVAILABLE))
                return
            }

            try {
                if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    complete(SetupLocationResult.Unavailable(REASON_GPS_DISABLED))
                    return
                }
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    this,
                    callbackHandler.looper
                )
            } catch (_: SecurityException) {
                complete(SetupLocationResult.Unavailable(REASON_PERMISSION_NOT_GRANTED))
            } catch (_: IllegalArgumentException) {
                complete(SetupLocationResult.Unavailable(REASON_GPS_UNAVAILABLE))
            } catch (_: RuntimeException) {
                complete(SetupLocationResult.Unavailable(REASON_GPS_UNAVAILABLE))
            }
        }

        override fun onLocationChanged(location: Location) {
            val result = try {
                SetupLocationResult.Success(
                    GeographicPoint(
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                )
            } catch (_: IllegalArgumentException) {
                SetupLocationResult.Unavailable(REASON_INVALID_LOCATION)
            }
            complete(result)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                complete(SetupLocationResult.Unavailable(REASON_GPS_DISABLED))
            }
        }

        override fun run() {
            complete(SetupLocationResult.Unavailable(REASON_TIMEOUT))
        }

        override fun cancel() {
            complete(SetupLocationResult.Unavailable(REASON_CANCELLED))
        }

        private fun complete(result: SetupLocationResult) {
            if (!completed.compareAndSet(false, true)) {
                return
            }

            callbackHandler.removeCallbacks(this)
            try {
                locationManager.removeUpdates(this)
            } catch (_: SecurityException) {
                // Permission can be revoked while the one-shot request is outstanding.
            } catch (_: RuntimeException) {
                // Completion must still be delivered exactly once if provider cleanup fails.
            }
            onResult(result)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val REASON_GPS_DISABLED = "gps_provider_disabled"
        const val REASON_GPS_UNAVAILABLE = "gps_provider_unavailable"
        const val REASON_PERMISSION_NOT_GRANTED = "location_permission_not_granted"
        const val REASON_TIMEOUT = "location_request_timed_out"
        const val REASON_CANCELLED = "location_request_cancelled"
        const val REASON_INVALID_LOCATION = "invalid_location_fix"
        const val REASON_CALLBACK_LOOPER_UNAVAILABLE = "callback_looper_unavailable"
    }
}
