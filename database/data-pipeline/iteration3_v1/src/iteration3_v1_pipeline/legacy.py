"""Preserve the earlier static-speed selection rules inside this pipeline.

These helpers were retained locally from iteration1_v2 so iteration3_v1 can
select the same Normal Operation baseline rows without importing or reading
the older folder. ``build.py`` uses them for source UUID/speed selection and
for basic EPSG:4326 line validation and EWKT serialization.

This is offline transformation code: inputs are already-parsed source values,
outputs are normalized values or a RoadSpeed record, and invalid source rows
return None. It performs no filesystem, network, or database operations.
PostGIS handles simplification and the final stored-geometry checks later.
"""

from __future__ import annotations

import math
import re
from collections.abc import Sequence
from dataclasses import dataclass
from uuid import UUID


from iteration3_v1_pipeline.settings import NORMAL_OPERATION


@dataclass(frozen=True)
class RoadSpeed:
    """Represent an accepted static source row before database simplification.

    Attributes:
        source_speed_zone_id: Valid source UUID used as the stable row key.
        speed_kmh: Normal Operation speed between 1 and 200 km/h.
        geometry_ewkt: Unsimplified 2D EPSG:4326 MultiLineString EWKT.
    """

    source_speed_zone_id: UUID
    speed_kmh: int
    geometry_ewkt: str


def _normal_conditions(value: object) -> list[str]:
    """Return stripped, nonempty labels from a source string or list.

    Unsupported containers and non-string list items contribute no labels.
    Label case, duplicates, and list order are preserved for exact selection.
    """

    # Normalize container shape and whitespace without changing the labels that
    # the original baseline selector compares exactly.
    # A string is one source label; commas inside it are not list separators.
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
    """Return a finite longitude/latitude pair, discarding trailing dimensions.

    Args:
        value: Source coordinate sequence in GeoJSON longitude/latitude order.

    Returns:
        Two floats in WGS 84 bounds, or None for missing, boolean, non-numeric,
        non-finite, or out-of-range coordinates. Numeric strings are rejected.
    """

    if (
        not isinstance(value, Sequence)
        or isinstance(value, (str, bytes))
        or len(value) < 2
    ):
        return None
    # GeoJSON uses longitude first; extra altitude or measure values are dropped.
    longitude, latitude = value[0], value[1]
    # Python treats booleans as integers, but True/False are not valid coordinates.
    if (
        isinstance(longitude, bool)
        or isinstance(latitude, bool)
        or not isinstance(longitude, (int, float))
        or not isinstance(latitude, (int, float))
    ):
        return None
    longitude = float(longitude)
    latitude = float(latitude)
    # Reject NaN and infinity before range checks and WKT serialization.
    if not math.isfinite(longitude) or not math.isfinite(latitude):
        return None
    if not -180 <= longitude <= 180 or not -90 <= latitude <= 90:
        return None
    return longitude, latitude


def _geometry_ewkt(geometry: object) -> str | None:
    """Convert a basic line GeoJSON geometry to 2D MultiLineString EWKT.

    Args:
        geometry: Source LineString or MultiLineString geometry dictionary.

    Returns:
        EPSG:4326 EWKT with each coordinate formatted to 15 significant digits,
        or None for unsupported geometry, malformed coordinates, empty parts,
        or a line part with fewer than two distinct valid positions.

    Basic coordinate and line checks match the stored-row selection. PostGIS
    remains responsible for simplification and final geometry validity.
    """

    if not isinstance(geometry, dict):
        return None
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    # Give both supported source types the same list-of-lines structure so the
    # database always receives MultiLineString geometry.
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
        # Reject the whole geometry rather than remove points and reshape a road.
        if any(position is None for position in positions):
            return None
        valid_positions = [position for position in positions if position is not None]
        # Repeated copies of one point do not form a usable line.
        if len(set(valid_positions)) < 2:
            return None
        # The distinct-position check only tests usability. Keep repeated
        # vertices and their order in the exported road shape.
        # Keep the original formatting precision so accepted baseline EWKT stays
        # comparable with the earlier extraction.
        points = [
            f"{format(longitude, '.15g')} {format(latitude, '.15g')}"
            for longitude, latitude in valid_positions
        ]
        serialized_parts.append(f"({','.join(points)})")
    return f"SRID=4326;MULTILINESTRING({','.join(serialized_parts)})"


def _road_speed(feature: object) -> RoadSpeed | None:
    """Select one static baseline row using the retained original rules.

    Args:
        feature: Parsed source speed-zone GeoJSON feature.

    Returns:
        RoadSpeed when the normalized conditions are exactly one Normal
        Operation label, speed is a digit-only integer in 1..200, geometry is
        supported, and the source ID is a valid UUID. Otherwise returns None.

    A rejected row may still become a conditional row in ``build.py``; None
    here means it does not meet the static baseline contract.
    """

    if not isinstance(feature, dict):
        return None
    properties = feature.get("properties")
    if not isinstance(properties, dict):
        return None
    # A mixed or repeated condition list is not the original static baseline;
    # build.py can still examine that feature through its conditional path.
    if _normal_conditions(properties.get("zone_conditions")) != [NORMAL_OPERATION]:
        return None

    # Require an explicit integer speed; do not coerce decimals or infer defaults.
    raw_speed = str(properties.get("speed_limit", "")).strip()
    if re.fullmatch(r"\d+", raw_speed) is None:
        return None
    speed_kmh = int(raw_speed)
    if not 1 <= speed_kmh <= 200:
        return None

    # Geometry and UUID must both be usable before a complete baseline row is
    # returned. Database simplification occurs later during loading.
    geometry_ewkt = _geometry_ewkt(feature.get("geometry"))
    if geometry_ewkt is None:
        return None
    try:
        # UUID parsing removes formatting differences such as letter case;
        # downstream matching and duplicate checks use this canonical identity.
        source_id = UUID(str(feature.get("id") or "").strip())
    except ValueError:
        return None
    return RoadSpeed(source_id, speed_kmh, geometry_ewkt)
