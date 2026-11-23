"""Build one year's school/public-holiday calendar from the DataVic CSV alone.

This module never connects to a database or fetches a page. A READY bundle is an
immutable CSV, source manifest and review report. Unpublished or unrecognised
source content produces a report only, so it cannot accidentally become an empty
but supposedly supported calendar year. READY covers this CSV source only; local
substitute and part-day holiday declarations are outside this source scope.
The separate calendar_review entry point supports explicitly reviewed inputs;
validate_bundle recognizes its retained-evidence schema-4 artifacts as well.

Flow: verify a saved calendar-download snapshot, select the requested year's
dates, validate complete terms and holiday families, then write a calendar.csv,
calendar_manifest.json and calendar_report.json bundle. calendar_storage.py
validates that bundle again before comparing or publishing it to PostgreSQL.
"""

import csv
from datetime import date, datetime, timedelta, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tempfile
import uuid

from iteration3_v1_pipeline.settings import PROCESSED_DIR


# The loader uses these same fields; one row is an inclusive term interval or
# a single holiday date. Source CSV columns below describe the unprocessed input.
CALENDAR_FIELDS = ("kind", "start_date", "end_date")
# Policy and source scope travel with each build so validation cannot silently
# mix an older interpretation of dates with the current CSV-only contract.
POLICY_VERSION = "victoria-datavic-csv-only-v3"
COVERAGE_SCOPE = "datavic_school_terms_and_public_holidays"
LIMITATIONS = ("Regional substitute and part-day public holidays published separately by Business Victoria are not collected.",)
# The uncertain kind asks the backend to account for local exceptions; it does
# not assert that the date is a public holiday at every location in Victoria.
KINDS = ("SCHOOL_TERM", "PUBLIC_HOLIDAY", "REGIONAL_HOLIDAY_UNCERTAIN")
SOURCE_NAMES = ("important_dates",)
CSV_FIELDS = ("arun", "dateType", "name", "important_date", "publisher", "description", "source")
# Required holiday families detect an incomplete source without fixing annual date counts.
# A family can have more than one date when an additional weekday holiday applies.
HOLIDAY_FAMILIES = {
    "new_year": r"new year's day", "australia": r"australia day", "labour": r"labour day",
    "good_friday": r"good friday", "easter_saturday": r"saturday before easter sunday",
    "easter_sunday": r"easter sunday", "easter_monday": r"easter monday", "anzac": r"anzac day",
    "monarch": r"(?:king|queen)'s birthday", "afl": r"friday before (?:the )?afl grand final",
    "cup": r"melbourne cup(?: day)?", "christmas": r"christmas day", "boxing": r"boxing day",
}
# Provisional wording prevents an otherwise valid-looking date from making the
# ordinary annual build READY. Reviewed bounded builds retain explicit uncertainty;
# keep missing/unconfirmed publication separate from malformed data.
_PENDING_RE = re.compile(r"to be (?:confirmed|announced|determined)|\btbc\b|\btba\b|not yet (?:available|published)|subject to (?:confirmation|(?:the )?AFL)", re.IGNORECASE)


class PendingCalendarError(ValueError):
    """Official content does not yet establish the complete requested year."""


def _hash(data):
    """Return the SHA-256 digest used to compare exact source or output bytes."""
    return hashlib.sha256(data).hexdigest()


def _year(value):
    """Validate the requested year without coercing strings or booleans."""
    # bool is an int subclass, but True/False must never select a calendar year.
    if type(value) is not int or not 1900 <= value <= 9998:
        raise ValueError("Calendar year must be an integer between 1900 and 9998")
    return value


def _utc(value):
    """Validate and normalize a recorded source-check time, without refreshing it."""
    if not isinstance(value, str):
        raise ValueError("A UTC verification timestamp is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("Invalid UTC verification timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0) or parsed > datetime.now(timezone.utc):
        raise ValueError("Verification timestamp must be UTC and not in the future")
    # Both Z and +00:00 are valid UTC spellings. Normalize before comparing
    # snapshot and prepared-bundle verification timestamps.
    return parsed.isoformat()


