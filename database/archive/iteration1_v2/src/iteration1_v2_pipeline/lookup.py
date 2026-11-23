"""Run a small parameterized nearest-road lookup against the v2 schema."""

from __future__ import annotations

import math
from typing import Any


LOOKUP_SQL = """
WITH gps AS (
    SELECT ST_SetSRID(ST_MakePoint(%(longitude)s, %(latitude)s), 4326)::geography
        AS location
)
SELECT roads.speed_kmh
FROM iteration1_v2.road_speed AS roads
CROSS JOIN gps
WHERE ST_DWithin(roads.road_geom, gps.location, %(radius_m)s)
ORDER BY
    ST_Distance(roads.road_geom, gps.location),
    roads.source_speed_zone_id
LIMIT 1
"""


def validate_location(latitude: float, longitude: float, radius_m: float) -> None:
    """Check the basic coordinate and search-radius bounds used by PostGIS."""

    if not all(math.isfinite(value) for value in (latitude, longitude, radius_m)):
        raise ValueError("Location values must be finite")
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise ValueError("Latitude or longitude is outside WGS 84 bounds")
    if not 0 < radius_m <= 1000:
        raise ValueError("Radius must be above 0 and at most 1000 metres")


def lookup_speed(
    cursor: Any,
    *,
    latitude: float,
    longitude: float,
    radius_m: float = 30.0,
) -> int | None:
    """Return the nearest speed with source UUID as a stable distance tie-break."""

    validate_location(latitude, longitude, radius_m)
    cursor.execute(
        LOOKUP_SQL,
        {
            "latitude": latitude,
            "longitude": longitude,
            "radius_m": radius_m,
        },
    )
    row = cursor.fetchone()
    return None if row is None else int(row[0])


def query_speed(
    database_url: str,
    *,
    latitude: float,
    longitude: float,
    radius_m: float = 30.0,
) -> int | None:
    """Open PostgreSQL, perform one nearest-road lookup, and close it."""

    import psycopg

    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            return lookup_speed(
                cursor,
                latitude=latitude,
                longitude=longitude,
                radius_m=radius_m,
            )
