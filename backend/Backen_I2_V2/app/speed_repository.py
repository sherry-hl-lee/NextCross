"""Read-only batched PostGIS access; no route persistence or per-point round trips."""
import json
import os
from collections import defaultdict


class SpeedDataUnavailable(RuntimeError):
    pass


CANDIDATES_SQL = """
WITH points AS (
 SELECT sample_id, heading,
 ST_SetSRID(ST_MakePoint(longitude, latitude),4326)::geography AS point
 FROM jsonb_to_recordset(%s::jsonb)
 AS p(sample_id text, latitude double precision, longitude double precision,
 heading double precision)
)
SELECT p.sample_id, r.source_speed_zone_id, r.speed_kmh, r.status,
 ST_Distance(r.road_geom,p.point) AS distance_m
FROM points p JOIN iteration2_v2.road_speed r
 ON ST_DWithin(r.road_geom,p.point,%s)
WHERE p.heading IS NULL OR r.direction IS NULL
 OR abs(mod((r.direction::numeric-p.heading::numeric+540),360)-180)<=67.5
ORDER BY p.sample_id, distance_m, r.source_speed_zone_id
"""


def load_speed_data(samples, config):
    url = (os.getenv("SPEED_DATABASE_URL") or os.getenv("HISTORICAL_DATABASE_URL")
           or os.getenv("RUNTIME_DATABASE_URL") or os.getenv("DATABASE_URL"))
    if not url:
        raise SpeedDataUnavailable("Speed database is not configured")
    import psycopg
    from psycopg.rows import dict_row
    candidates = defaultdict(list)
    rules = defaultdict(list)
    # A new connection keeps analysis reads separate from notification writes.
    with psycopg.connect(url, autocommit=True, row_factory=dict_row, connect_timeout=10) as conn:
        try:
            conn.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
            conn.execute("SET LOCAL statement_timeout = '10000ms'")
            for offset in range(0, len(samples), config.query_chunk_size):
                chunk = samples[offset:offset + config.query_chunk_size]
                payload = [{"sample_id": s.sample_id, "latitude": s.latitude,
                            "longitude": s.longitude, "heading": s.bearing_degrees} for s in chunk]
                for row in conn.execute(CANDIDATES_SQL, (json.dumps(payload), config.lookup_radius_meters)).fetchall():
                    candidates[row["sample_id"]].append(row)
            ids = sorted({str(r["source_speed_zone_id"]) for rows in candidates.values() for r in rows})
            if ids:
                for row in conn.execute("SELECT * FROM iteration2_v2.speed_rule WHERE source_speed_zone_id = ANY(%s::uuid[])", (ids,)).fetchall():
                    rules[str(row["source_speed_zone_id"])].append(row)
            periods = conn.execute("SELECT kind,start_date,end_date FROM iteration2_v2.calendar_period").fetchall()
            # Keep bounds/policy together with periods in this repeatable-read
            # snapshot. Fetching only the year would incorrectly treat bounded
            # reviewed coverage as a complete year.
            coverage = {r["year"]: r for r in conn.execute(
                "SELECT year,build_id,policy_version,source_manifest FROM iteration2_v2.calendar_coverage"
            ).fetchall()}
            return candidates, rules, periods, coverage
        finally:
            conn.execute("ROLLBACK")