def _sources(value, year):
    """Validate the single CSV's fixed filename and official catalogue identity."""
    from iteration3_v1_pipeline.calendar_download import SOURCE_FILES, source_urls, validate_csv_url

    # Reuse acquisition's URL rules without making an HTTP request. The fixed
    # source set and filename also keep manually added files out of the build.
    if not isinstance(value, dict) or set(value) != set(SOURCE_NAMES):
        raise ValueError("Exactly one DataVic CSV source entry is required")
    source = value["important_dates"]
    if not isinstance(source, dict) or source.get("file") != SOURCE_FILES["important_dates"]:
        raise ValueError("Unexpected DataVic source filename")
    if not isinstance(source.get("resource_id"), str) or not source["resource_id"]:
        raise ValueError("Missing DataVic resource identity")
    validate_csv_url(source.get("url"), source["resource_id"], source.get("package_id"))
    if source.get("metadata_url") != source_urls(year)["important_dates"]:
        raise ValueError("Unexpected DataVic metadata URL")
    if source.get("acquisition") not in ("http", "local_csv"):
        raise ValueError("Unsupported DataVic CSV acquisition")
    # These checks validate the metadata shape. _read_snapshot later compares
    # the actual file bytes against its recorded size and digest.
    _utc(source.get("verified_at"))
    if type(source.get("bytes")) is not int or source["bytes"] <= 0:
        raise ValueError("Invalid DataVic source byte count")
    if not re.fullmatch(r"[0-9a-f]{64}", str(source.get("sha256", ""))):
        raise ValueError("Invalid DataVic source digest")
    return value


def _verification_time(sources):
    """Use the CSV's original source-check time as the bundle verification time."""
    return _utc(sources["important_dates"].get("verified_at"))


def _coverage_metadata():
    """Describe the same source limitations in ready, reused and failed results."""
    # Full-year dates do not imply local holiday coverage. Preserve this limit
    # in both the handoff manifest and every success/failure/reuse report.
    return {"coverage_scope": COVERAGE_SCOPE, "regional_holidays_complete": False,
            "limitations": list(LIMITATIONS)}


def _build_id(year, sources):
    """Identify a year, policy and source URL/content combination deterministically."""
    # Retrieval timestamps do not change the identity of identical source content.
    identity = {"year": year, "policy_version": POLICY_VERSION,
                "sources": {name: {key: sources[name][key] for key in ("url", "bytes", "sha256")}
                            for name in SOURCE_NAMES}}
    # Stable JSON key order makes the identifier independent of dictionary order.
    return _hash(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode())


def calendar_csv_bytes(rows):
    """Return the canonical CSV bytes used by the bundle and database checks."""
    # Fix column order, row order, line endings and encoding so a later DB read
    # can be serialized to exactly the same bytes for an integrity comparison.
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=CALENDAR_FIELDS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(sorted(rows, key=lambda row: tuple(row[key] for key in CALENDAR_FIELDS)))
    return stream.getvalue().encode("utf-8")


