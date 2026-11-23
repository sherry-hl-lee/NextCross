"""Build the statewide crash dataset from local official CSV snapshots.

This pipeline stage selects casualty-positive crashes involving an 18–25-year-old
driver, assigns seasonal local-time periods, and runs one fixed DBSCAN build over
all selected dates and time periods. Involvement does not establish driver fault;
fatality and serious-injury totals cover all participants in qualifying crashes.

Outputs are ``crash_event.csv``, ``hotspot.csv``, and a checksum-bearing
``crash_report.json`` consumed by ``crash_storage``. Noise and unlocated events
remain event records. This module performs no database or HTTP operations.

The backend filters and aggregates the loaded events using their stored time
periods and cluster membership, without recomputing the clusters per request.
"""

import csv
from collections import Counter, defaultdict
from datetime import date, datetime, time
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from pyproj import Transformer
from pyproj.exceptions import ProjError
import sklearn
from sklearn.cluster import DBSCAN

from iteration3_v1_pipeline.operations_policy import baseline_range
from iteration3_v1_pipeline.settings import RAW_DIR, PROCESSED_DIR


# These policy values are recorded in each build; changing them requires a rebuild.
TIME_PERIODS = ("morning", "daytime", "night", "late_night")
TIME_POLICY_VERSION = "victoria-seasonal-walltime-v1"
COHORT_POLICY = "YOUNG_DRIVER_18_25 > 0 AND INJ_OR_FATAL > 0"
CLUSTER_POLICY_VERSION = "statewide-all-time-dbscan-sorted-ids-v1"
# A DBSCAN core point needs at least 3 samples within 100 m, including itself.
EPS_M, MIN_SAMPLES = 100, 3
# SOURCE_FIELDS validates incoming columns; the two output tuples set CSV order.
SOURCE_FIELDS = ("ACCIDENT_NO", "ACCIDENT_DATE", "ACCIDENT_TIME", "LATITUDE", "LONGITUDE",
                 "YOUNG_DRIVER_18_25", "INJ_OR_FATAL", "FATALITY", "SERIOUSINJURY", "OTHERINJURY")
EVENT_FIELDS = ("accident_id", "build_id", "hotspot_id", "crash_date", "time_period",
                "accident_point", "location_label", "fatalities", "serious_injuries")
HOTSPOT_FIELDS = ("hotspot_id", "build_id", "representative_point", "location_label")
SEASONS = {
    "summer": {"months": [12, 1, 2], "morning_start": 6, "night_start": 20},
    "autumn": {"months": [3, 4, 5], "morning_start": 7, "night_start": 18},
    "winter": {"months": [6, 7, 8], "morning_start": 7, "night_start": 17},
    "spring": {"months": [9, 10, 11], "morning_start": 6, "night_start": 19},
}
PROJECT = Transformer.from_crs(4326, 3111, always_xy=True)


def _date(value):
    """Normalize a date, datetime, ISO date string, or ``DD/MM/YYYY`` string.

    Datetimes contribute their existing calendar date without timezone conversion.

    Args:
        value: A date-like object or supported date string.

    Returns:
        A ``datetime.date`` value.

    Raises:
        ValueError: If a string does not represent a supported calendar date.
        AttributeError: If another object does not support string trimming.
    """
    # datetime is also a date instance, so remove its clock before the date check.
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    value = value.strip()
    try:
        return date.fromisoformat(value)
    except ValueError:
        # Accept the source's day-first form too, e.g. 15/01/2025 means 15 January.
        return datetime.strptime(value, "%d/%m/%Y").date()


def _time(value):
    """Parse an offset-free local crash time without UTC or DST conversion.

    Args:
        value: A ``datetime.time`` or ISO-style clock string; a one-digit hour
            is padded before parsing.

    Returns:
        An offset-free ``datetime.time`` value.

    Raises:
        ValueError: If the clock is invalid, its hour is outside 0–23, or a
            timezone offset is supplied.
        AttributeError: If another object does not support string trimming.
    """
    if not isinstance(value, time):
        value = value.strip()
        # Reject 24:00 instead of silently moving an accident to the following date.
        if not 0 <= int(value.split(":", 1)[0]) < 24:
            raise ValueError("Crash time hour must be in 0..23")
        # Official snapshots may use one-digit hours that ISO parsing would reject.
        if len(value.split(":", 1)[0]) == 1:
            value = "0" + value
        value = time.fromisoformat(value)
    # Keep the recorded local clock; applying an offset would change its period.
    if value.tzinfo is not None:
        raise ValueError("Crash time must be local wall time without an offset")
    return value


