"""Prepare, inspect and explicitly publish one immutable data candidate.

Preparation and validation do not connect to PostgreSQL. A candidate retains
original inputs, prepared outputs, producer policy and dependency lock, with
their file digests included in its manifest identity. Publication loads only the
selected domain; it never downloads sources or applies schema migrations.

The GitHub workflow archives the candidate and records a durable publication
intent before making a database write. ``workflow_support`` owns that external
evidence and reconciliation lifecycle; calling ``publish`` directly does not
create it. Production operators need the same durable evidence procedure, not
just a local report. A failed report after the loader starts is not proof that
the database rolled back, because the commit and report are separate writes.

``status`` reads database state and review thresholds without changing datasets.
Neither wall-clock rollover nor monitoring automatically publishes a new build.
"""

import argparse
from contextlib import contextmanager
import csv
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import requests
from iteration3_v1_pipeline.build import ROAD_FIELDS, RULE_FIELDS, build
from iteration3_v1_pipeline.calendar import validate_bundle, build_calendar
from iteration3_v1_pipeline.calendar_download import download_calendar
from iteration3_v1_pipeline.calendar_storage import load_calendar
from iteration3_v1_pipeline.crash import build_crashes, sha256, _date
from iteration3_v1_pipeline.crash_download import download_crashes
from iteration3_v1_pipeline.crash_storage import _prepared_inputs, load_crashes
from iteration3_v1_pipeline.download import download
from iteration3_v1_pipeline.load import load
from iteration3_v1_pipeline.operations_policy import annual_range, baseline_range, melbourne_today, read_policy, POLICY_PATH

DATASETS = ("speed", "crashes", "calendar")
# Application-owned email history is part of schema readiness, even though no
# candidate loader may replace it. The schema keeps its existing iteration name.
TABLES = ("road_speed", "speed_rule", "crash_build", "crash_event", "hotspot",
          "calendar_period", "calendar_coverage", "notification_event", "report_email_dispatch")
# A shared session lock serializes cooperating operations across all domains.
LOCK = int.from_bytes(b"OPS3", "big")


class OperationError(ValueError):
    """A non-sensitive, actionable error suitable for durable operation reports."""
    def __init__(self, code, message):
        """Keep a stable error code and an operator-safe message, never secrets."""
        super().__init__(message)
        self.code = code


def _json(path):
    """Read local JSON; propagate missing-file, decoding and JSON errors."""
    return json.loads(Path(path).read_text())


def _write(path, value):
    """Replace a JSON file via a sibling partial file, creating parent folders.

    Return None; propagate serialization or filesystem errors. Sorted keys make
    reports readable and non-finite JSON numbers are rejected. Replacement
    avoids exposing partially written JSON, but is not an external durable
    publication archive or a transaction with the database.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".part")
    partial.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")
    partial.replace(path)


def _now():
    """Return an aware UTC timestamp for evidence, independent of policy dates."""
    return datetime.now(timezone.utc).isoformat()


def _digest(value):
    """Hash canonical JSON, rejecting NaN/Infinity; return lowercase SHA-256."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def _hex(value, length, name):
    """Return a valid lowercase hexadecimal ID or raise safe INVALID_ID."""
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{%d}" % length, value):
        raise OperationError("INVALID_ID", f"{name} must be {length} lowercase hexadecimal characters")
    return value


def _files(directory):
    """Inventory candidate files as relative paths, sizes and SHA-256 digests.

    Exclude only the root manifest to avoid a self-referential digest. Reject
    symlinks and files outside raw/payload/producer with OperationError; other
    filesystem errors propagate. This reads bytes without modifying them.
    """
    result = {}
    for path in sorted(Path(directory).rglob("*")):
        if path.is_symlink():
            raise OperationError("UNSAFE_FILE", "Candidate symlinks are not supported")
        if path.is_file() and path != Path(directory) / "candidate_manifest.json":
            relative = path.relative_to(directory).as_posix()
            if relative.split("/")[0] not in ("raw", "payload", "producer"):
                raise OperationError("UNSAFE_FILE", "Candidate contains an unexpected file")
            result[relative] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
    return result


def _acquire(function, *args):
    """Call a downloader up to three times and return its unchanged result.

    Retry requests transport failures, HTTP 408/429 and server errors, sleeping
    one then two seconds. Propagate the last error, other HTTP errors, and all
    non-requests exceptions. The downloader owns its filesystem side effects;
    this wrapper does not log credentials or retry validation/build failures.
    """
    for attempt in range(3):
        try:
            return function(*args)
        except requests.RequestException as error:
            status = error.response.status_code if error.response is not None else None
            if attempt == 2 or (status is not None and status not in (408, 429) and status < 500):
                raise
            time.sleep(2 ** attempt)