def _validate_rows(rows, year):
    """Allow term/holiday overlap, but reject ambiguity within one calendar kind."""
    grouped = {kind: [] for kind in KINDS}
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != set(CALENDAR_FIELDS):
            raise ValueError("Unexpected calendar row columns")
        kind = row["kind"]
        if kind not in KINDS:
            raise ValueError("Unknown calendar kind")
        try:
            start, end = (date.fromisoformat(row[key]) for key in CALENDAR_FIELDS[1:])
        except (ValueError, TypeError) as error:
            raise ValueError("Invalid calendar date") from error
        # Parsing alone accepts some alternative ISO forms; stored dates must
        # round-trip to the one YYYY-MM-DD spelling used in CSVs and manifests.
        if row["start_date"] != start.isoformat() or row["end_date"] != end.isoformat():
            raise ValueError("Calendar dates must use YYYY-MM-DD")
        if start.year != year or end.year != year or start > end:
            raise ValueError("Calendar interval is reversed or outside its year")
        if kind != "SCHOOL_TERM" and start != end:
            raise ValueError("Holiday markers must represent one whole date")
        key = (kind, start, end)
        if key in seen:
            raise ValueError("Duplicate calendar row")
        seen.add(key)
        grouped[kind].append((start, end))
    # A complete bundle under this policy has four term intervals and one Cup
    # uncertainty marker. An empty but syntactically valid year is not supported.
    if len(grouped["SCHOOL_TERM"]) != 4:
        raise ValueError("Exactly four school terms are required")
    if not grouped["PUBLIC_HOLIDAY"] or len(grouped["REGIONAL_HOLIDAY_UNCERTAIN"]) != 1:
        raise ValueError("Public holidays and exactly one Melbourne Cup uncertainty date are required")
    for kind, periods in grouped.items():
        ordered = sorted(periods)
        # Endpoints are inclusive: sharing the same boundary date is overlap.
        # Different kinds may overlap, such as a public holiday inside a term.
        if any(left[1] >= right[0] for left, right in zip(ordered, ordered[1:])):
            raise ValueError(f"Overlapping {kind} intervals")
    definite = {start for start, _ in grouped["PUBLIC_HOLIDAY"]}
    uncertain = {start for start, _ in grouped["REGIONAL_HOLIDAY_UNCERTAIN"]}
    if definite & uncertain:
        raise ValueError("A holiday cannot be both statewide and regionally uncertain")
    return {kind: len(grouped[kind]) for kind in KINDS}