def classify_time_period(accident_date, accident_time):
    """Assign the build-time seasonal period from source local wall time.

    Boundaries are start-inclusive and end-exclusive. Late night starts at
    midnight, morning at the season's configured hour, daytime at noon, and
    night at the season's configured evening hour. These are broad seasonal
    categories, not calculated sunrise/sunset times or a timezone conversion.

    Args:
        accident_date: Date accepted by ``_date``; its month selects the season.
        accident_time: Local clock accepted by ``_time``.

    Returns:
        One of ``morning``, ``daytime``, ``night``, or ``late_night``.

    Raises:
        ValueError: If a date/time string is invalid or time has an offset.
        AttributeError: If a supplied object cannot be parsed as date/time input.
    """
    day, clock = _date(accident_date), _time(accident_time)
    # The same clock time can have a different label in different seasons.
    # For example, 06:30 is morning in January but late_night in July.
    season = next(s for s in SEASONS.values() if day.month in s["months"])
    # Ordered upper bounds put an exact boundary time in the following period.
    if clock < time(season["morning_start"]):
        return "late_night"
    if clock < time(12):
        return "morning"
    if clock < time(season["night_start"]):
        return "daytime"
    return "night"


def project_point(latitude, longitude):
    """Project a valid WGS 84 point to EPSG:3111 metres for clustering.

    Args:
        latitude: Numeric latitude in degrees or a numeric string.
        longitude: Numeric longitude in degrees or a numeric string.

    Returns:
        An ``(x, y)`` tuple in the Victorian projected coordinate system.

    Raises:
        ValueError: If coordinates are non-finite, outside global degree ranges,
            or yield non-finite projected coordinates.
        TypeError: If a value cannot be converted to a float.
        ProjError: If the coordinate transformation fails.
    """
    latitude, longitude = float(latitude), float(longitude)
    if not (math.isfinite(latitude) and math.isfinite(longitude)
            and -90 <= latitude <= 90 and -180 <= longitude <= 180):
        raise ValueError("Coordinates must be finite WGS 84 latitude/longitude")
    # DBSCAN needs distances in metres, not longitude/latitude degrees.
    x, y = PROJECT.transform(longitude, latitude, errcheck=True)
    if not math.isfinite(x) or not math.isfinite(y):
        raise ValueError("Coordinates cannot be projected to EPSG:3111")
    return x, y


def _point(longitude, latitude):
    """Return point EWKT for already validated longitude/latitude in EPSG:4326."""
    # PostGIS point text uses longitude first; SRID identifies the coordinate system.
    return f"SRID=4326;POINT({longitude!r} {latitude!r})"


def sha256(path):
    """Return a file's SHA-256 hex digest for source/output integrity checks.

    Args:
        path: Local file path to read in binary mode.

    Returns:
        A hexadecimal SHA-256 digest.

    Raises:
        OSError: If the file cannot be opened or read.
    """
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _totals(events):
    """Count an event iterable and sum its all-participant casualty metrics.

    Consumes the iterable once. Returns ``crash_count``, ``fatalities``, and
    ``serious_injuries``; each event must provide the two numeric injury fields.
    """
    # Some callers pass a generator; materialize it so both injury sums see all rows.
    events = list(events)
    return {"crash_count": len(events),
            "fatalities": sum(e["fatalities"] for e in events),
            "serious_injuries": sum(e["serious_injuries"] for e in events)}


def _location_label(row, geometry):
    """Format official road/crossroad fields without inferring a new location.

    Args:
        row: Accident Location mapping with road-name and road-type fields.
        geometry: Source road-geometry description used to label an intersection.

    Returns:
        A road/intersection label, a near-road label, or ``Unknown location``.
        The label does not change coordinates or snap events to a road.
    """
    road = " ".join(str(row.get(k) or "").strip() for k in ("ROAD_NAME", "ROAD_TYPE")).strip()
    crossroad = " ".join(str(row.get(k) or "").strip() for k in ("ROAD_NAME_INT", "ROAD_TYPE_INT")).strip()
    # A nearby crossroad is not necessarily an intersection at the accident point.
    intersection = "intersection" in geometry.lower() and not geometry.lower().startswith("not ")
    if road and crossroad:
        return road + (" × " if intersection else " / near ") + crossroad
    return road or crossroad or "Unknown location"


