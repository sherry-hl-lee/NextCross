package com.fit5120tp10.nextcross.client.recording

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** Device GPS, about one update per second. Does nothing if GPS is turned off. */
class LocationManagerTracker(
    context: Context,
    private val onLocation: (Location) -> Unit
) : LocationListener {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun start() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            GPS_INTERVAL_MS,
            0f,
            this,
            Looper.getMainLooper()
        )
    }

    fun stop() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        onLocation(location)
    }

    companion object {
        const val GPS_INTERVAL_MS = 1000L
    }
}
