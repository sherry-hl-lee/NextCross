package com.fit5120tp10.nextcross.client.routing

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint

/**
 * Decodes Google encoded polylines used by Directions API overview_polyline.
 * @see https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
internal object EncodedPolylineDecoder {
    fun decode(encoded: String): List<GeographicPoint> {
        if (encoded.isBlank()) return emptyList()

        val points = ArrayList<GeographicPoint>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val deltaLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += deltaLat

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val deltaLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += deltaLng

            points.add(
                GeographicPoint(
                    latitude = lat / 1e5,
                    longitude = lng / 1e5
                )
            )
        }
        return points
    }
}
