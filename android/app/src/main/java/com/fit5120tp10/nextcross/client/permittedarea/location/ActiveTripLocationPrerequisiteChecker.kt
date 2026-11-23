package com.fit5120tp10.nextcross.client.permittedarea.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

enum class ActiveTripLocationStartFailure {
    FineLocationPermissionRequired,
    GpsProviderDisabled,
    GpsProviderUnavailable
}

fun interface ActiveTripLocationPrerequisiteChecker {
    /** Returns null only when Client A can start its existing GPS recorder. */
    fun findFailure(): ActiveTripLocationStartFailure?
}

class AndroidActiveTripLocationPrerequisiteChecker(context: Context) :
    ActiveTripLocationPrerequisiteChecker {
    private val appContext = context.applicationContext
    private val locationManager = appContext
        .getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun findFailure(): ActiveTripLocationStartFailure? {
        if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ActiveTripLocationStartFailure.FineLocationPermissionRequired
        }

        val manager = locationManager
            ?: return ActiveTripLocationStartFailure.GpsProviderUnavailable
        return try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                null
            } else {
                ActiveTripLocationStartFailure.GpsProviderDisabled
            }
        } catch (_: RuntimeException) {
            ActiveTripLocationStartFailure.GpsProviderUnavailable
        }
    }
}