def build_crashes(source=RAW_DIR / "crashes.csv", output=PROCESSED_DIR,
                  start_date=None, end_date=None,
                  location_source=None):
    """Prepare one reproducible crash cohort and fixed statewide cluster build.

    Select records in the inclusive date range with ``YOUNG_DRIVER_18_25 > 0``
    and ``INJ_OR_FATAL > 0``. This means young-driver involvement, not fault or
    young-driver-only casualties. Fatalities and serious injuries retain the
    source's all-participant totals. Other injuries validate the casualty total
    but are not an output metric.

    Required source columns are checked. Invalid IDs/counts/dates/times and every
    occurrence of a duplicate source ID are excluded and counted, not imputed.
    Invalid coordinates instead produce retained events with a null point.
    Accident Location labels join by accident ID; conflicts keep the original
    crash road label. Source CSVs remain the authoritative audit inputs.

    Time periods are assigned once using ``classify_time_period``. Mappable
    events are sorted by accident ID and clustered together across all selected
    dates/periods with EPSG:3111 DBSCAN (100 m neighbours, minimum 3 samples).
    The 100 m value does not bound a whole cluster's radius. Noise keeps a point
    and null hotspot ID; unlocated events have both null. Each representative
    is an actual member nearest the coordinate-wise projected median, with ID
    order resolving ties, rather than a surveyed intersection or new centroid.

    Args:
        source: Official crash CSV snapshot; defaults to this iteration's raw
            ``crashes.csv``.
        output: Directory for prepared CSVs and ``crash_report.json``.
        start_date: Inclusive first date accepted by ``_date``.
        end_date: Inclusive last date accepted by ``_date``.
        location_source: Official Accident Location CSV; ``None`` uses this
            iteration's raw ``accident-location.csv``.

    Returns:
        The report dictionary: source hashes, policy-derived build ID, selected
        and excluded totals, cluster/time summaries, and output hashes/counts.

    Raises:
        ValueError: If date bounds are invalid/reversed or required columns are
            missing. Invalid individual crash values are reported as above.
        Exception: Propagates file, CSV, location-row, and clustering failures.

    Side effects:
        Creates the output directory and replaces two prepared CSVs, then the
        manifest last, through per-file ``.part`` renames. Publication is not
        atomic across all three files; the loader checks hashes, headers,
        counts, and matching build IDs before replacing database data. Pending
        temporary files are removed on exit. No database tables are changed.
    """
    source, output = Path(source), Path(output)
    location_source = Path(location_source) if location_source is not None else RAW_DIR / "accident-location.csv"
    # Keep the established baseline stable across New Year. The operations
    # layer supplies an explicit five-year range for a reviewed annual release.
    default_start, default_end = baseline_range()
    start_date = default_start if start_date is None else start_date
    end_date = default_end if end_date is None else end_date
    start_date, end_date = _date(start_date), _date(end_date)
    if start_date > end_date:
        raise ValueError("start_date must not follow end_date")
    # Identify the build by its actual source bytes and recorded processing policy.
    # A reordered source file gets a new build ID even if its events are the
    # same: the ID fingerprints the input snapshot, not just the selected rows.
    policy = {
        "period_start": start_date.isoformat(), "period_end": end_date.isoformat(),
        "region": "Victoria", "cohort_policy": COHORT_POLICY,
        "source_sha256": sha256(source), "location_source_sha256": sha256(location_source),
        "eps_m": EPS_M, "min_samples": MIN_SAMPLES, "crs_epsg": 3111,
        "time_policy_version": TIME_POLICY_VERSION, "cluster_policy_version": CLUSTER_POLICY_VERSION,
        "sklearn_version": sklearn.__version__,
    }
    # Sorted JSON keys make the fingerprint independent of dictionary key order.
    build_id = hashlib.sha256(json.dumps(policy, sort_keys=True).encode()).hexdigest()
    # Keep source-level audit counts separate from reasons for rejecting candidates.
    counts = Counter({key: 0 for key in ("source_rows", "source_invalid_date_rows", "source_missing_id_rows",
        "period_rows", "young_involved_before_casualty_filter", "zero_casualty_excluded",
        "invalid_cohort_count_rows", "source_duplicate_ids")})
    exclusions = Counter({key: 0 for key in ("missing_id_rows", "duplicate_id_rows", "invalid_count_rows", "invalid_time_rows")})
    examples, identifiers, years = defaultdict(list), Counter(), Counter()
    # Count the complete source before cohort filtering: sparse young-driver
    # events alone cannot establish that all 60 source months are available.
    months = Counter()
    selected, source_min, source_max = [], None, None
    # First pass: audit the whole snapshot and collect in-period young-driver cases.
    with source.open(encoding="utf-8-sig", newline="") as stream:
        # utf-8-sig tolerates a BOM that would otherwise become part of the first header.
        reader = csv.DictReader(stream)
        missing = set(SOURCE_FIELDS) - set(reader.fieldnames or ())
        # A missing required column invalidates the input contract for the whole file.
        if missing:
            raise ValueError(f"Missing crash source columns: {', '.join(sorted(missing))}")
        source_columns = reader.fieldnames
        for row in reader:
            counts["source_rows"] += 1
            identifier = (row["ACCIDENT_NO"] or "").strip()
            # Count IDs before filtering so a duplicate outside the period still counts.
            identifiers[identifier] += 1
            if not identifier:
                counts["source_missing_id_rows"] += 1
            try:
                day = _date(row["ACCIDENT_DATE"])
            except (ValueError, TypeError, AttributeError):
                # An invalid date cannot be assigned to the requested period or a year.
                counts["source_invalid_date_rows"] += 1
                continue
            # These coverage statistics describe all parseable dates, before filtering.
            years[str(day.year)] += 1
            months[day.strftime("%Y-%m")] += 1
            source_min = day if source_min is None else min(day, source_min)
            source_max = day if source_max is None else max(day, source_max)
            if not start_date <= day <= end_date:
                continue
            counts["period_rows"] += 1
            try:
                young = int(row["YOUNG_DRIVER_18_25"])
                if young < 0:
                    raise ValueError("Negative young driver count")
            except (ValueError, TypeError):
                counts["invalid_cohort_count_rows"] += 1
                continue
            # Negative counts were rejected above, so this means one or more drivers.
            if young:
                counts["young_involved_before_casualty_filter"] += 1
                # Retain the original row for casualty, time and coordinate validation.
                selected.append((identifier, day, row))
    # Reject every occurrence of a repeated ID instead of choosing an arbitrary row.
    duplicates = {key for key, value in identifiers.items() if key and value > 1}
    # This counts distinct repeated IDs, not the number of affected candidate rows.
    counts["source_duplicate_ids"] = len(duplicates)
    events = []

    def exclude(reason, identifier):
        """Record a cohort exclusion and retain up to ten IDs for its audit."""
        exclusions[reason] += 1
        if len(examples[reason]) < 10:
            examples[reason].append(identifier)

    missing_coordinates = []
    # Second pass: validate candidate IDs, casualty counts and local accident times.
    for identifier, day, row in selected:
        # Each continue rejects this candidate once, recording its first failure reason.
        if not identifier:
            exclude("missing_id_rows", identifier)
            continue
        if identifier in duplicates:
            # Even a valid-looking copy is excluded when its source ID is ambiguous.
            exclude("duplicate_id_rows", identifier)
            continue
        try:
            casualties, fatal, serious, other = (int(row[k]) for k in ("INJ_OR_FATAL", "FATALITY", "SERIOUSINJURY", "OTHERINJURY"))
            # These totals cover everyone in the accident, not only young drivers.
            # Other injuries help validate the total but are not an exported metric.
            if min(casualties, fatal, serious, other) < 0 or casualties != fatal + serious + other:
                raise ValueError("Inconsistent casualty counts")
        except (ValueError, TypeError):
            exclude("invalid_count_rows", identifier)
            continue
        # The cohort requires an injury or fatality; involvement alone is insufficient.
        if not casualties:
            counts["zero_casualty_excluded"] += 1
            if len(examples["zero_casualty_excluded"]) < 10:
                examples["zero_casualty_excluded"].append(identifier)
            continue
        try:
            period = classify_time_period(day, row["ACCIDENT_TIME"])
        except (ValueError, TypeError, AttributeError):
            exclude("invalid_time_rows", identifier)
            continue
        # One event represents one accident, even if several people were injured.
        # _geometry and _xy are build-only helpers; the remaining fields are exported.
        event = {"accident_id": identifier, "build_id": build_id, "hotspot_id": None,
                 "crash_date": day.isoformat(), "time_period": period, "accident_point": None,
                 "location_label": (row.get("ROAD_NAME") or "Unknown location").strip(),
                 "fatalities": fatal, "serious_injuries": serious,
                 "_geometry": row.get("ROAD_GEOMETRY") or "", "_xy": None}
        try:
            xy = project_point(row["LATITUDE"], row["LONGITUDE"])
            # Keep metric coordinates for clustering and WGS 84 text for DB storage.
            event["_xy"] = xy
            event["accident_point"] = _point(float(row["LONGITUDE"]), float(row["LATITUDE"]))
        except (ValueError, TypeError, OverflowError, ProjError):
            # Preserve the valid accident even when its position cannot be mapped.
            missing_coordinates.append(identifier)
        events.append(event)
    # Stable ID order makes clustering and representative tie-breaking reproducible.
    # Keep unlocated events in this order too so CSV output is stable even when
    # only a subset participates in the spatial calculation.
    events.sort(key=lambda e: e["accident_id"])
    # Both containers reference the same event dictionaries, so joined labels persist.
    by_id = {event["accident_id"]: event for event in events}
    # Sets distinguish genuinely conflicting labels from repeated identical rows.
    labels = defaultdict(set)
    location_rows = Counter()
    # Join official location labels by accident ID without changing event coordinates.
    with location_source.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"ACCIDENT_NO", "ROAD_NAME", "ROAD_TYPE", "ROAD_NAME_INT", "ROAD_TYPE_INT"}
        if required - set(reader.fieldnames or ()):
            raise ValueError("Missing Accident Location columns")
        for row in reader:
            identifier = row["ACCIDENT_NO"].strip()
            # Location rows for accidents outside the retained cohort are irrelevant.
            if identifier in by_id:
                location_rows[identifier] += 1
                labels[identifier].add(_location_label(row, by_id[identifier]["_geometry"]))
    for identifier, options in labels.items():
        # Conflicting labels leave the original crash-road fallback in place.
        if len(options) == 1:
            by_id[identifier]["location_label"] = next(iter(options))

    mappable = [e for e in events if e["_xy"] is not None]
    cluster_members = defaultdict(list)
    # Cluster all selected dates and periods together, once, using projected points.
    if mappable:
        # Each array row is [x, y]; dates and time labels are not clustering features.
        clustering = DBSCAN(eps=EPS_M, min_samples=MIN_SAMPLES, algorithm="kd_tree").fit_predict(
            np.asarray([e["_xy"] for e in mappable]))
        # fit_predict returns one group label per point in the original array order.
        for event, label in zip(mappable, clustering):
            # DBSCAN's negative label is noise; those events keep a null hotspot ID.
            if label >= 0:
                cluster_members[int(label)].append(event)
    # With no mappable events, the cluster list stays empty but events are still saved.
    hotspots, max_radius = [], 0.0
    for members in cluster_members.values():
        # The first member has the smallest accident ID in this cluster.
        # Use that source ID instead of exposing DBSCAN's temporary integer label.
        # Membership changes can change this ID in a later build; it is not a
        # permanent identifier for a physical intersection.
        hotspot_id = "hotspot:" + members[0]["accident_id"]
        coordinates = np.asarray([e["_xy"] for e in members])
        # axis=0 finds a separate median for x and y across all members.
        median = np.median(coordinates, axis=0)
        # Pick an actual accident nearest the median; no synthetic map point is added.
        # Sum squared x/y differences per member; argmin selects the nearest one.
        # An equal-distance tie uses the first member in the established ID order.
        representative = members[int(np.argmin(np.sum((coordinates - median) ** 2, axis=1)))]
        # A chain of neighbours can extend beyond the 100 m DBSCAN distance.
        max_radius = max(max_radius, float(np.sqrt(np.sum((coordinates - representative["_xy"]) ** 2, axis=1)).max()))
        hotspots.append({"hotspot_id": hotspot_id, "build_id": build_id,
                         "representative_point": representative["accident_point"],
                         "location_label": representative["location_label"]})
        # Store membership on every accident so later queries need no reclustering.
        for event in members:
            event["hotspot_id"] = hotspot_id
    hotspots.sort(key=lambda h: h["hotspot_id"])
    grouped = [e for e in events if e["hotspot_id"] is not None]
    noise = [e for e in mappable if e["hotspot_id"] is None]
    # Report retained, clustered, unclustered and unlocated totals separately.
    # Selected accidents = clustered + noise + missing-coordinate accidents.
    # Period/year summaries count accidents; they do not create separate clusters.
    # A location join counts as matched when at least one source row exists;
    # conflicting labels are a subset of those matches, not missing joins.
    report = {**policy, "build_id": build_id, "source_file": str(source.resolve()),
              "location_source_file": str(location_source.resolve()), "source_columns": source_columns,
              "source_date_min": source_min.isoformat() if source_min else None,
              "source_date_max": source_max.isoformat() if source_max else None,
              "source_rows_by_year": dict(sorted(years.items())),
              "source_rows_by_month": dict(sorted(months.items())), "counts": dict(counts),
              "excluded": dict(exclusions), "excluded_id_examples": dict(examples),
              "selected_totals": _totals(events), "mappable_totals": _totals(mappable),
              "clustered_totals": _totals(grouped), "noise_totals": _totals(noise),
              "missing_coordinate_totals": _totals(e for e in events if e["_xy"] is None),
              "missing_coordinate_id_examples": missing_coordinates[:10],
              "by_period": {p: _totals(e for e in events if e["time_period"] == p) for p in TIME_PERIODS},
              "by_year": {str(y): _totals(e for e in events if e["crash_date"].startswith(str(y)))
                          for y in range(start_date.year, end_date.year + 1)},
              "location_join": {"matched": len(labels), "missing": len(events) - len(labels),
                                "multiple_rows": sum(n > 1 for n in location_rows.values()),
                                "conflicting_labels": sum(len(v) > 1 for v in labels.values())},
              "hotspot_count": len(hotspots), "maximum_representative_radius_m": round(max_radius, 3),
              "policy": {
                  "scope": "Victoria statewide, inclusive date range; no LGA or bounding-box filter.",
                  "metrics": "Crash count and fatalities/serious injuries of all participants in qualifying crashes.",
                  "clustering": "One point per accident, sorted IDs; one statewide all-time DBSCAN. Noise and missing locations retained. eps is neighbor distance, not maximum cluster radius.",
                  "representative": "Actual member point nearest coordinate-wise projected median; ties use accident ID. Not a surveyed intersection coordinate.",
                  "time_basis": "Source local wall time, no timezone/DST conversion; start inclusive/end exclusive.",
                  "time_boundaries": SEASONS,
                  "invalid_rows": "All duplicate ID occurrences and invalid IDs/counts/dates/times excluded and reported; invalid coordinates retained as NULL.",
                  "location_labels": "Official location labels joined by ACCIDENT_NO; conflicting/missing joins use original crash road label, without inventing a location.",
              }, "files": {}}
    output.mkdir(parents=True, exist_ok=True)
    # Each pair remembers a temporary file and the final path it will replace.
    pending = []
    # Prepare every artifact before replacing any published output file.
    try:
        for name, fields, rows in (("hotspot", HOTSPOT_FIELDS, hotspots), ("crash_event", EVENT_FIELDS, events)):
            target = output / f"{name}.csv"
            part = target.with_suffix(".csv.part")
            pending.append((part, target))
            with part.open("w", encoding="utf-8", newline="") as stream:
                # Export only public fields; internal projected coordinates stay local.
                writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
                writer.writeheader()
                # CSV encodes None as an empty field, used for nullable DB columns.
                writer.writerows(rows)
            # The loader uses these byte counts and hashes to detect mixed artifacts.
            report["files"][name] = {"file": target.name, "rows": len(rows),
                                     "bytes": part.stat().st_size, "sha256": sha256(part)}
        target = output / "crash_report.json"
        part = target.with_suffix(".json.part")
        pending.append((part, target))
        # Refuse non-finite JSON numbers instead of publishing an invalid manifest.
        part.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        # Publish the manifest last; individual renames do not make the set atomic.
        # Publishing metadata last leaves mixed intermediate CSVs detectable by
        # the loader's hash, count and build-ID checks.
        for part, target in pending:
            part.replace(target)
    finally:
        # Remove unfinished temporary outputs on success or failure.
        for part, _ in pending:
            part.unlink(missing_ok=True)
    return report
