package com.fit5120tp10.nextcross.client.historicalcrash

import com.fit5120tp10.nextcross.client.permittedarea.GeographicPoint
import org.json.JSONArray
import org.json.JSONObject

internal object HistoricalCrashJson {
    fun requestQueryString(request: HistoricalCrashContextRequest): String {
        val parts = mutableListOf(
            "latitude=${request.centre.latitude}",
            "longitude=${request.centre.longitude}",
            "radiusMeters=${request.radiusMeters}",
            "timePeriod=${request.timePeriod.wireValue}"
        )
        request.startDate?.let { parts += "startDate=$it" }
        request.endDate?.let { parts += "endDate=$it" }
        return parts.joinToString("&")
    }

    fun parseResponse(
        body: String,
        fallbackRequest: HistoricalCrashContextRequest
    ): HistoricalCrashContext {
        val root = JSONObject(body)
        val status = HistoricalCrashContextStatus.fromWireValue(root.getString("status"))
            ?: throw IllegalArgumentException("Unknown status in historical crash response.")
        val timePeriod = CrashTimePeriod.fromWireValue(
            root.optString("timePeriod", fallbackRequest.timePeriod.wireValue)
        ) ?: fallbackRequest.timePeriod

        val areaObject = root.optJSONObject("area")
        val centre = if (areaObject != null) {
            GeographicPoint(
                latitude = areaObject.getDouble("latitude"),
                longitude = areaObject.getDouble("longitude")
            )
        } else {
            fallbackRequest.centre
        }
        val radiusMeters = areaObject?.optDouble("radiusMeters", fallbackRequest.radiusMeters)
            ?: fallbackRequest.radiusMeters

        val totalsObject = root.optJSONObject("totals")
        val totals = if (totalsObject == null || totalsObject == JSONObject.NULL) {
            null
        } else {
            CrashTotals(
                crashCount = totalsObject.getInt("crashCount"),
                fatalities = totalsObject.getInt("fatalities"),
                seriousInjuries = totalsObject.getInt("seriousInjuries")
            )
        }

        return HistoricalCrashContext(
            status = status,
            timePeriod = timePeriod,
            areaCentre = centre,
            radiusMeters = radiusMeters,
            totals = totals,
            clusters = parseClusters(root.optJSONArray("clusters")),
            individualPoints = parseIndividuals(root.optJSONArray("individualPoints")),
            periodStart = root.optStringOrNull("periodStart"),
            periodEnd = root.optStringOrNull("periodEnd"),
            buildId = root.optStringOrNull("buildId"),
            countScope = root.optStringOrNull("countScope")
        )
    }

    private fun parseClusters(array: JSONArray?): List<CrashCluster> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    CrashCluster(
                        hotspotId = item.getString("hotspotId"),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        locationLabel = item.optString("locationLabel", "Unknown location"),
                        crashCount = item.getInt("crashCount"),
                        fatalities = item.optInt("fatalities", 0),
                        seriousInjuries = item.optInt("seriousInjuries", 0),
                        displayPointSource = item.optStringOrNull("displayPointSource")
                    )
                )
            }
        }
    }

    private fun parseIndividuals(array: JSONArray?): List<CrashIndividualPoint> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    CrashIndividualPoint(
                        accidentId = item.getString("accidentId"),
                        crashDate = item.optStringOrNull("crashDate"),
                        timePeriod = CrashTimePeriod.fromWireValue(
                            item.optString("timePeriod", "")
                        ),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        locationLabel = item.optString("locationLabel", "Unknown location"),
                        crashCount = item.optInt("crashCount", 1),
                        fatalities = item.optInt("fatalities", 0),
                        seriousInjuries = item.optInt("seriousInjuries", 0)
                    )
                )
            }
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val value = optString(name)
        return value.takeIf { it.isNotBlank() }
    }
}