def validate_bundle(path):
    """Validate a supported saved calendar bundle without database access.

    Args:
        path: Directory containing a schema-3 official-CSV bundle or a
            schema-4 reviewed bundle with its retained original/review files.

    Returns:
        ``(manifest, rows)`` for a verified READY or READY_BOUNDED bundle.
        A bounded result describes only its inclusive coverage dates; callers
        must not interpret its year key as evidence of full-year coverage.

    Raises:
        ValueError: For an unsupported policy/status, malformed manifest,
            inconsistent rows or missing/mismatched source and output evidence.

    Side effects:
        Reads local bundle files only. The schema-4 validator reconstructs the
        expected reviewed result from retained inputs; it does not fetch live
        official pages, publish rows, or assert that a backend is deployed.

    File hashes protect the handoff against a partial or mixed local build; they
    are consistency checks rather than a cryptographic signature of the source.

    Read calendar_manifest.json and calendar.csv from ``path``. Return their
    validated metadata and rows; reject invalid or incomplete artifacts with
    ValueError. No files are written and no source download is attempted.
    """
    path = Path(path)
    try:
        # 1. Require a completed bundle with a supported policy and explicitly
        # declared coverage; a failed candidate contains only a review report.
        manifest = json.loads((path / "calendar_manifest.json").read_text(encoding="utf-8"))
        if isinstance(manifest, dict) and manifest.get("schema_version") == 4:
            # Reviewed bundles have a different identity and evidence contract.
            # Dispatch before applying the original full-year schema-3 rules;
            # importing here also avoids a module-level circular dependency.
            from iteration3_v1_pipeline.calendar_review import validate_reviewed_bundle
            return validate_reviewed_bundle(path, manifest)
        if not isinstance(manifest, dict) or type(manifest.get("schema_version")) is not int or manifest["schema_version"] != 3 or manifest.get("status") != "READY":
            raise ValueError("A schema-version-3 CSV-only READY calendar manifest is required; rebuild older bundles")
        year = _year(manifest["year"])
        if manifest.get("policy_version") != POLICY_VERSION:
            raise ValueError("Unsupported calendar policy version")
        if (manifest.get("coverage_scope") != COVERAGE_SCOPE
                or manifest.get("regional_holidays_complete") is not False
                or manifest.get("limitations") != list(LIMITATIONS)):
            raise ValueError("Calendar manifest must disclose its CSV-only coverage and regional limitations")
        _utc(manifest["verified_at"])
        if manifest.get("coverage_start") != f"{year}-01-01" or manifest.get("coverage_end") != f"{year}-12-31":
            raise ValueError("Calendar coverage must describe the complete year")
        # 2. Tie the build ID and verification time back to the recorded source.
        # This validates the handoff metadata, not the remote source's current state.
        sources = _sources(manifest["sources"], year)
        if _utc(manifest["verified_at"]) != _verification_time(sources):
            raise ValueError("Calendar verification time must match its CSV source check")
        if manifest.get("build_id") != _build_id(year, sources):
            raise ValueError("Calendar build ID does not match its source and policy")
        # 3. Check the prepared CSV's identity and bytes before trusting its rows.
        entry = manifest["file"]
        if entry.get("file") != "calendar.csv" or entry.get("columns") != list(CALENDAR_FIELDS):
            raise ValueError("Unexpected prepared calendar file or columns")
        data = (path / "calendar.csv").read_bytes()
        if type(entry.get("bytes")) is not int or len(data) != entry["bytes"] or _hash(data) != entry.get("sha256"):
            raise ValueError("Calendar CSV does not match its manifest hash/size")
        reader = csv.DictReader(io.StringIO(data.decode("utf-8"), newline=""))
        if tuple(reader.fieldnames or ()) != CALENDAR_FIELDS:
            raise ValueError("Unexpected calendar CSV header")
        # 4. Recheck date semantics, counts and canonical formatting even when
        # the bytes match: a matching hash alone does not make a valid calendar.
        rows = list(reader)
        counts = _validate_rows(rows, year)
        if type(entry.get("rows")) is not int or entry["rows"] != len(rows) or manifest.get("record_counts") != counts:
            raise ValueError("Calendar record counts do not match the manifest")
        if calendar_csv_bytes(rows) != data:
            raise ValueError("Calendar CSV must use canonical row order and formatting")
        # 5. Every uncertain date must retain exactly one evidence entry, with
        # nonempty source/scope details for the backend and later review.
        evidence = manifest.get("evidence", {}).get("regional_dates")
        if not isinstance(evidence, list):
            raise ValueError("Regional date evidence is required")
        expected_dates = {row["start_date"] for row in rows if row["kind"] == "REGIONAL_HOLIDAY_UNCERTAIN"}
        evidence_dates = set()
        for item in evidence:
            if not isinstance(item, dict) or item.get("date") not in expected_dates or not isinstance(item.get("declarations"), list) or not item["declarations"]:
                raise ValueError("Invalid regional date evidence")
            if item["date"] in evidence_dates:
                raise ValueError("Duplicate regional date evidence")
            evidence_dates.add(item["date"])
            for declaration in item["declarations"]:
                if (not isinstance(declaration, dict) or declaration.get("source") not in SOURCE_NAMES
                        or not isinstance(declaration.get("scope"), str) or not declaration["scope"].strip()
                        or not isinstance(declaration.get("municipality"), str) or not declaration["municipality"].strip()
                        or type(declaration.get("half_day")) is not bool):
                    raise ValueError("Regional declarations must retain their source and scope")
        if evidence_dates != expected_dates:
            raise ValueError("Regional evidence does not cover every uncertain date")
        return manifest, rows
    except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, AttributeError) as error:
        raise ValueError(f"Invalid calendar bundle: {error}") from error


def _row(kind, start, end=None):
    """Create an export row; a holiday without an end covers its start date only."""
    return {"kind": kind, "start_date": start, "end_date": end or start}


def _csv_date(value):
    """DataVic's raw CSV is day/month/year; never guess or use DataStore dates."""
    if not isinstance(value, str) or not re.fullmatch(r"\d{1,2}/\d{1,2}/\d{4}", value):
        raise ValueError(f"Expected a DataVic date in day/month/year format: {value!r}")
    day, month, year = map(int, value.split("/"))
    try:
        return date(year, month, day)
    except ValueError as error:
        raise ValueError(f"Invalid DataVic calendar date: {value}") from error


