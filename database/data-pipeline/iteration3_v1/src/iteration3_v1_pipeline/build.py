"""Build the statewide speed dataset offline from local GeoJSON snapshots.

This pipeline stage preserves the static rows selected by ``legacy.py`` and
adds usable conditional zones. Sign direction, distance, schedules, and zone
connectivity provide evidence for scheduled or static limits; unsupported or
conflicting records remain explicitly unresolved or dynamic. The spatial
matches are dataset inferences, not a surveyed sign-to-road relationship.

``build()`` writes ``road_speed.csv``, ``speed_rule.csv``, and an evidence
``report.json`` for the database loader. Matching uses EPSG:7899 metres while
the exported road geometry remains EPSG:4326 EWKT. This module neither queries
Neon nor evaluates the applicable limit for an individual backend request.
"""

import csv
import json
from collections import Counter
from dataclasses import dataclass, field, replace
from datetime import date, datetime
from pathlib import Path
from uuid import UUID
from zoneinfo import ZoneInfo

import ijson
from pyproj import Transformer
from shapely import STRtree, from_wkt, line_merge
from shapely.geometry import Point, box, shape
from shapely.ops import transform, unary_union

from iteration3_v1_pipeline.legacy import _geometry_ewkt, _normal_conditions, _road_speed
from iteration3_v1_pipeline.settings import DIRECTIONS, PROCESSED_DIR, RAW_DIR
from iteration3_v1_pipeline.signs import angular_difference, is_current, is_regulatory, sign_rules, travel_heading

# Matching distances use projected metres; longitude stays the first input axis.
PROJECT = Transformer.from_crs(4326, 7899, always_xy=True).transform
# The loader imports these column lists, so CSV output and COPY use the same order.
ROAD_FIELDS = ("source_speed_zone_id", "speed_kmh", "road_geom", "is_legacy", "direction", "conditions", "status")
RULE_FIELDS = ("source_speed_zone_id", "rule_no", "speed_kmh", "weekdays", "start_minute", "end_minute", "school_days", "public_holidays", "source_sign_ids")
# This is the sign-to-road search radius. The separate 0.2 m checks below
# decide whether road endpoints connect; they serve a different purpose.
SIGN_DISTANCE_M = 30


def features(path):
    """Yield GeoJSON features incrementally from a local FeatureCollection.

    Args:
        path: Source GeoJSON file containing a top-level ``features`` array.

    Yields:
        Parsed source features, with numeric values read using float support.

    Raises:
        OSError: The source cannot be opened or read.
        Exception: Propagates ijson parsing failures.

    Side effects:
        Holds the source file open while the iterator is being consumed.
    """
    # Read one feature at a time instead of loading the statewide JSON into memory.
    with Path(path).open("rb") as stream:
        yield from ijson.items(stream, "features.item", use_float=True)


def pg_array(values):
    """Encode string or integer values as a PostgreSQL array literal for CSV."""
    # csv.writer handles the outer CSV quoting; this builds the inner array value.
    # For example, weekdays [1, 2] become {1,2}; an empty list becomes {}.
    return "{" + ",".join(json.dumps(v) for v in values) + "}"


@dataclass
class Road:
    """Hold one speed-zone row and mutable evidence during the offline build.

    Attributes:
        id: Source speed-zone UUID as text.
        source_speed: Published zone speed, or None when it is unusable.
        ewkt: Original EPSG:4326 MultiLineString EWKT for export; an empty
            string is sufficient for baseline roads retained only for matching.
        geometry: Shapely line geometry projected into EPSG:7899 metres.
        direction: Published travel heading in degrees, or None if unknown.
        conditions: Normalized source condition labels.
        legacy: Whether the row meets the original static selection rules.
        base_speed: Supported static or off-hours speed, or None if unknown.
        status: Resolution state: static, scheduled, dynamic, or unresolved.
        rules: Parsed sign rules assigned to this zone.
        evidence: Matching decisions and source identifiers for the report.
        note: Source free-text condition annotation used for explicit cases.
    """

    id: str
    source_speed: int | None
    ewkt: str
    geometry: object
    direction: int | None
    conditions: tuple[str, ...]
    legacy: bool
    # The published source speed may apply only during a signed window. Keep
    # the normal/off-hours speed unset until separate evidence establishes it.
    # For example, a school zone can have source_speed=40 and base_speed=60;
    # the 40 km/h timetable belongs in rules, while 60 needs its own evidence.
    base_speed: int | None = None
    status: str = "unresolved"
    rules: tuple = ()
    # Give each new Road its own evidence dictionary rather than a shared default.
    evidence: dict = field(default_factory=dict)
    note: str = ""

    def row(self):
        """Return the seven export values in ``ROAD_FIELDS`` order."""
        # Export the supported base speed, which can differ from source_speed.
        # csv.writer writes None as an empty field, so an unknown speed stays blank.
        return (self.id, self.base_speed, self.ewkt, self.legacy,
                self.direction, pg_array(self.conditions), self.status)