def _speed_summary(payload):
    """Validate speed CSV structure/counts and return (counts, as-of period).

    Headers and row widths must match the loader contract and report counts.
    OperationError signals these mismatches; malformed report or file errors
    propagate. This does not duplicate PostgreSQL value/constraint validation.
    The process-wide CSV field limit is restored even when validation fails.
    """
    report = _json(payload / "report.json")
    counts = {}
    previous_limit = csv.field_size_limit()
    try:
        for name, fields in (("road_speed", ROAD_FIELDS), ("speed_rule", RULE_FIELDS)):
            path = payload / f"{name}.csv"
            csv.field_size_limit(max(previous_limit, path.stat().st_size))
            with path.open(newline="") as stream:
                reader = csv.DictReader(stream)
                if tuple(reader.fieldnames or ()) != fields:
                    raise OperationError("INVALID_HEADER", "Prepared speed header differs from the loader contract")
                count = 0
                for row in reader:
                    # DictReader uses a None key for surplus columns and None
                    # values for missing columns; either means a broken CSV row.
                    if None in row or None in row.values():
                        raise OperationError("INVALID_ROW", "Prepared speed CSV contains a malformed row")
                    count += 1
                key = "road_rows" if name == "road_speed" else "rule_rows"
                if count != report["counts"].get(key, 0):
                    raise OperationError("COUNT_MISMATCH", "Speed CSV and report counts disagree")
                counts[key] = count
    finally:
        csv.field_size_limit(previous_limit)
    counts["unresolved_rows"] = report["counts"].get("unresolved_rows", 0)
    return counts, {"as_of": date.fromisoformat(report["signs_as_of"]).isoformat()}


def _summary(dataset, payload):
    """Validate a known dataset's payload and return its counts and period.

    Callers restrict dataset to DATASETS. Domain validators retain their own
    checksum/structure checks and exceptions; this adapter performs no writes
    and exposes comparable manifest fields without merging dataset domains.
    """
    if dataset == "speed":
        return _speed_summary(payload)
    if dataset == "crashes":
        report, files = _prepared_inputs(payload)
        return {"event_rows": files["crash_event"][2], "hotspot_rows": files["hotspot"][2],
                "missing_point_rows": report["missing_coordinate_totals"]["crash_count"]}, {
                "start": report["period_start"], "end": report["period_end"], "build_id": report["build_id"]}
    manifest, rows = validate_bundle(payload)
    return {"rows": len(rows), **manifest["record_counts"]}, {
        "year": manifest["year"], "start": manifest["coverage_start"], "end": manifest["coverage_end"],
        "build_id": manifest["build_id"], "status": manifest["status"]}


def _quality(directory, dataset, counts, period):
    """Return PASS/BLOCKED issues and warnings under the current runtime policy.

    Counts and period come from a validated payload. Speed must have roads;
    crashes also need all source months, confirmed source periods and an
    acceptable missing-coordinate fraction. The narrow legacy baseline
    exception warns without fabricating metadata. Calendar readiness is
    validated by its bundle validator, not repeated here. File errors propagate.
    """
    issues, warnings = [], []
    if dataset == "speed" and counts["road_rows"] <= 0:
        issues.append("EMPTY_SPEED_DATA")
    if dataset == "crashes":
        report = _json(directory / "payload/crash_report.json")
        if counts["event_rows"] <= 0:
            issues.append("EMPTY_CRASH_COHORT")
        first, last = date.fromisoformat(period["start"]), date.fromisoformat(period["end"])
        months = [f"{year}-{month:02}" for year in range(first.year, last.year + 1) for month in range(1, 13)]
        distribution = report.get("source_rows_by_month")
        if distribution is None:
            # Older frozen reports lack this summary. Inspect the retained raw
            # source without rebuilding or changing the accepted event outputs.
            distribution = {}
            with (directory / "raw/crashes.csv").open(encoding="utf-8-sig", newline="") as stream:
                for row in csv.DictReader(stream):
                    try:
                        month = _date(row["ACCIDENT_DATE"]).strftime("%Y-%m")
                    except (ValueError, TypeError):
                        continue
                    distribution[month] = distribution.get(month, 0) + 1
        if any(distribution.get(month, 0) <= 0 for month in months):
            issues.append("MISSING_SOURCE_MONTHS")
        # Monthly presence and the declared reporting end complement each
        # other: some rows in a month do not prove the source period is complete.
        for name in ("crashes", "accident-location"):
            metadata = _json(directory / "raw" / f"{name}.source.json")
            end = metadata.get("resource", {}).get("reporting_period_end")
            try:
                complete = date.fromisoformat(str(end)[:10]) >= last
            except ValueError:
                complete = False
            if not complete:
                if ((first, last) == baseline_range() and name == "accident-location"
                        and "resource" not in metadata and metadata.get("resource_id")
                        == "e1beb92b-5836-448a-9769-e7aa6a9e7413"):
                    # The original accepted location sidecar predates the
                    # current downloader and has no reporting period. Preserve
                    # it verbatim; don't invent metadata during baseline adoption.
                    warnings.append("LEGACY_BASELINE_LOCATION_PERIOD_UNCONFIRMED")
                else:
                    issues.append("SOURCE_PERIOD_UNCONFIRMED:" + name)
        maximum = read_policy()["crashes"]["maximum_missing_coordinate_fraction"]
        if counts["event_rows"] and counts["missing_point_rows"] / counts["event_rows"] > maximum:
            issues.append("EXCESS_MISSING_COORDINATES")
    return {"status": "PASS" if not issues else "BLOCKED", "issues": issues, "warnings": warnings}