def _parse_csv(text, year, *, deferred_afl_not_before=None):
    """Select only the two date types that define this pipeline's calendar.

    All target-year names must be recognised. Missing annual data is PENDING;
    malformed or contradictory published data is ERROR. Neither is publishable.
    Descriptions are kept as evidence rather than lost during normalization.

    ``deferred_afl_not_before`` is an explicit lower bound supplied only by the
    reviewed-calendar policy. It permits a missing or still-tentative AFL entry
    while preserving every other annual requirement. It is not a holiday date
    and does not itself establish coverage: calendar_review validates evidence
    and requires coverage_end to precede that bound. The ordinary builder leaves
    it unset and still requires a complete confirmed annual source.
    """
    # 1. Read the original CSV's date strings, selecting school/holiday records
    # for this year. Other important-date types do not contribute calendar rows.
    reader = csv.DictReader(io.StringIO(text, newline=""), strict=True)
    if tuple(reader.fieldnames or ()) != CSV_FIELDS:
        raise ValueError("Unexpected DataVic CSV header")
    selected, seen = [], set()
    try:
        for item in reader:
            if set(item) != set(CSV_FIELDS) or any(value is None for value in item.values()):
                raise ValueError("Incomplete or extra DataVic CSV columns")
            if item["dateType"] not in ("SCHOOL_TERM", "PUBLIC_HOLIDAY"):
                continue
            value = _csv_date(item["important_date"])
            if value.year != year:
                continue
            key = (item["dateType"], item["name"], value)
            if key in seen:
                raise ValueError(f"Duplicate DataVic calendar record: {item['name']} {value}")
            seen.add(key)
            selected.append((item, value))
    except csv.Error as error:
        raise ValueError(f"Malformed DataVic CSV: {error}") from error
    if not selected:
        raise PendingCalendarError(f"No published school terms or public holidays for {year}")

    # 2. Keep term starts/ends by term number and holidays by recognized family.
    # Preserve complete selected records and notes for the manifest's evidence.
    boundaries, holidays, notes, records = {}, {}, [], []
    for item, value in selected:
        name = item["name"]
        # Normalize typography, not meaning. An unfamiliar holiday name needs
        # review rather than being dropped from an allegedly complete year.
        # Retain the publisher's exact name and description in the evidence below.
        normalized = name.casefold().replace("’", "'").replace("‘", "'")
        family = next((key for key, pattern in HOLIDAY_FAMILIES.items() if re.fullmatch(pattern, normalized)), None)
        if item["dateType"] == "PUBLIC_HOLIDAY" and family == "afl" and deferred_afl_not_before is not None:
            if not _PENDING_RE.search(name + " " + item["description"]):
                raise ValueError("Deferred AFL review conflicts with a confirmed source date")
            if value < deferred_afl_not_before:
                raise ValueError("Unconfirmed AFL source date precedes its reviewed lower bound")
            # This is not a holiday date. The reviewed policy retains the
            # uncertainty and can cover only dates before the reviewed bound.
            continue
        if _PENDING_RE.search(name + " " + item["description"]):
            raise PendingCalendarError(f"Unconfirmed calendar source record: {name}: {item['description']}")
        records.append({**item, "date": value.isoformat()})
        if item["dateType"] == "SCHOOL_TERM":
            # A term is published as two source rows. The optional student-start
            # note is accepted in the name; the CSV date remains authoritative.
            match = re.fullmatch(r"Term ([1-4]) (\d{4}) - (Start|End) date(?: - (students start .+ in government schools))?", name)
            if not match or int(match[2]) != year or (match[4] and match[3] != "Start"):
                raise ValueError(f"Unrecognised school-term boundary: {name}")
            key = (int(match[1]), match[3])
            if key in boundaries:
                raise ValueError(f"Duplicate school-term boundary: {name}")
            boundaries[key] = value
            continue
        if family is None:
            raise ValueError(f"Unrecognised public-holiday name: {name}")
        holidays.setdefault(family, []).append((item, value))
        if item["description"]:
            notes.append({"source": "important_dates", "holiday": name, "date": value.isoformat(),
                          "text": item["description"]})

    # 3. Require all eight boundaries before turning them into four intervals.
    # Missing boundaries/families are PENDING; contradictory values are ERROR.
    required = {(number, boundary) for number in range(1, 5) for boundary in ("Start", "End")}
    if set(boundaries) != required:
        raise PendingCalendarError(f"All eight school-term boundaries for {year} are not yet available")
    rows = [_row("SCHOOL_TERM", boundaries[number, "Start"].isoformat(), boundaries[number, "End"].isoformat())
            for number in range(1, 5)]
    if any(boundaries[number, "End"] >= boundaries[number + 1, "Start"] for number in range(1, 4)):
        raise ValueError("School terms must be in order and must not overlap")
    missing = set(HOLIDAY_FAMILIES) - set(holidays)
    if deferred_afl_not_before is not None:
        missing.discard("afl")
    if missing:
        raise PendingCalendarError("Public-holiday data is incomplete; missing families: " + ", ".join(sorted(missing)))
    # Some holiday families may include an additional observed date. Apply the
    # supported-family rule instead of expecting a fixed total of holiday rows.
    for family, entries in holidays.items():
        # Only these families may supply both a nominal date and an additional
        # observed date; an extra date elsewhere is a source inconsistency.
        if len(entries) > 1 and family not in ("new_year", "australia", "christmas", "boxing"):
            raise ValueError(f"Unexpected multiple dates for public-holiday family: {family}")
        if len({value for _, value in entries}) != len(entries):
            raise ValueError(f"Duplicate dates for public-holiday family: {family}")

    # These relationships validate the source itself; they do not compare against
    # a generated reference CSV or silently repair a publisher's wrong dates.
    easter = {family: holidays[family][0][1] for family in
              ("good_friday", "easter_saturday", "easter_sunday", "easter_monday")}
    friday = easter["good_friday"]
    if friday.weekday() != 4 or any(easter[family] != friday + timedelta(days=offset)
            for offset, family in enumerate(("good_friday", "easter_saturday", "easter_sunday", "easter_monday"))):
        raise ValueError("Easter holiday dates must be consecutive Friday, Saturday, Sunday and Monday")
    for family, weekday in (("labour", 0), ("monarch", 0), ("afl", 4), ("cup", 1)):
        if family == "afl" and deferred_afl_not_before is not None:
            continue
        if holidays[family][0][1].weekday() != weekday:
            raise ValueError(f"Unexpected weekday for public-holiday family: {family}")

    # 4. Export definite dates and retain Cup Day separately as local uncertainty.
    # _parse_sources turns that evidence into REGIONAL_HOLIDAY_UNCERTAIN rows.
    evidence = {}
    for family, entries in holidays.items():
        for item, value in entries:
            if family == "cup":
                # Cup Day remains uncertain without point-in-polygon local rules.
                # The CSV's original local-substitution note is retained above.
                evidence[value.isoformat()] = [{"source": "important_dates",
                    "municipality": "Victoria with local exceptions", "holiday": item["name"],
                    "scope": "Melbourne Cup is subject to alternative local public holidays; statewide status is uncertain.",
                    "half_day": False}]
            else:
                rows.append(_row("PUBLIC_HOLIDAY", value.isoformat()))
    # These are input-record counts: school_terms counts eight boundary records,
    # while _validate_rows separately counts the four prepared term intervals.
    counts = {"important_dates": len(selected), "school_terms": len(boundaries),
              "public_holidays": sum(map(len, holidays.values()))}
    return rows, evidence, notes, records, counts


