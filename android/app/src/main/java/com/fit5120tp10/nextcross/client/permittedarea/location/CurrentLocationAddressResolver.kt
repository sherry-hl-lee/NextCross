package com.fit5120tp10.nextcross.client.permittedarea.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Resolves the one-shot setup GPS fix before it can become a selected trip origin. */
class CurrentLocationAddressResolver(context: Context) {
    private val geocoder = Geocoder(context.applicationContext)

    suspend fun resolve(point: GeographicPoint): String? = withTimeoutOrNull(ADDRESS_TIMEOUT_MILLIS) {
        if (!Geocoder.isPresent()) return@withTimeoutOrNull null

        try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>?> { continuation ->
                    geocoder.getFromLocation(
                        point.latitude,
                        point.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses)
                            }

                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                runInterruptible(Dispatchers.IO) {
                    geocoder.getFromLocation(point.latitude, point.longitude, 1)
                }
            }

            addresses?.firstNotNullOfOrNull { address ->
                address.getAddressLine(0)?.trim()?.takeIf(String::isNotEmpty)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val ADDRESS_TIMEOUT_MILLIS = 10_000L
    }
}