def _comparison(manifest, baseline):
    """Return count deltas against a verified candidate, or None without one.

    Reject a baseline for another domain. These deltas support human review;
    they are not proof that the supplied baseline is currently in production.
    """
    if baseline is None:
        return None
    old = validate_candidate(baseline)
    if old["dataset"] != manifest["dataset"]:
        raise OperationError("WRONG_BASELINE", "Baseline belongs to a different dataset")
    return {"candidate_id": old["candidate_id"], "period": old["period"],
            "count_changes": {key: value - old["counts"].get(key, 0) for key, value in manifest["counts"].items()}}


def prepare(dataset, commit, output, *, year=None, release_year=None, as_of=None,
            calendar_source_dir=None, calendar_review_dir=None, baseline=None,
            frozen_input=None, frozen_source_dir=None):
    """Build or adopt one self-contained candidate without opening a database.

    Args:
        dataset: One of speed, crashes or calendar; other domains are untouched.
        commit: Full lowercase 40-character producer commit supplied by caller.
        output: New directory for the completed immutable candidate.
        year: Explicit calendar year when preparing calendar data.
        release_year: Due September crash release year, beginning in 2027.
        as_of: Optional ISO speed-sign date; defaults to Melbourne's local date.
        calendar_source_dir: Optional original calendar snapshot directory.
        calendar_review_dir: Optional reviewed original/corrected/provenance
            bundle, mutually exclusive with calendar_source_dir.
        baseline: Optional archived candidate for same-domain count comparison.
        frozen_input: Existing 2021–2025 crash outputs to adopt unchanged.
        frozen_source_dir: Matching original crash inputs and source metadata;
            required with frozen_input and incompatible with release_year.

    Returns:
        The manifest plus status='prepared' and the completed input_dir. Its
        candidate_id covers manifest metadata and the inventory of file hashes.

    Raises:
        OperationError: For invalid configuration, existing output, mismatched
            contracts or blocked quality. Underlying download/build, input and
            filesystem exceptions propagate after attempted failure reporting.

    Side effects:
        May download the selected domain and writes local candidate files.
        Work is staged beside output and renamed only after validation. Errors
        inside preparation leave a sibling <output>-failure.json; early argument
        rejection occurs before that report lifecycle. No external archive, publication
        intent, schema change or database write is performed here.
    """
    if dataset not in DATASETS:
        raise OperationError("INVALID_DATASET", "Select speed, crashes or calendar")
    _hex(commit, 40, "commit")
    output = Path(output).resolve()
    if output.exists():
        raise OperationError("OUTPUT_EXISTS", "Choose a new candidate directory; saved candidates are immutable")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = "configuration"
    failure = output.with_name(output.name + "-failure.json")
    calendar_failure = None
    try:
        # Stage on the destination filesystem so an incomplete build cannot be
        # mistaken for a completed candidate at the requested output path.
        with tempfile.TemporaryDirectory(prefix=".candidate-", dir=output.parent) as temporary:
            work = Path(temporary)
            bundle = work / "bundle"
            raw, payload, producer = (bundle / name for name in ("raw", "payload", "producer"))
            raw.mkdir(parents=True)
            producer.mkdir()
            # Retain the producer's policy for review/reproduction. Publication
            # still applies current runtime policy rather than trusting old gates.
            shutil.copy2(POLICY_PATH, producer / "operations.json")
            shutil.copy2(ROOT / "requirements.lock", producer / "requirements.lock")
            stage = dataset + "_download"
            if dataset == "speed":
                _acquire(download, raw)
                stage = "speed_build"
                build(raw / "speed-zones.geojson", raw / "speed-signs.geojson", payload,
                      as_of=date.fromisoformat(as_of) if as_of else melbourne_today())
            elif dataset == "crashes":
                if frozen_input or frozen_source_dir:
                    # Adoption preserves the already accepted clustering and
                    # input bytes; re-fetching/rebuilding would create a new build.
                    if not frozen_input or not frozen_source_dir or release_year is not None:
                        raise OperationError("FROZEN_INPUTS_REQUIRED", "Adopt the existing baseline with both frozen directories and no release year")
                    for filename in ("crashes.csv", "accident-location.csv", "crashes.source.json", "accident-location.source.json"):
                        shutil.copy2(Path(frozen_source_dir) / filename, raw / filename)
                    payload.mkdir()
                    for filename in ("crash_event.csv", "hotspot.csv", "crash_report.json"):
                        shutil.copy2(Path(frozen_input) / filename, payload / filename)
                    saved = _json(payload / "crash_report.json")
                    first, last = baseline_range()
                    if (saved["period_start"], saved["period_end"]) != (first.isoformat(), last.isoformat()):
                        raise OperationError("WRONG_BASELINE", "Frozen adoption is restricted to the existing 2021–2025 baseline")
                else:
                    # Check the release date before spending work on a download;
                    # preparation alone never changes the active crash window.
                    first, last = annual_range(release_year)
                    _acquire(download_crashes, raw)
                    stage = "crash_build"
                    build_crashes(raw / "crashes.csv", payload, start_date=first, end_date=last,
                                  location_source=raw / "accident-location.csv")
            else:
                if type(year) is not int or not 1900 <= year <= 9998:
                    raise OperationError("YEAR_REQUIRED", "Calendar candidate requires an explicit year")
                if calendar_source_dir and calendar_review_dir:
                    raise OperationError("AMBIGUOUS_SOURCE", "Choose original or reviewed calendar inputs")
                if calendar_review_dir:
                    from iteration3_v1_pipeline.calendar_review import build_reviewed_calendar
                    review = Path(calendar_review_dir)
                    stage = "calendar_review"
                    try:
                        result = build_reviewed_calendar(review / "original", year, review / "corrected.csv",
                                                         review / "review.json", output=work / "calendar-build")
                    except ValueError:
                        calendar_failure = _saved_calendar_failure(work)
                        raise
                else:
                    if calendar_source_dir:
                        source = Path(calendar_source_dir)
                    else:
                        source = Path(_acquire(download_calendar, year, work / "calendar-raw")["source_dir"])
                    (raw / "original").mkdir()
                    # Copy only the source contract, not unrelated local files
                    # that happen to share the operator's snapshot directory.
                    for filename in ("important_dates.csv", "sources.json"):
                        shutil.copy2(source / filename, raw / "original" / filename)
                    stage = "calendar_build"
                    try:
                        result = build_calendar(raw / "original", year, output=work / "calendar-build")
                    except ValueError:
                        calendar_failure = _saved_calendar_failure(work)
                        raise
                shutil.copytree(result["input_dir"], payload)
            stage = "validation"
            counts, period = _summary(dataset, payload)
            manifest = {"schema_version": 1, "dataset": dataset, "commit": commit, "created_at": _now(),
                        "last_checked_at": _now(), "release_year": release_year if dataset == "crashes" else None,
                        "counts": counts, "period": period, "quality": _quality(bundle, dataset, counts, period)}
            manifest["comparison"] = _comparison(manifest, baseline)
            manifest["files"] = _files(bundle)
            # The ID is computed before inserting itself; validation reconstructs
            # this exact identity instead of trusting a caller-provided filename.
            manifest["candidate_id"] = _digest(manifest)
            _write(bundle / "candidate_manifest.json", manifest)
            validate_candidate(bundle, manifest["candidate_id"], commit)
            if manifest["quality"]["status"] != "PASS":
                _write(failure, {"status": "failed", "stage": stage, "quality": manifest["quality"], "checked_at": _now()})
                raise OperationError("QUALITY_GATE", "Source coverage or output quality requires review")
            bundle.rename(output)
            return {"status": "prepared", "input_dir": str(output), **manifest}
    except Exception as error:
        # Temporary inputs disappear on exit. Preserve selected calendar details
        # and any quality report outside that directory for actionable review.
        previous = _json(failure) if failure.exists() else {}
        _write(failure, {**previous, "status": "failed", "dataset": dataset, "stage": stage,
                        "checked_at": _now(), "calendar_validation": calendar_failure, **_error(error)})
        raise