def read_signs(path, conditional_tree, counts, as_of):
    """Read current sign evidence within 30 metres of a conditional zone.

    Args:
        path: Local speed-sign GeoJSON snapshot.
        conditional_tree: STRtree of conditional road geometry in EPSG:7899.
        counts: Mutable counters for source, invalid, and current sign rows.
        as_of: Date used by the source installation/removal eligibility check.

    Returns:
        Sign dictionaries containing identity, projected point, travel heading,
        source properties, parsed rules, and an optional rule-parsing error.
        A bad schedule remains evidence so it can prevent an unsupported match.

    Raises:
        Exception: Propagates source I/O, JSON parsing, and unexpected geometry
            failures; the explicitly handled malformed rows are counted/skipped.

    Side effects:
        Reads the source once and updates ``counts``. No files are written.
    """
    signs = []
    for feature in features(path):
        counts["source_sign_rows"] += 1
        properties = feature.get("properties") or {}
        # Reject ineligible snapshot records before spending time on geometry.
        if not is_current(properties, as_of=as_of):
            counts["noncurrent_sign_rows"] += 1
            continue
        if not properties.get("speed_sign_id"):
            counts["invalid_sign_rows"] += 1
            continue
        try:
            geometry = transform(PROJECT, shape(feature["geometry"]))
            if geometry.geom_type != "Point" or geometry.is_empty or not geometry.is_valid:
                raise ValueError("Sign geometry must be a valid point")
            heading = travel_heading(properties)
        except (KeyError, ValueError, TypeError):
            counts["invalid_sign_rows"] += 1
            continue
        # Count usable current signs even when they lie outside the matching area.
        counts["current_sign_rows"] += 1
        # This distance filter only collects possible evidence. attach_signs()
        # later decides which aligned roads can actually claim the sign.
        if not len(conditional_tree.query(geometry, predicate="dwithin", distance=SIGN_DISTANCE_M)):
            continue
        try:
            rules = sign_rules(properties, as_of=as_of)
            error = None
        except (KeyError, ValueError, TypeError) as exc:
            # Keep malformed schedules as evidence rather than silently ignoring them.
            rules, error = (), str(exc)
        signs.append({"id": str(properties["speed_sign_id"]), "geometry": geometry,
                      "heading": heading, "properties": properties,
                      "rules": rules, "error": error})
    return signs


def components(roads):
    """Yield connected groups of compatible conditional road pieces.

    Args:
        roads: Indexed Road sequence whose geometry is in EPSG:7899 metres.

    Yields:
        Lists of roads joined by endpoints within 0.2 metres, identical
        conditions/source speed, and headings differing by at most 45 degrees.
        Compatibility is checked for each adjacent pair, so a group may bend.

    This is a local connectivity inference. It does not merge road geometry or
    establish a shared street name, and isolated pieces are yielded individually.
    """
    # The spatial index returns positions in this geometry list, so each result
    # can be mapped back to its Road without scanning every road in the state.
    tree = STRtree([r.geometry for r in roads])
    seen = set()
    for start in range(len(roads)):
        if start in seen:
            continue
        # Walk each connected group once; an unconnected road becomes its own group.
        todo, group = [start], []
        seen.add(start)
        while todo:
            index = todo.pop()
            road = roads[index]
            group.append(road)
            for candidate in tree.query(road.geometry, predicate="dwithin", distance=0.2):
                j = int(candidate)
                other = roads[j]
                # Nearby lines alone are insufficient: conditions, direction, and
                # endpoints must agree before they can share schedule evidence.
                # Compare the complete condition tuple, retaining source label
                # order; do not combine differently annotated zone records.
                if (j not in seen and (road.conditions, road.source_speed) == (other.conditions, other.source_speed)
                        and road.direction is not None and other.direction is not None
                        and angular_difference(road.direction, other.direction) <= 45
                        and road.geometry.boundary.distance(other.geometry.boundary) <= 0.2):
                    # Mark a road when adding it to the work list. Two neighbours
                    # may discover the same road before it is visited.
                    seen.add(j)
                    todo.append(j)
        yield group


