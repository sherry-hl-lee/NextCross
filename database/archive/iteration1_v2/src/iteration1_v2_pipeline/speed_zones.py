"""Stream and select static Speed Zones rows with ijson."""

from __future__ import annotations

import math
import re
from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from uuid import UUID

import ijson

from iteration1_v2_pipeline.settings import NORMAL_OPERATION


@dataclass
class Counts:
    """Track the source, accepted, and skipped totals needed by v2."""

    seen: int = 0
    accepted: int = 0
    skipped: int = 0


@dataclass(frozen=True)
class RoadSpeed:
    """Represent one source-geometry row ready for DB-side simplification."""

    source_speed_zone_id: UUID
    speed_kmh: int
    geometry_ewkt: str


def _normal_conditions(value: object) -> list[str]:
    """Normalize nonempty source conditions for static-speed selection."""

    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        return [
            item.strip()
            for item in value
            if isinstance(item, str) and item.strip()
        ]
    return []


def _position(value: object) -> tuple[float, float] | None:
    """Return one finite WGS 84 position, discarding trailing altitude."""

    if (
        not isinstance(value, Sequence)
        or isinstance(value, (str, bytes))
        or len(value) < 2
    ):
        return None
    longitude, latitude = value[0], value[1]
    if (
        isinstance(longitude, bool)
        or isinstance(latitude, bool)
        or not isinstance(longitude, (int, float))
        or not isinstance(latitude, (int, float))
    ):
        return None
    longitude = float(longitude)
    latitude = float(latitude)
    if not math.isfinite(longitude) or not math.isfinite(latitude):
        return None
    if not -180 <= longitude <= 180 or not -90 <= latitude <= 90:
        return None
    return longitude, latitude


def _geometry_ewkt(geometry: object) -> str | None:
    """Convert a basic line GeoJSON geometry to 2D MultiLineString EWKT.

    Basic coordinate and line checks match the stored-row selection. PostGIS
    remains responsible for simplification and final geometry validity.
    """

    if not isinstance(geometry, dict):
        return None
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if geometry_type == "LineString":
        parts = [coordinates]
    elif geometry_type == "MultiLineString":
        parts = coordinates
    else:
        return None
    if not isinstance(parts, list) or not parts:
        return None

    serialized_parts: list[str] = []
    for part in parts:
        if not isinstance(part, list) or len(part) < 2:
            return None
        positions = [_position(value) for value in part]
        if any(position is None for position in positions):
            return None
        valid_positions = [position for position in positions if position is not None]
        if len(set(valid_positions)) < 2:
            return None
        points = [
            f"{format(longitude, '.15g')} {format(latitude, '.15g')}"
            for longitude, latitude in valid_positions
        ]
        serialized_parts.append(f"({','.join(points)})")
    return f"SRID=4326;MULTILINESTRING({','.join(serialized_parts)})"


def _road_speed(feature: object) -> RoadSpeed | None:
    """Return a row for one usable Normal Operation feature, otherwise None."""

    if not isinstance(feature, dict):
        return None
    properties = feature.get("properties")
    if not isinstance(properties, dict):
        return None
    if _normal_conditions(properties.get("zone_conditions")) != [NORMAL_OPERATION]:
        return None

    raw_speed = str(properties.get("speed_limit", "")).strip()
    if re.fullmatch(r"\d+", raw_speed) is None:
        return None
    speed_kmh = int(raw_speed)
    if not 1 <= speed_kmh <= 200:
        return None

    geometry_ewkt = _geometry_ewkt(feature.get("geometry"))
    if geometry_ewkt is None:
        return None
    try:
        source_id = UUID(str(feature.get("id") or "").strip())
    except ValueError:
        return None
    return RoadSpeed(source_id, speed_kmh, geometry_ewkt)


def iter_road_speeds(
    raw_file: Path,
    counts: Counts | None = None,
) -> Iterator[RoadSpeed]:
    """Yield accepted rows from ``features.item`` without loading the file.

    Args:
        raw_file: Plain UTF-8 GeoJSON FeatureCollection downloaded by v2.
        counts: Optional counters updated as the iterator is consumed.

    Yields:
        Database-ready UUID, speed, and MultiLineString EWKT rows.

    Raises:
        Exception: Propagates file and ijson parser failures.

    Side effects:
        Reads ``raw_file`` once and mutates ``counts`` when supplied.
    """

    result_counts = counts if counts is not None else Counts()
    with raw_file.open("rb") as source:
        for feature in ijson.items(
            source,
            "features.item",
            use_float=True,
        ):
            result_counts.seen += 1
            row = _road_speed(feature)
            if row is None:
                result_counts.skipped += 1
                continue
            result_counts.accepted += 1
            yield row