def _saved_calendar_failure(work):
    """Extract calendar errors/pending reasons before temporary files disappear.

    Return a small report or a safe generic review hint when no report exists.
    Reading/parsing errors propagate; raw sources and credentials are omitted.
    """
    reports = sorted((work / "calendar-build").rglob("calendar_report.json"))
    if not reports:
        return {"status": "ERROR", "errors": ["Review inputs and hashes; see the calendar review contract"]}
    value = _json(reports[-1])
    return {key: value.get(key) for key in ("status", "year", "errors", "pending")}


def validate_candidate(directory, candidate_id=None, commit=None):
    """Check local candidate identity, every retained file and payload contracts.

    Args:
        directory: Candidate directory containing candidate_manifest.json.
        candidate_id: Optional exact SHA-256 ID approved by the operator.
        commit: Optional expected full producer commit from the selected run.

    Returns:
        The verified manifest, including payload-derived counts and period.

    Raises:
        OperationError: If identity, inventory, producer files, payload summary
            or frozen crash source provenance disagree. File, JSON and domain
            validation errors propagate for otherwise malformed candidates.

    Side effects:
        Reads local files only. It does not contact GitHub or PostgreSQL, prove
        archive authenticity, rerun operational quality gates, or establish
        that a candidate was previously published.
    """
    directory = Path(directory).resolve()
    manifest_path = directory / "candidate_manifest.json"
    if manifest_path.is_symlink():
        raise OperationError("UNSAFE_FILE", "Manifest must not be a symlink")
    manifest = _json(manifest_path)
    identity = dict(manifest)
    # Recompute identity without its own digest, then independently rehash files;
    # a matching directory name or manifest text alone is insufficient.
    own_id = identity.pop("candidate_id")
    _hex(own_id, 64, "candidate ID")
    if _digest(identity) != own_id or (candidate_id is not None and own_id != candidate_id):
        raise OperationError("CANDIDATE_CHANGED", "Candidate ID does not match the reviewed candidate")
    _hex(manifest["commit"], 40, "commit")
    if commit is not None and manifest["commit"] != commit:
        raise OperationError("COMMIT_MISMATCH", "Candidate was not produced by the selected run commit")
    if manifest.get("schema_version") != 1 or manifest.get("dataset") not in DATASETS:
        raise OperationError("INVALID_MANIFEST", "Unsupported candidate manifest")
    if manifest["files"] != _files(directory):
        raise OperationError("FILE_CHANGED", "Candidate files differ from recorded hashes or file inventory")
    if not {"producer/operations.json", "producer/requirements.lock"}.issubset(manifest["files"]):
        raise OperationError("PRODUCER_MISSING", "Candidate must retain producer policy and dependency lock")
    # Integrity does not imply semantic consistency. Derive counts and period
    # from the domain payload instead of accepting the manifest's summary alone.
    counts, period = _summary(manifest["dataset"], directory / "payload")
    if manifest["counts"] != counts or manifest["period"] != period:
        raise OperationError("SUMMARY_CHANGED", "Candidate summary differs from its payload")
    if manifest["dataset"] == "crashes":
        release_year = manifest["release_year"]
        baseline = release_year is None and (period["start"], period["end"]) == tuple(day.isoformat() for day in baseline_range())
        annual = (type(release_year) is int and 2027 <= release_year <= 9998
                  and period["start"] == f"{release_year-5}-01-01" and period["end"] == f"{release_year-1}-12-31")
        if not baseline and not annual:
            raise OperationError("WRONG_CRASH_PERIOD", "Annual crash candidate must cover its five completed years")
        report = _json(directory / "payload/crash_report.json")
        # Bind the retained originals to both the build report and download
        # sidecars, including when legacy outputs were adopted without a rebuild.
        for name, key in (("crashes", "source_sha256"), ("accident-location", "location_source_sha256")):
            raw_digest = sha256(directory / "raw" / f"{name}.csv")
            metadata = _json(directory / "raw" / f"{name}.source.json")
            if raw_digest != report[key] or raw_digest != metadata["sha256"]:
                raise OperationError("SOURCE_CHANGED", "Crash outputs and frozen original sources do not match")
    return manifest