def aligned(sign, heading):
    """Return whether both headings exist and differ by at most 45 degrees."""
    # An unknown heading is insufficient evidence of travel-direction agreement.
    return sign["heading"] is not None and heading is not None and angular_difference(sign["heading"], heading) <= 45


def attach_signs(signs, roads):
    """Assign each sign to the nearest aligned candidate roads within 30 metres.

    Args:
        signs: Mutable sign dictionaries produced by ``read_signs``.
        roads: Conditional and retained static Roads in EPSG:7899 metres.

    Side effects:
        Adds ``road_ids`` and ``conditional_ids`` sets to each sign. All
        candidates within one metre of the best distance are retained as ties;
        the latter set contains only non-legacy candidates.

    Including nearby static roads prevents a conditional zone from claiming a
    sign that is closer to an aligned static road. Assignment is inferred from
    geometry and direction and can remain ambiguous across tied candidates.
    """
    tree = STRtree([r.geometry for r in roads])
    for sign in signs:
        candidates = [roads[int(i)] for i in tree.query(sign["geometry"], predicate="dwithin", distance=SIGN_DISTANCE_M)
                      if aligned(sign, roads[int(i)].direction)]
        distances = [(r.geometry.distance(sign["geometry"]), r.id) for r in candidates]
        # An empty candidate list leaves both ownership sets empty; infinity
        # avoids raising an error just because no nearby road matches direction.
        best = min((distance for distance, _ in distances), default=float("inf"))
        # Preserve near-ties so a sign is not arbitrarily claimed by one road.
        # Distances of 10.0 m and 10.5 m retain both roads; direction filtering
        # has already removed nearby roads that face the wrong way.
        sign["road_ids"] = {source_id for distance, source_id in distances if distance <= best + 1.0}
        # Keep conditional ownership separately so resolve_group() can detect
        # a sign shared with a competing conditional group.
        sign["conditional_ids"] = {r.id for r in candidates if not r.legacy and r.id in sign["road_ids"]}


def road_name(sign):
    """Normalize a sign's road name for matching, using uppercase and single spaces."""
    # Normalize spacing and case so " High   St " and "HIGH ST" match.
    return " ".join(str(sign["properties"].get("road_name") or "").upper().split())