def _parse_sources(texts, year, *, deferred_afl_not_before=None):
    """Add the regional uncertainty marker to parsed source rows.

    Return ``(rows, evidence, counts)``; evidence contains regional declarations,
    holiday notes and retained input records. Forward the reviewed AFL lower
    bound without manufacturing a date; the outer reviewed builder owns its
    evidence and coverage checks. This helper performs no I/O.
    """
    rows, evidence, notes, records, counts = _parse_csv(
        texts["important_dates"], year, deferred_afl_not_before=deferred_afl_not_before)
    # The only uncertainty marker comes from Melbourne Cup in the official CSV.
    # No locally maintained regional declarations are added to this output.
    rows.extend(_row("REGIONAL_HOLIDAY_UNCERTAIN", value) for value in sorted(evidence))
    return rows, {"regional_dates": [{"date": value, "declarations": declarations}
                                     for value, declarations in sorted(evidence.items())],
                  "public_holiday_notes": notes, "important_dates_records": records}, counts


def _read_snapshot(source_dir, year):
    """Read a saved acquisition manifest and verify its CSV before decoding it."""
    try:
        source = json.loads((source_dir / "sources.json").read_text(encoding="utf-8"))
        if (type(source.get("schema_version")) is not int or source["schema_version"] != 3
                or type(source.get("year")) is not int or source["year"] != year):
            raise ValueError("A schema-version-3 CSV-only snapshot for the requested year is required; older snapshots are unsupported")
        if not isinstance(source.get("snapshot_id"), str) or not source["snapshot_id"]:
            raise ValueError("Missing source snapshot ID")
        _utc(source["fetched_at"])
        sources = _sources(source["sources"], year)
        texts = {}
        for name, entry in sources.items():
            # Compare original bytes before removing a possible UTF-8 BOM for
            # parsing, so the checksum still identifies the downloaded file.
            data = (source_dir / entry["file"]).read_bytes()
            if len(data) != entry["bytes"] or _hash(data) != entry["sha256"]:
                raise ValueError(f"Source data does not match its recorded hash/size: {name}")
            # Accept a UTF-8 byte-order mark for parsing without changing the
            # raw bytes whose digest identifies the retained source.
            texts[name] = data.decode("utf-8-sig")
        return source, texts
    except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, AttributeError) as error:
        raise ValueError(f"Invalid calendar source snapshot: {error}") from error