def _error(error):
    """Return a safe error record, hiding arbitrary exception/connection text.

    OperationError messages are authored for operators and may pass through;
    all other exceptions expose only their type and a generic troubleshooting
    hint. Callers must never put credentials into an OperationError message.
    """
    if isinstance(error, OperationError):
        return {"error_code": error.code, "message": str(error)}
    return {"error_code": type(error).__name__,
            "message": "Check the named stage, input contract and source availability; connection details are omitted."}


def _database_state(connection):
    """Read readiness, active build/coverage summaries and relation sizes.

    Require all nine expected tables, allowing additional tables. Return JSON
    compatible summaries or raise SCHEMA_NOT_READY/driver errors. Reads use the
    caller's connection and transaction settings; no data or schema is changed.
    This is a health summary, not full payload equality or a source-freshness
    check, and it does not inspect application email contents.
    """
    existing = {row[0] for row in connection.execute("SELECT tablename FROM pg_tables WHERE schemaname='iteration2_v2'")}
    if not set(TABLES).issubset(existing):
        raise OperationError("SCHEMA_NOT_READY", "Expected nine tables are missing; run the reviewed schema setup separately")
    speed = connection.execute("SELECT count(*), count(*) FILTER (WHERE status='unresolved') FROM iteration2_v2.road_speed").fetchone()
    crash = connection.execute("SELECT build_id,start_date,end_date,event_rows,loaded_at FROM iteration2_v2.crash_build").fetchone()
    coverage = connection.execute("SELECT year,source_manifest,loaded_at FROM iteration2_v2.calendar_coverage ORDER BY year").fetchall()
    sizes = connection.execute("SELECT relname, pg_total_relation_size(relid) FROM pg_catalog.pg_statio_user_tables WHERE schemaname='iteration2_v2'").fetchall()
    return {"speed": {"road_rows": speed[0], "unresolved_rows": speed[1]},
            "crashes": None if crash is None else dict(zip(("build_id", "start", "end", "event_rows", "loaded_at"),
                       (crash[0], crash[1].isoformat(), crash[2].isoformat(), crash[3], crash[4].isoformat()))),
            "calendar": [{"year": y, "start": m.get("coverage_start"), "end": m.get("coverage_end"),
                          "status": m.get("status"), "build_id": m.get("build_id"), "loaded_at": loaded.isoformat()} for y, m, loaded in coverage],
            "table_bytes": dict(sizes), "database_bytes": connection.execute("SELECT pg_database_size(current_database())").fetchone()[0]}


