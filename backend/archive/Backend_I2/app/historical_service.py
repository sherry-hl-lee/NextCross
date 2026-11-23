"""Backend-owned PostGIS queries. No runtime data-pipeline dependency."""
import os
from .historical_schemas import HistoricalCrashQuery, HistoricalCrashResponse


class CrashLookupUnavailable(RuntimeError):
    pass


BUILD_SQL = """
SELECT build_id, start_date, end_date, region, cohort_policy, eps_m,
       min_samples, crs_epsg, time_policy_version, cluster_policy_version,
       event_rows, hotspot_rows, missing_point_rows
FROM iteration2_v2.crash_build WHERE singleton = true
"""

# Bound parameters; point-based filtering; stable nearest-member tie-break.
EVENTS_SQL = """
WITH matching AS (
 SELECT e.* FROM iteration2_v2.crash_event e
 WHERE e.build_id = %(build_id)s
   AND e.crash_date BETWEEN %(start_date)s AND %(end_date)s
   AND e.time_period = %(time_period)s
   AND e.accident_point IS NOT NULL
   AND ST_DWithin(e.accident_point::geography,
       ST_SetSRID(ST_MakePoint(%(longitude)s, %(latitude)s), 4326)::geography,
       %(radius_m)s)
)
SELECT e.accident_id, e.hotspot_id, e.crash_date, e.time_period,
       e.location_label, e.fatalities, e.serious_injuries,
       ST_Y(e.accident_point) AS latitude, ST_X(e.accident_point) AS longitude,
       h.hotspot_id AS joined_hotspot_id, h.location_label AS representative_label,
       ST_Y(h.representative_point) AS representative_latitude,
       ST_X(h.representative_point) AS representative_longitude,
       ST_DWithin(h.representative_point::geography,
         ST_SetSRID(ST_MakePoint(%(longitude)s, %(latitude)s), 4326)::geography,
         %(radius_m)s) AS representative_inside,
       ROW_NUMBER() OVER (PARTITION BY e.hotspot_id
         ORDER BY ST_Distance(e.accident_point::geography,
             h.representative_point::geography), e.accident_id) AS member_rank
FROM matching e
LEFT JOIN iteration2_v2.hotspot h
 ON h.hotspot_id = e.hotspot_id AND h.build_id = e.build_id
ORDER BY e.accident_id
"""


def base_response(query):
    return {
        "status": "no_dataset", "time_period": query.time_period,
        "requested_start_date": query.start_date, "requested_end_date": query.end_date,
        "area": {"kind": "circle", "latitude": query.latitude,
                 "longitude": query.longitude, "radius_meters": query.radius_meters},
        "count_scope": "Counts are based on actual crash points inside the circle.",
        "totals": None, "dataset": None, "clusters": [], "individual_points": [],
    }


def lookup_with_connection(connection, query):
    """Caller supplies a fresh dict-row connection, separate from notification writes."""
    response = base_response(query)
    with connection.cursor() as cursor:
        cursor.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        cursor.execute("SET LOCAL statement_timeout = '10000ms'")
        cursor.execute(BUILD_SQL)
        builds = cursor.fetchall()
        if not builds:
            return response
        if len(builds) != 1:
            raise CrashLookupUnavailable("Expected one active crash build")
        build = builds[0]
        response.update(
            build_id=build["build_id"], period_start=build["start_date"],
            period_end=build["end_date"],
            dataset={
                "region": build["region"], "cohortPolicy": build["cohort_policy"],
                "epsMeters": build["eps_m"], "minSamples": build["min_samples"],
                "crsEpsg": build["crs_epsg"], "eventRows": build["event_rows"],
                "hotspotRows": build["hotspot_rows"],
                "unlocatedEventRows": build["missing_point_rows"],
                "timePolicyVersion": build["time_policy_version"],
                "clusterPolicyVersion": build["cluster_policy_version"],
            },
        )
        start = query.start_date or build["start_date"]
        end = query.end_date or build["end_date"]
        # Any out-of-range endpoint is rejected, even for a partially overlapping interval.
        if not (build["start_date"] <= start <= build["end_date"]
                and build["start_date"] <= end <= build["end_date"]):
            response["status"] = "outside_dataset_period"
            return response
        cursor.execute(EVENTS_SQL, {
            "build_id": build["build_id"], "start_date": start, "end_date": end,
            "time_period": query.time_period, "latitude": query.latitude,
            "longitude": query.longitude, "radius_m": query.radius_meters,
        })
        events = cursor.fetchall()
    response["status"] = "recorded" if events else "no_recorded_crashes"
    response["totals"] = {
        "crash_count": len(events), "fatalities": sum(e["fatalities"] for e in events),
        "serious_injuries": sum(e["serious_injuries"] for e in events),
    }
    groups = {}
    for event in events:
        if event["hotspot_id"] is None:
            response["individual_points"].append({key: event[key] for key in (
                "accident_id", "crash_date", "time_period", "latitude",
                "longitude", "location_label", "fatalities", "serious_injuries"
            )} | {"crash_count": 1})
        else:
            if event["joined_hotspot_id"] is None:
                raise CrashLookupUnavailable("Inconsistent hotspot record")
            groups.setdefault(event["hotspot_id"], []).append(event)
    for hotspot_id, members in sorted(groups.items()):
        member = min(members, key=lambda e: e["member_rank"])
        inside = member["representative_inside"]
        response["clusters"].append({
            "hotspot_id": hotspot_id,
            "latitude": member["representative_latitude"] if inside else member["latitude"],
            "longitude": member["representative_longitude"] if inside else member["longitude"],
            "location_label": member["representative_label"] if inside else member["location_label"],
            "representative_latitude": member["representative_latitude"],
            "representative_longitude": member["representative_longitude"],
            "display_point_source": "cluster_representative" if inside else "in_area_member",
            "crash_count": len(members), "fatalities": sum(e["fatalities"] for e in members),
            "serious_injuries": sum(e["serious_injuries"] for e in members),
        })
    return response


def call_lookup(query: HistoricalCrashQuery):
    if os.getenv("HISTORICAL_CRASH_USE_MOCK", "false").lower() == "true":
        return base_response(query)
    url = os.getenv("HISTORICAL_DATABASE_URL") or os.getenv("RUNTIME_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not url:
        raise CrashLookupUnavailable("No database connection configured")
    import psycopg
    from psycopg.rows import dict_row
    with psycopg.connect(url, autocommit=True, connect_timeout=10, row_factory=dict_row) as connection:
        try:
            return lookup_with_connection(connection, query)
        finally:
            connection.rollback()


def normalize_response(raw, query):
    # Never fill missing database counts with zeros.
    return HistoricalCrashResponse.model_validate(raw)