def resolve_group(group, signs, sign_tree, normal_roads=(), normal_tree=None):
    """Resolve one connected zone group from its available source evidence.

    Args:
        group: Nonempty compatible Road group from ``components``; roads are
            expected to begin unresolved with no assigned rules or baseline.
        signs: Parsed sign dictionaries with ownership from ``attach_signs``.
        sign_tree: STRtree in the same order as ``signs``, in EPSG:7899 metres.
        normal_roads: Retained static Roads available as endpoint evidence.
        normal_tree: Optional STRtree with the same order as ``normal_roads``.

    Side effects:
        Mutates each Road's resolution status, rules, baseline, and/or evidence.
        No database or filesystem operations are performed.

    School/Shop zones require one consistent signed schedule. An off-hours
    baseline additionally needs agreeing standard endpoint signs, or matching
    connected static speeds at both ends when no such sign speed is available.
    Mixed Normal Operation annotations do not establish that baseline. An
    explicit Shared Zone annotation and matching signs can establish a static
    limit; other unsupported conditions retain an unknown or dynamic status.
    The resulting evidence records explain the inference for later review.
    """
    # components() grouped roads with matching conditions and source speeds,
    # so the first road supplies the shared condition to resolve for this group.
    first = group[0]
    conditional_kinds = [kind for kind in first.conditions if kind != "Normal Operation"]
    # Several special labels do not establish one timetable type. Keep them
    # as Mixed instead of choosing whichever label happens to appear first.
    kind = conditional_kinds[0] if len(conditional_kinds) == 1 else "Mixed"
    # One explicit source annotation supports a permanent Shared Zone case.
    # Generic Other, temporal comments and merely nearby signs remain unknown.
    if kind == "Other" and all(r.conditions == ("Other",) and r.note.strip().casefold() == "shared zone" for r in group):
        geometry = unary_union([r.geometry for r in group])
        group_ids = {r.id for r in group}
        owned = [signs[int(i)] for i in sign_tree.query(geometry, predicate="dwithin", distance=SIGN_DISTANCE_M)]
        # '&' requires ownership by at least one road in this group; '<=' requires
        # every assigned conditional road to belong to this same group.
        owned = [s for s in owned if s.get("road_ids", set()) & group_ids
                 and s.get("conditional_ids", set()) <= group_ids
                 and aligned(s, min(group, key=lambda r: r.geometry.distance(s["geometry"])).direction)
                 and s["properties"].get("sign_type") == "Shared Zone"]
        # A permanent limit needs unqualified evidence; any time fields or comments
        # prevent this special case from being promoted to a static speed.
        restricted = any(s["properties"].get(key) for s in owned for key in (
            "variable_calendar_type", "comments", "variable_time_1_from", "variable_time_1_to",
            "variable_time_1_days", "variable_time_2_from", "variable_time_2_to", "variable_time_2_days"))
        speeds = {str(s["properties"].get("speed")) for s in owned}
        if owned and not restricted and first.source_speed is not None and speeds == {str(first.source_speed)}:
            for road in group:
                road.base_speed, road.status = first.source_speed, "static"
                road.evidence = {"reason": "explicit_shared_zone_and_current_signs",
                                 "base_sign_ids": sorted(s["id"] for s in owned),
                                 "base_speed_kmh": first.source_speed}
            return
    # Stop here for unsupported conditions; live activation cannot be inferred
    # from a downloaded snapshot, even when the source contains a speed value.
    if kind not in ("School", "Shop"):
        status = "dynamic" if any("Activated" in c or "Variable" in c for c in first.conditions) else "unresolved"
        for road in group:
            road.status = status
            road.evidence = {"reason": "live_state_required" if status == "dynamic" else "unsupported_condition"}
        return
    # Combine pieces for this evidence search; each original road keeps its own
    # ID and export geometry even when the whole group shares one schedule.
    geometry = unary_union([r.geometry for r in group])
    group_ids = {road.id for road in group}
    nearby = [signs[int(i)] for i in sign_tree.query(geometry, predicate="dwithin", distance=SIGN_DISTANCE_M)]
    # Use the closest piece's heading: a connected group can curve enough that
    # its first piece no longer represents traffic near this particular sign.
    nearby = [s for s in nearby if aligned(s, min(group, key=lambda r: r.geometry.distance(s["geometry"])).direction)]
    # Exclude signs also assigned to a conditional road outside this group.
    # A nearby sign must additionally agree with this zone's type and speed.
    candidates = [s for s in nearby if s.get("road_ids", set()) & group_ids
                  and s.get("conditional_ids", set()) <= group_ids
                  and s["properties"].get("variable_calendar_type") == kind
                  and is_regulatory(s["properties"]) and str(s["properties"].get("speed")) == str(first.source_speed)]
    # Compare schedule contents without sign IDs, so identical signs can agree.
    # The set collapses matching timetables into one entry: zero entries means
    # no parsed timetable, while several entries mean the candidates disagree.
    # Each signature retains time-slot order, so reversed source slots are not
    # silently treated as identical evidence by this comparison.
    signatures = {tuple(rule.signature() for rule in s["rules"]) for s in candidates if s["rules"]}
    evidence = {"candidate_sign_ids": sorted(s["id"] for s in candidates)}
    # Missing, malformed, or conflicting schedules leave the initial unknown state.
    if any(s["error"] for s in candidates) or len(signatures) != 1:
        evidence["reason"] = "missing_or_conflicting_schedule"
        for road in group:
            road.evidence = evidence
        return
    schedule = next(iter(signatures))
    ids = tuple(sorted(s["id"] for s in candidates if s["rules"]))
    # Attach every supporting sign ID to the agreed rules for later auditing.
    # replace() creates copies, leaving the original per-sign rules unchanged
    # when those same sign records are examined for another group.
    rules = tuple(replace(rule, source_sign_ids=ids) for rule in schedule)
    if "Normal Operation" in first.conditions:
        # Mixed annotations can evidence the signed active limit, but do not
        # settle what "Normal Operation" means outside the signed window.
        for road in group:
            road.rules, road.status = rules, "scheduled"
            road.evidence = {**evidence, "reason": "mixed_normal_annotation_base_unconfirmed",
                             "base_speed_kmh": None}
        return
    # Empty road names provide no name evidence for the standard endpoint signs.
    names = {road_name(s) for s in candidates if s["rules"] and road_name(s)}

    # A baseline must be evidenced by a normal speed sign at a zone endpoint.
    # Arbitrary nearest normal roads are not evidence of the off-hours speed.
    merged = line_merge(geometry)
    base_signs = []
    # Only one continuous line gives this method an unambiguous pair of endpoints.
    if merged.geom_type == "LineString":
        ends = [Point(merged.coords[0]), Point(merged.coords[-1])]
        for s in nearby:
            p = s["properties"]
            if (p.get("sign_type") == "Standard Speed Limit" and not p.get("variable_calendar_type")
                    and road_name(s) in names):
                if min(s["geometry"].distance(end) for end in ends) <= SIGN_DISTANCE_M:
                    raw_speed = str(p.get("speed") or "")
                    if raw_speed.isdigit() and 0 < int(raw_speed) <= 200:
                        base_signs.append(s)
    speeds = {int(s["properties"]["speed"]) for s in base_signs}
    # Disagreeing standard signs cannot establish a single off-hours speed.
    # This sign path accepts one distinct speed even if only one endpoint has
    # a sign; the adjacent-road fallback below specifically requires both ends.
    base = next(iter(speeds)) if len(speeds) == 1 else None
    adjacent_ids = []
    # Where a standard sign is missing, require the normal road directly
    # connected at BOTH ends to agree. A merely nearby road cannot supply this.
    # The empty-speeds check also prevents adjacent roads from overriding a
    # conflict between standard signs that were actually found.
    if base is None and not speeds and merged.geom_type == "LineString" and normal_tree is not None:
        ends_speeds = []
        for end in ends:
            # Use the direction of the piece at this endpoint, allowing a group
            # to bend without borrowing a normal road for the opposite direction.
            direction = min(group, key=lambda r: r.geometry.distance(end)).direction
            neighbors = [normal_roads[int(i)] for i in normal_tree.query(end, predicate="dwithin", distance=0.2)
                         if normal_roads[int(i)].direction is not None and direction is not None
                         and angular_difference(normal_roads[int(i)].direction, direction) <= 45
                         and normal_roads[int(i)].geometry.boundary.distance(end) <= 0.2]
            ends_speeds.append({r.base_speed for r in neighbors})
            adjacent_ids.extend(r.id for r in neighbors)
        # Both ends must yield the same single speed. Missing or contradictory
        # neighbours cannot establish the group's normal speed.
        # For example, [{60}, {60}] agrees on 60; [{60, 80}, {60}] is ambiguous.
        if len(ends_speeds[0]) == 1 and ends_speeds[0] == ends_speeds[1]:
            value = next(iter(ends_speeds[0]))
            # Do not infer a normal speed below the group's signed conditional speed.
            if value is not None and value >= first.source_speed:
                base = value
    evidence.update({"reason": "matched" if base is not None else "off_hours_speed_unconfirmed",
                     "base_sign_ids": sorted(s["id"] for s in base_signs),
                     "base_adjacent_zone_ids": sorted(set(adjacent_ids)), "base_speed_kmh": base})
    # A confirmed active schedule remains useful even when its off-hours speed
    # is unknown; the backend must distinguish those two pieces of evidence.
    for road in group:
        road.rules, road.base_speed, road.status, road.evidence = rules, base, "scheduled", evidence