def _publication_gate(manifest, state, *, restore=False, history=None):
    """Reject unsafe replacements using current policy, state and known history.

    manifest must already be validated; state comes from the locked database.
    history is the list from an unresolved-free publication-history document,
    validated by publish. Return None when allowed, otherwise raise
    OperationError or policy/date validation errors. No database writes occur.

    Crash updates and restores require history. A normal annual period remains
    frozen even after rollback to an earlier build. Annual recovery requires
    the exact previously successful candidate; the adopted legacy baseline is
    the narrow exception because it predates the publication archive. Restore
    bypasses normal recency/count gates, not manifest or quality validation.
    """
    dataset, counts, period = manifest["dataset"], manifest["counts"], manifest["period"]
    policy = read_policy()
    if dataset == "crashes" and history is None:
        raise OperationError("PUBLICATION_HISTORY_REQUIRED", "Fetch durable publication history before a crash update or restore")
    if restore:
        # Being archived proves what bytes exist, not that they ever went live.
        # Recovery of an annual version needs the exact successful candidate.
        if dataset == "crashes" and manifest["release_year"] is not None:
            previously_published = any(previous.get("status") == "complete"
                                       and previous.get("dataset") == "crashes"
                                       and previous.get("candidate_id") == manifest["candidate_id"]
                                       and previous.get("period") == period for previous in history)
            if not previously_published:
                raise OperationError("RESTORE_NOT_PUBLISHED", "Restore requires a previously successful annual candidate; archival alone is not publication")
        return
    if dataset == "speed":
        before = state["speed"]
        if before["road_rows"]:
            if counts["road_rows"] < before["road_rows"] * policy["speed"]["minimum_retained_fraction"]:
                raise OperationError("ROAD_COUNT_DROP", "Road count dropped beyond policy; review the source and policy before publishing")
            # Compare unresolved proportions so a changed network size does not
            # turn a simple row-count difference into a false quality signal.
            increase = counts["unresolved_rows"] / counts["road_rows"] - before["unresolved_rows"] / before["road_rows"]
            if increase > policy["speed"]["maximum_unresolved_fraction_increase"]:
                raise OperationError("UNRESOLVED_INCREASE", "Unresolved road fraction increased beyond policy")
    if dataset == "crashes":
        today = melbourne_today()
        year = manifest["release_year"]
        if year is None:
            raise OperationError("BASELINE_RESTORE_ONLY", "An adopted baseline is for recovery; normal updates begin September 2027")
        for previous in history:
            # Active DB state alone loses this fact after a baseline rollback;
            # durable successful history keeps the once-per-year snapshot frozen.
            if (previous.get("status") == "complete" and previous.get("dataset") == "crashes"
                    and not previous.get("restore", False) and previous.get("period", {}).get("end") == period["end"]
                    and previous.get("candidate_id") != manifest["candidate_id"]):
                raise OperationError("ANNUAL_ALREADY_PUBLISHED", "This annual period was already published; replay its exact archive or use explicit recovery")
        annual_range(year, today=today)
        # January does not advance the release window: last September stays due
        # through August, allowing a late update without inventing a new period.
        due_year = today.year if today.month >= policy["crashes"]["release_month"] else today.year - 1
        if year != due_year:
            raise OperationError("OLD_ANNUAL_CANDIDATE", "Prepare a candidate for the latest September release; use restore only for recovery")
        before = state["crashes"]
        if before:
            if before["end"] >= period["end"] and before["build_id"] != period["build_id"]:
                raise OperationError("ANNUAL_ALREADY_PUBLISHED", "This annual period is already published; recovery requires explicit restore")
            if counts["event_rows"] < before["event_rows"] * policy["crashes"]["minimum_retained_fraction"]:
                raise OperationError("CRASH_COUNT_DROP", "Crash cohort dropped beyond policy; review source completeness")


@contextmanager
def _locked_database(url):
    """Yield an autocommit connection while holding the shared publication lock.

    Acquisition waits at most the configured 30-second lock timeout; database
    errors propagate and the lock is released on exit. This session lock spans
    preflight and loaders using their own transactions/connections. It serializes
    cooperating operations callers, not arbitrary SQL or lower-level loaders.
    """
    import psycopg
    with psycopg.connect(url, autocommit=True) as connection:
        connection.execute("SET lock_timeout = '30s'")
        connection.execute("SELECT pg_advisory_lock(%s)", (LOCK,))
        try:
            yield connection
        finally:
            connection.execute("SELECT pg_advisory_unlock(%s)", (LOCK,))


