package com.fit5120tp10.nextcross.client.permittedarea

/** A validated latitude/longitude pair in decimal degrees. */
data class GeographicPoint(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite and between -90 and 90 degrees."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite and between -180 and 180 degrees."
        }
    }
}