def _diff(previous, rows, year):
    """Compare dates against a validated local build of the same year, if given."""
    if previous is None:
        return None
    manifest, old_rows = validate_bundle(previous)
    if manifest["year"] != year:
        raise ValueError("Previous bundle must cover the same calendar year")
    # Set differences describe added/removed intervals regardless of row order.
    # This local comparison neither reads PostgreSQL nor compares source metadata.
    old = {tuple(row[key] for key in CALENDAR_FIELDS) for row in old_rows}
    new = {tuple(row[key] for key in CALENDAR_FIELDS) for row in rows}
    return {"previous_build_id": manifest["build_id"],
            "added": [dict(zip(CALENDAR_FIELDS, row)) for row in sorted(new - old)],
            "removed": [dict(zip(CALENDAR_FIELDS, row)) for row in sorted(old - new)]}


def _write_json(path, value):
    """Write readable, consistently ordered JSON without nonstandard NaN values."""
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")


def build_calendar(source_dir, year, output=PROCESSED_DIR / "calendar", previous=None):
    """Prepare an immutable annual bundle, or a report-only failed candidate.

    ``previous`` optionally compares a validated local bundle of the same year.
    A ValueError identifies the saved failure report when source validation or
    parsing fails. No READY manifest is published in that case. Successful output
    includes ``input_dir``, the exact directory to pass to calendar-load.

    Args:
        source_dir: Saved calendar-download directory containing sources.json
            and the original important_dates.csv.
        year: Integer calendar year to prepare from the multi-year source CSV.
        output: Parent directory for immutable builds and failure reports.
        previous: Optional existing bundle for a local added/removed-date report.

    Returns:
        A JSON-serializable summary with coverage, counts, build ID, output path,
        reuse status and the optional difference. Detailed evidence is in the
        saved manifest; publishing to PostgreSQL is a separate load step.
    """
    source_dir, output = Path(source_dir), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    pending = None
    try:
        # 1. Validate the saved source and derive its stable build directory name.
        year = _year(year)
        source, texts = _read_snapshot(source_dir, year)
        build_id = _build_id(year, source["sources"])
        target = output / build_id
        if target.exists():
            # Reuse only after validating the existing artifacts. Identical input
            # does not overwrite the bundle or advance its verified_at timestamp.
            manifest, rows = validate_bundle(target)
            if manifest["build_id"] != build_id:
                raise ValueError("Existing build directory has a different build ID")
            return {**_coverage_metadata(), "status": "READY", "year": year, "build_id": build_id,
                    "input_dir": str(target.resolve()), "reused": True,
                    "record_counts": manifest["record_counts"], "diff": _diff(previous, rows, year)}
        # 2. Parse and validate the annual dates before preparing any loadable CSV.
        # The optional comparison is with a local bundle, not the current DB.
        rows, evidence, table_counts = _parse_sources(texts, year)
        counts = _validate_rows(rows, year)
        comparison = _diff(previous, rows, year)
        data = calendar_csv_bytes(rows)
        # 3. The manifest binds source evidence to these exact output bytes. The
        # report is a review summary; it cannot substitute for the load manifest.
        manifest = {**_coverage_metadata(), "schema_version": 3, "status": "READY", "year": year, "build_id": build_id,
                    "policy_version": POLICY_VERSION, "verified_at": _verification_time(source["sources"]),
                    "coverage_start": f"{year}-01-01", "coverage_end": f"{year}-12-31",
                    "sources": source["sources"], "source_snapshot_id": source["snapshot_id"],
                    "file": {"file": "calendar.csv", "columns": list(CALENDAR_FIELDS),
                             "rows": len(rows), "bytes": len(data), "sha256": _hash(data)},
                    "record_counts": counts, "evidence": evidence}
        report = {**_coverage_metadata(), "status": "READY", "year": year, "build_id": build_id,
                  "input_dir": str(target.resolve()), "source_dir": str(source_dir.resolve()),
                  "record_counts": counts, "source_table_rows": table_counts,
                  "pending": [], "errors": [], "diff": comparison, "reused": False}
        # 4. Stage and revalidate all files using the same check the loader uses.
        # A directory rename publishes the CSV, manifest and report as one bundle.
        pending = Path(tempfile.mkdtemp(prefix=".calendar-build-", dir=output))
        (pending / "calendar.csv").write_bytes(data)
        _write_json(pending / "calendar_report.json", report)
        _write_json(pending / "calendar_manifest.json", manifest)
        validate_bundle(pending)
        try:
            pending.rename(target)
        except OSError:
            # Another process may have completed the same immutable build first.
            if not target.is_dir():
                raise
            concurrent_manifest, _ = validate_bundle(target)
            if concurrent_manifest["build_id"] != build_id:
                raise ValueError("Concurrent build has a different build ID")
            report["reused"] = True
        return report
    except ValueError as error:
        # Failed candidates remain reviewable but have no READY manifest/CSV.
        # An unpublished year is PENDING; malformed or inconsistent data is ERROR.
        status = "PENDING" if isinstance(error, PendingCalendarError) else "ERROR"
        failed = output / ("failed-" + uuid.uuid4().hex)
        failed.mkdir()
        report_path = failed / "calendar_report.json"
        _write_json(report_path, {**_coverage_metadata(), "status": status, "year": year,
                    "source_dir": str(source_dir.resolve()),
                    "pending": [str(error)] if status == "PENDING" else [],
                    "errors": [str(error)] if status == "ERROR" else []})
        raise ValueError(f"{error}. Calendar report: {report_path}") from error
    finally:
        # Remove only this run's temporary directory after failure or a concurrent
        # build winning the rename. Previously published bundles stay intact.
        if pending is not None and pending.exists():
            shutil.rmtree(pending)