def publish(directory, candidate_id, reviewed_by, reason, *, report, allow_bounded_calendar=False,
            restore=False, baseline=None, publication_history=None):
    """Publish exact reviewed candidate bytes to one existing database domain.

    Args:
        directory: Complete local candidate directory; never modified here.
        candidate_id: Exact reviewed manifest SHA-256 ID, not a mutable alias.
        reviewed_by: Nonblank operator identity retained in the report.
        reason: Nonblank review/publication or recovery explanation.
        report: Local JSON report path outside the immutable candidate directory.
        allow_bounded_calendar: Explicit confirmation that the deployed backend
            enforces bounded coverage; this function cannot verify backend code.
        restore: Select reviewed recovery, bypassing normal count/recency gates.
        baseline: Optional verified candidate for report-only count comparison.
        publication_history: JSON history with no unresolved intents, required
            for crash updates/restores and checked when supplied for any domain.

    Returns:
        A complete publication report. Replaying the active crash build returns
        an unchanged outcome without replacing its rows or loaded timestamp.

    Raises:
        OperationError: For invalid review, identity, quality, history, schema or
            publication gates, or missing DATABASE_URL. File/driver/loader errors
            propagate after attempted reporting. A report write may also fail.

    Side effects:
        Reads DATABASE_URL, writes a local report and creates a private temporary
        snapshot. A session lock protects preflight and the selected loader's
        separate transaction. No downloads, migrations, GitHub archives/intents
        or other-domain replacement occur. Commit and report finalization are
        separate: an exception after publication starts may require read-only
        verification and external reconciliation, not an automatic retry.
    """
    directory, report = Path(directory).resolve(), Path(report).resolve()
    if report.is_relative_to(directory):
        raise OperationError("IMMUTABLE_CANDIDATE", "Publication report must be outside the candidate")
    if not reviewed_by or not reviewed_by.strip() or not reason or not reason.strip():
        raise OperationError("REVIEW_REQUIRED", "Reviewer and reason are required for publication and restore")
    result = {"status": "running", "candidate_id": candidate_id, "reviewed_by": reviewed_by,
              "reason": reason, "restore": restore, "started_at": _now(), "stage": "validate"}
    _write(report, result)
    try:
        manifest = validate_candidate(directory, candidate_id)
        result.update(dataset=manifest["dataset"], period=manifest["period"], commit=manifest["commit"],
                      release_year=manifest["release_year"])
        # Preparation may have used an older policy. A retained PASS is review
        # evidence, while today's publication must satisfy the current limits.
        quality = _quality(directory, manifest["dataset"], manifest["counts"], manifest["period"])
        if quality["status"] != "PASS":
            result["quality"] = quality
            raise OperationError("QUALITY_GATE", "Candidate fails production quality gates")
        if baseline:
            result["comparison"] = _comparison(manifest, baseline)
        if manifest["dataset"] == "calendar" and manifest["period"]["status"] == "READY_BOUNDED" and not allow_bounded_calendar:
            raise OperationError("BACKEND_CONTRACT_REQUIRED", "Bounded calendar publication requires a backend that enforces coverage dates")
        url = os.environ.get("DATABASE_URL")
        if not url:
            raise OperationError("DATABASE_URL_REQUIRED", "DATABASE_URL is required only for publication")
        history = None
        if publication_history:
            # An unresolved intent may hide a committed update whose evidence
            # upload failed. Reject it before connecting, including for recovery.
            document = _json(publication_history)
            if document.get("schema_version") != 1 or not isinstance(document.get("publications"), list):
                raise OperationError("INVALID_HISTORY", "Publication history requires schema version 1 and a publications list")
            if document.get("unresolved_intents", []):
                raise OperationError("UNRESOLVED_PUBLICATION", "Reconcile uncertain earlier publications before changing or restoring data")
            history = document["publications"]
            if any(not isinstance(item, dict) for item in history):
                raise OperationError("INVALID_HISTORY", "Publication history entries must be objects")
        result["stage"] = "preflight"
        # Copy into a private temporary directory and revalidate before opening
        # the DB. An operator editing the original while we wait for a writer
        # lock cannot change the bytes that will be loaded.
        with tempfile.TemporaryDirectory(prefix="pipeline-publication-") as temporary:
            snapshot = Path(temporary) / "candidate"
            shutil.copytree(directory, snapshot, symlinks=True)
            validate_candidate(snapshot, candidate_id)
            with _locked_database(url) as connection:
                # Read current counts only after acquiring the publication lock
                # so cooperating writers cannot invalidate the comparison.
                state = _database_state(connection)
                result["before"] = state
                _publication_gate(manifest, state, restore=restore, history=history)
                result["stage"] = "publish"
                # Persist the write boundary first. Failures from this point are
                # conservatively uncertain to workflow_support until reconciled.
                _write(report, result)
                dataset = manifest["dataset"]
                if dataset == "speed":
                    outcome = load(url, snapshot / "payload")
                elif dataset == "crashes":
                    if state["crashes"] and state["crashes"]["build_id"] == manifest["period"]["build_id"]:
                        outcome = {"status": "unchanged", "build_id": manifest["period"]["build_id"]}
                    else:
                        outcome = load_crashes(url, snapshot / "payload")
                else:
                    # Carry the dry-run build ID into the write so calendar
                    # storage also detects writers outside this advisory lock.
                    delta = load_calendar(url, snapshot / "payload", dry_run=True)
                    outcome = load_calendar(url, snapshot / "payload", expected_current_build_id=delta["expected_current_build_id"],
                                            allow_bounded_coverage=allow_bounded_calendar)
                result.update(status="complete", stage="complete", outcome=outcome, published_at=_now())
    except Exception as error:
        result.update(status="failed", **_error(error))
        raise
    finally:
        # This filesystem write cannot participate in the loader transaction.
        # A committed build must not be inferred absent from a failed/lost report.
        result["finished_at"] = _now()
        _write(report, result)
    return result