def build(zones=RAW_DIR / "speed-zones.geojson", signs=RAW_DIR / "speed-signs.geojson", output=PROCESSED_DIR, *, as_of: date | None = None):
    """Build road rows, scheduled rules, and a matching report from local sources.

    Args:
        zones: Path to the statewide speed-zone GeoJSON FeatureCollection.
        signs: Path to the speed-sign GeoJSON FeatureCollection.
        output: Directory for the two CSV files and ``report.json``.
        as_of: Sign eligibility date; defaults to today's Melbourne local date.

    Returns:
        A dictionary of source, accepted, skipped, status, and rule counters.

    Raises:
        ValueError: A usable source speed-zone UUID occurs more than once.
        Exception: Propagates I/O, parsing, and unexpected geometry failures.

    Side effects:
        Reads the local snapshots, creates ``output``, and replaces
        ``road_speed.csv``, ``speed_rule.csv``, and ``report.json``. Each file is
        prepared as ``.part`` and renamed individually after processing; this
        is not an atomic publication of the entire directory. Failures may
        leave partial files or, during renames, outputs from different builds.
        Does not load PostgreSQL or update a deployed backend.

    A second pass bounds memory: baseline roads need not stay in memory merely
    to be written. Only normals within twice the sign search distance can beat
    a conditional road in the nearest-sign assignment or evidence its endpoints.
    """
    # Build flow: export baseline roads, retain nearby matching evidence, parse
    # and assign signs, resolve connected groups, then publish CSVs and the report.
    output = Path(output)
    # as_of selects eligible source signs; it is not the time of a driver's trip
    # and does not decide whether a school/shop timetable is active right now.
    as_of = as_of or datetime.now(ZoneInfo("Australia/Melbourne")).date()
    output.mkdir(parents=True, exist_ok=True)
    # This list includes all usable non-baseline zones, even unsupported ones
    # that will ultimately remain unresolved rather than gain a time rule.
    conditional = []
    # Counter starts unseen keys at zero, so each rejection reason can be counted
    # only when it occurs without declaring every possible reason up front.
    counts = Counter()
    seen_ids = set()
    # Pass 1 writes accepted baseline rows immediately and retains other usable
    # zones for sign matching. The existing final CSV is not overwritten yet.
    with (output / "road_speed.csv.part").open("w", newline="") as road_file:
        writer = csv.writer(road_file)
        writer.writerow(ROAD_FIELDS)
        for feature in features(zones):
            counts["source_rows"] += 1
            properties = feature.get("properties") or {}
            # Reuse the original static selection and serialization before trying
            # the broader conditional path, preserving accepted baseline values.
            old = _road_speed(feature)
            ewkt = old.geometry_ewkt if old else _geometry_ewkt(feature.get("geometry"))
            try:
                source_id = str(UUID(str(feature.get("id") or "")))
            except ValueError:
                counts["skipped_invalid_id"] += 1
                counts["skipped_rows"] += 1
                continue
            if ewkt is None:
                counts["skipped_invalid_geometry"] += 1
                counts["skipped_rows"] += 1
                continue
            # Duplicate usable IDs would violate the database primary key; fail
            # the build instead of choosing one of the source records silently.
            if source_id in seen_ids:
                raise ValueError(f"Duplicate source speed-zone UUID: {source_id}")
            seen_ids.add(source_id)
            direction = DIRECTIONS.get(properties.get("direction"))
            if old:
                writer.writerow((source_id, old.speed_kmh, old.geometry_ewkt, True,
                                 direction, pg_array(["Normal Operation"]), "static"))
                counts["legacy_rows"] += 1
                counts["static_rows"] += 1
                continue
            # Keep export EWKT unchanged while creating a separate metre-based
            # geometry for matching signs and neighboring road endpoints.
            # from_wkt expects geometry text, so remove the EWKT SRID=4326; prefix.
            geometry = transform(PROJECT, from_wkt(ewkt.split(";", 1)[1]))
            conditions = tuple(_normal_conditions(properties.get("zone_conditions")))
            speed = str(properties.get("speed_limit") or "")
            # An unusable speed becomes None, not zero. Keep the zone itself so
            # the report can show the missing source value and final status.
            speed = int(speed) if speed.isdigit() and 0 < int(speed) <= 200 else None
            conditional.append(Road(source_id, speed, ewkt, geometry, direction,
                                    conditions, False, note=str(properties.get("zone_conditions_other") or "")))

        # Keep exact distance searches in metres. The second index below uses
        # longitude/latitude only to avoid projecting obviously distant roads.
        conditional_tree = STRtree([r.geometry for r in conditional])
        # An expanded geographic envelope is only a coarse prefilter. Within
        # Victoria 0.001 degrees exceeds 60 metres on both axes; the following
        # projected test determines actual inclusion. It retains whole roads.
        geographic_tree = STRtree([from_wkt(r.ewkt.split(";", 1)[1]) for r in conditional])
        normal_roads = []
        # Pass 2 keeps only nearby baseline roads in memory for sign ownership
        # and endpoint evidence; their CSV rows were already written in pass 1.
        if conditional:
            for feature in features(zones):
                old = _road_speed(feature)
                if old is None:
                    continue
                geometry = from_wkt(old.geometry_ewkt.split(";", 1)[1])
                x0, y0, x1, y1 = geometry.bounds
                if not len(geographic_tree.query(box(x0 - .001, y0 - .001, x1 + .001, y1 + .001))):
                    continue
                geometry = transform(PROJECT, geometry)
                # A sign can be 30 m from a zone and another 30 m from a competing
                # road, so keep baseline roads up to 60 m away for sign ownership.
                if len(conditional_tree.query(geometry, predicate="dwithin", distance=2 * SIGN_DISTANCE_M)):
                    # The empty EWKT saves a second export copy: this baseline
                    # road is now needed only for matching, not for another row.
                    normal_roads.append(Road(str(old.source_speed_zone_id), old.speed_kmh, "", geometry,
                        DIRECTIONS.get(feature["properties"].get("direction")), ("Normal Operation",),
                        True, old.speed_kmh, "static"))
        # Duplicate checking and the coarse prefilter are finished; release their
        # memory before building the sign and normal-road matching indexes.
        del seen_ids, geographic_tree
        # Establish sign ownership before grouping roads and resolving schedules.
        sign_rows = read_signs(signs, conditional_tree, counts, as_of)
        attach_signs(sign_rows, [*conditional, *normal_roads])
        # Keep each index beside the sequence it indexes. STRtree results are
        # integer positions, not source IDs, and must use that same row order.
        sign_tree = STRtree([s["geometry"] for s in sign_rows])
        normal_tree = STRtree([r.geometry for r in normal_roads])
        for group in components(conditional):
            resolve_group(group, sign_rows, sign_tree, normal_roads, normal_tree)
        audits = []
        # Export every retained conditional road, including unresolved cases.
        # Rules are separate rows linked by the road UUID and a per-road number.
        with (output / "speed_rule.csv.part").open("w", newline="") as rule_file:
            rule_writer = csv.writer(rule_file)
            rule_writer.writerow(RULE_FIELDS)
            for road in conditional:
                writer.writerow(road.row())
                counts["additional_rows"] += 1
                counts[f"{road.status}_rows"] += 1
                # "scheduled" means a timetable was confirmed, not that its limit
                # is active now. Its off-hours base speed can still be unknown.
                if road.status == "scheduled":
                    counts["scheduled_with_base" if road.base_speed is not None else "scheduled_without_base"] += 1
                # Keep one audit entry per source zone, even when several zones
                # shared the same group decision, so a CSV row can be traced back.
                audits.append({"zone_id": road.id, "condition": list(road.conditions), "status": road.status,
                               "source_speed_kmh": road.source_speed, **road.evidence})
                # Rule numbers restart at 1 for each road; the database identifies
                # a rule by the combination of road UUID and rule number.
                for i, rule in enumerate(road.rules, 1):
                    rule_writer.writerow((road.id, i, rule.speed_kmh, pg_array(rule.weekdays),
                                          rule.start_minute, rule.end_minute, rule.school_days,
                                          rule.public_holidays, pg_array(rule.source_sign_ids)))
                    counts["rule_rows"] += 1
    counts["road_rows"] = counts["legacy_rows"] + counts["additional_rows"]
    counts["normal_roads_retained_for_matching"] = len(normal_roads)
    counts["signs_in_matching_buffer"] = len(sign_rows)
    # The report records counts and matching evidence; it does not resolve a
    # driver's current limit or prove the source files have not changed.
    report = {"sources": {"zones": str(Path(zones).resolve()), "signs": str(Path(signs).resolve())},
              "coverage": "Victoria statewide; no LGA filter",
              "signs_as_of": as_of.isoformat(),
              "counts": dict(counts), "conditional_conditions": dict(Counter(
                  ", ".join(r.conditions) for r in conditional)), "conditional_roads": audits}
    (output / "report.json.part").write_text(json.dumps(report, indent=2) + "\n")
    # Publish completed files individually; these renames are not one transaction.
    for name in ("road_speed.csv", "speed_rule.csv", "report.json"):
        (output / f"{name}.part").replace(output / name)
    # Return the compact count summary to the CLI; detailed evidence is in report.json.
    return dict(counts)