def status(report):
    """Read operational state and write a health/review report without publishing.

    Args:
        report: Destination JSON file, created or replaced locally.

    Returns:
        A report with status ok, attention, not_configured or failed. Warnings
        cover the configured calendar horizon, database size and latest due
        September crash release; ok is not a full application health guarantee.

    Raises:
        OSError/ValueError: Report-writing or serialization errors can propagate.
            Database/policy errors inside the check become safe failed reports.

    Side effects:
        Reads DATABASE_URL when set, opens a read-only transaction with a
        30-second statement timeout, and writes the report. Missing credentials
        remain explicitly not_configured. No data refresh or schema change runs.
    """
    url = os.environ.get("DATABASE_URL")
    result = {"status": "not_configured", "checked_at": _now(), "warnings": [],
              "next_crash_update": "September 2027, then annually; current period comes from crash_build"}
    try:
        if not url:
            result["warnings"].append("Read-only database credentials are not configured")
        else:
            import psycopg
            with psycopg.connect(url, options="-c default_transaction_read_only=on -c statement_timeout=30000") as connection:
                result["database"] = _database_state(connection)
            policy = read_policy()["monitoring"]
            today = melbourne_today()
            deadline = today + timedelta(days=policy["calendar_warning_days"])
            # Split the warning horizon at New Year; a valid current-year bundle
            # cannot stand in for missing coverage early in the following year.
            for year in range(today.year, deadline.year + 1):
                needed_start, needed_end = max(today, date(year, 1, 1)), min(deadline, date(year, 12, 31))
                found = any(row["year"] == year and row["status"] in ("READY", "READY_BOUNDED")
                            and row["start"] and row["end"] and row["start"] <= needed_start.isoformat()
                            and row["end"] >= needed_end.isoformat() for row in result["database"]["calendar"])
                if not found:
                    result["warnings"].append(f"Calendar coverage needs review for {needed_start} through {needed_end}")
            if result["database"]["database_bytes"] >= policy["database_warning_bytes"]:
                result["warnings"].append("Database storage exceeds configured review threshold")
            crash_policy = read_policy()["crashes"]
            current = result["database"]["crashes"]
            # Carry missed September updates across New Year until active DB
            # metadata proves the completed-year period was actually published.
            latest_due = today.year if today.month >= crash_policy["release_month"] else today.year - 1
            next_year = max(latest_due + 1, crash_policy["first_release_year"])
            if latest_due >= crash_policy["first_release_year"]:
                if current is None or current["end"] < f"{latest_due-1}-12-31":
                    result["warnings"].append(f"Annual crash update for September {latest_due} has not been published")
                    next_year = latest_due
            result["next_crash_update"] = f"{next_year}-{crash_policy['release_month']:02}"
            result["status"] = "attention" if result["warnings"] else "ok"
    except Exception as error:
        result.update(status="failed", **_error(error))
    _write(report, result)
    return result


def main(argv=None):
    """Dispatch the CLI and print a JSON result with sanitized command failures.

    Args:
        argv: Optional argument sequence; None uses process command-line args.

    Returns:
        Exit code 0 for success, or 1 for failure, attention or missing setup.

    Raises:
        SystemExit: argparse handles invalid arguments and help before dispatch.

    Side effects:
        Selected commands perform their documented effects. Results go to stdout;
        caught command errors go to stderr without arbitrary exception details.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_cli = commands.add_parser("prepare")
    prepare_cli.add_argument("--dataset", choices=DATASETS, required=True)
    prepare_cli.add_argument("--commit", required=True)
    prepare_cli.add_argument("--output", type=Path, required=True)
    prepare_cli.add_argument("--year", type=int)
    prepare_cli.add_argument("--release-year", type=int)
    prepare_cli.add_argument("--as-of")
    prepare_cli.add_argument("--calendar-source-dir", type=Path)
    prepare_cli.add_argument("--calendar-review-dir", type=Path)
    prepare_cli.add_argument("--baseline", type=Path)
    prepare_cli.add_argument("--frozen-input", type=Path)
    prepare_cli.add_argument("--frozen-source-dir", type=Path)
    validation = commands.add_parser("validate")
    validation.add_argument("--input", type=Path, required=True)
    validation.add_argument("--candidate-id", required=True)
    validation.add_argument("--commit")
    publication = commands.add_parser("publish")
    publication.add_argument("--input", type=Path, required=True)
    publication.add_argument("--candidate-id", required=True)
    publication.add_argument("--reviewed-by", required=True)
    publication.add_argument("--reason", required=True)
    publication.add_argument("--allow-bounded-calendar", action="store_true")
    publication.add_argument("--restore", action="store_true")
    publication.add_argument("--baseline", type=Path)
    publication.add_argument("--publication-history", type=Path)
    publication.add_argument("--report", type=Path, required=True)
    health = commands.add_parser("status")
    health.add_argument("--report", type=Path, required=True)
    args = vars(parser.parse_args(argv))
    command = args.pop("command")
    try:
        if command == "prepare":
            result = prepare(**args)
        elif command == "validate":
            result = {"status": "valid", **validate_candidate(args.pop("input"), **args)}
        elif command == "publish":
            result = publish(args.pop("input"), **args)
        else:
            result = status(**args)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 1 if result["status"] in ("failed", "attention", "not_configured") else 0
    except Exception as error:
        print(json.dumps({"status": "failed", "stage": command, **_error(error)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
