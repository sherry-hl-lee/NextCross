"""Prepare and deploy one commit's complete iteration3_v1 data artifact.

This legacy all-dataset interface remains for historical verification. The
current workflow uses operations.py and workflow_support.py for separate,
reviewed dataset publication; this module does not enforce their annual policy
or durable publication-intent protocol and is not the routine production path.

Preparation is offline with respect to PostgreSQL. Deployment validates the
entire artifact before opening a database connection. Migration, calendar,
speed, and crash publication each have their own transaction: a later failure
does not roll back an earlier successful stage. The result records that state.
"""

import argparse
import csv
from datetime import date, datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from iteration3_v1_pipeline.operations_policy import baseline_range
from iteration3_v1_pipeline.build import ROAD_FIELDS, RULE_FIELDS, build
from iteration3_v1_pipeline.calendar import COVERAGE_SCOPE, build_calendar, validate_bundle
from iteration3_v1_pipeline.calendar_download import download_calendar
from iteration3_v1_pipeline.calendar_storage import load_calendar
from iteration3_v1_pipeline.crash import build_crashes, sha256
from iteration3_v1_pipeline.crash_download import download_crashes
from iteration3_v1_pipeline.crash_storage import _prepared_inputs, load_crashes
from iteration3_v1_pipeline.download import download
from iteration3_v1_pipeline.load import _check_csv, apply_migration, load

# Reuse the accepted baseline rather than maintaining a second date constant.
# Annual candidate ranges belong to operations_policy.annual_range instead.
CRASH_RANGE = dict(zip(("start", "end"), (day.isoformat() for day in baseline_range())))
ARTIFACT_FILES = (
    "speed/road_speed.csv", "speed/speed_rule.csv", "speed/report.json",
    "crashes/crash_event.csv", "crashes/hotspot.csv", "crashes/crash_report.json",
    "calendar/calendar.csv", "calendar/calendar_manifest.json", "calendar/calendar_report.json",
)
DEFAULT_OUTPUT = ROOT / "data/processed/deployment"
DEFAULT_REPORT = ROOT / "data/processed/deployment-result.json"


class PreparationError(RuntimeError):
    """Expose only a fixed stage label and exception class, never source details."""

    def __init__(self, stage, cause):
        self.stage = stage
        self.error_type = type(cause).__name__
        super().__init__(f"Preparation failed at {stage} ({self.error_type})")


def _commit(value):
    """Require an exact commit identity instead of a movable branch or tag."""
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("A full lowercase 40-character Git commit is required")
    return value


def _json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path, value):
    """Replace the report only after its complete JSON has been written."""
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_suffix(path.suffix + ".part")
    part.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    part.replace(path)


def _file_info(path):
    return {"sha256": sha256(path), "bytes": path.stat().st_size}


def _payload_counts(directory, year, as_of):
    """Reuse loader checks and verify speed counts before any database writes."""
    speed = _json(directory / "speed/report.json")
    if speed.get("signs_as_of") != as_of:
        raise ValueError("Speed report date disagrees with deployment manifest")
    counts = {}
    for name, fields, allow_empty in (("road_speed", ROAD_FIELDS, False), ("speed_rule", RULE_FIELDS, True)):
        path = directory / "speed" / f"{name}.csv"
        _check_csv(path, fields, allow_empty=allow_empty)
        # Real road EWKT can exceed csv's default 131,072-character field limit.
        # A UTF-8 field cannot have more characters than its file has bytes, so
        # use that bound while streaming this file without truncating geometry.
        # The setting is process-wide; restore it even if row validation fails.
        previous_limit = csv.field_size_limit()
        try:
            csv.field_size_limit(max(previous_limit, path.stat().st_size))
            with path.open(newline="", encoding="utf-8") as stream:
                rows = csv.DictReader(stream)
                count = 0
                for row in rows:
                    if None in row or None in row.values():
                        raise ValueError(f"Malformed prepared speed row: {path.name}")
                    count += 1
        finally:
            csv.field_size_limit(previous_limit)
        key = "road_rows" if name == "road_speed" else "rule_rows"
        if (not allow_empty and not count) or count != speed["counts"].get(key, 0):
            raise ValueError(f"Speed counts disagree: {path.name}")
        counts[key] = count
    crashes, files = _prepared_inputs(directory / "crashes")
    if (crashes["period_start"] != CRASH_RANGE["start"] or crashes["period_end"] != CRASH_RANGE["end"]
            or crashes["region"] != "Victoria"):
        raise ValueError("Crash build is outside the fixed 2021–2025 Victoria cohort")
    # Check fields consumed by the loader now, before applying any schema DDL.
    for field in ("cohort_policy", "eps_m", "min_samples", "crs_epsg", "time_policy_version",
                  "cluster_policy_version", "source_sha256", "location_source_sha256"):
        if field not in crashes:
            raise ValueError(f"Missing crash build metadata: {field}")
    if type(crashes["missing_coordinate_totals"]["crash_count"]) is not int:
        raise ValueError("Invalid crash missing-coordinate count")
    calendar, periods = validate_bundle(directory / "calendar")
    report = _json(directory / "calendar/calendar_report.json")
    # The readiness report must match the year and build; the calendar manifest
    # must also retain the supported CSV-only scope and regional limitation.
    if (calendar["year"] != year or calendar["coverage_scope"] != COVERAGE_SCOPE
            or calendar["regional_holidays_complete"] is not False
            or report.get("status") != "READY" or report.get("build_id") != calendar["build_id"]
            or report.get("year") != year):
        raise ValueError("Calendar build does not match deployment year or CSV-only scope")
    return {"speed": counts, "crashes": {"event_rows": files["crash_event"][2],
            "hotspot_rows": files["hotspot"][2]}, "calendar": {"rows": len(periods),
            "record_counts": calendar["record_counts"]}}


def validate_deployment(input_dir, commit):
    """Validate a fixed, symlink-free artifact; never connect to a database."""
    directory = Path(input_dir).resolve()
    expected = set(ARTIFACT_FILES) | {"deployment_manifest.json"}
    paths = list(directory.rglob("*"))
    if any(path.is_symlink() for path in paths):
        raise ValueError("Deployment artifacts must not contain symlinks")
    if {path.relative_to(directory).as_posix() for path in paths if path.is_file()} != expected:
        raise ValueError("Deployment artifact has missing or unexpected files")
    manifest = _json(directory / "deployment_manifest.json")
    if manifest.get("schema_version") != 1 or manifest.get("commit") != _commit(commit):
        raise ValueError("Deployment artifact schema or commit mismatch")
    year = manifest.get("year")
    if type(year) is not int or not 1900 <= year <= 9998:
        raise ValueError("Invalid calendar year")
    as_of = date.fromisoformat(manifest["as_of"]).isoformat()
    if manifest.get("crash_range") != CRASH_RANGE or set(manifest["files"]) != set(ARTIFACT_FILES):
        raise ValueError("Unexpected crash range or deployment file paths")
    if manifest.get("calendar_scope") != COVERAGE_SCOPE or manifest.get("regional_holidays_complete") is not False:
        raise ValueError("Deployment must disclose the CSV-only calendar scope")
    # Hashes bind the artifact bytes; parsing them again below checks that those
    # bytes also satisfy the loaders and the manifest's claimed row counts.
    for filename in ARTIFACT_FILES:
        if manifest["files"][filename] != _file_info(directory / filename):
            raise ValueError(f"Deployment file hash or size mismatch: {filename}")
    if manifest.get("counts") != _payload_counts(directory, year, as_of):
        raise ValueError("Deployment counts disagree with prepared datasets")
    return manifest


def prepare(year, commit, output=DEFAULT_OUTPUT, *, as_of=None):
    """Fetch calendar first, then build and atomically publish all three datasets."""
    commit = _commit(commit)
    as_of = date.fromisoformat(as_of) if isinstance(as_of, str) else as_of
    as_of = as_of or datetime.now(ZoneInfo("Australia/Melbourne")).date()
    output = Path(output).resolve()
    if output.exists():
        raise FileExistsError("Choose a new output directory; an existing deployment artifact is immutable")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = "calendar_download"
    try:
        with tempfile.TemporaryDirectory(prefix=".deployment-", dir=output.parent) as temporary:
            work = Path(temporary)
            bundle = work / "bundle"
            bundle.mkdir()
            # Invalid/unpublished calendars stop before downloading the larger inputs.
            stage = "calendar_download"
            calendar_raw = download_calendar(year, work / "raw/calendar")
            stage = "calendar_build"
            calendar_build = build_calendar(calendar_raw["source_dir"], year, work / "calendar-build")
            shutil.copytree(calendar_build["input_dir"], bundle / "calendar")
            speed_raw, crash_raw = work / "raw/speed", work / "raw/crashes"
            stage = "speed_download"
            sources = {"speed": download(speed_raw)}
            sources["speed_files"] = {name: _file_info(speed_raw / name)
                                      for name in ("speed-zones.geojson", "speed-signs.geojson")}
            stage = "speed_build"
            build(speed_raw / "speed-zones.geojson", speed_raw / "speed-signs.geojson", bundle / "speed", as_of=as_of)
            stage = "crash_download"
            sources["crashes"] = download_crashes(crash_raw)
            stage = "crash_build"
            build_crashes(crash_raw / "crashes.csv", bundle / "crashes", start_date=CRASH_RANGE["start"],
                          end_date=CRASH_RANGE["end"], location_source=crash_raw / "accident-location.csv")
            stage = "validation"
            counts = _payload_counts(bundle, year, as_of.isoformat())
            manifest = {"schema_version": 1, "commit": commit, "year": year, "as_of": as_of.isoformat(),
                        "created_at": datetime.now(timezone.utc).isoformat(), "crash_range": CRASH_RANGE,
                        "calendar_scope": COVERAGE_SCOPE, "regional_holidays_complete": False,
                        "sources": sources, "counts": counts,
                        "files": {name: _file_info(bundle / name) for name in ARTIFACT_FILES}}
            _write_json(bundle / "deployment_manifest.json", manifest)
            validate_deployment(bundle, commit)
            # Only this rename exposes the requested output directory; the
            # temporary download/build tree is discarded on either outcome.
            bundle.rename(output)
    except Exception as error:
        raise PreparationError(stage, error) from None
    return {"status": "prepared", "input_dir": str(output), **manifest}


def deploy(input_dir, commit, report=DEFAULT_REPORT):
    """Validate first, then persist progress after every completed stage.

    The last completed stages remain observable if the runner is terminated
    before the final result can be written. A running result is not success.
    """
    directory, report = Path(input_dir).resolve(), Path(report).resolve()
    if report.is_relative_to(directory):
        raise ValueError("Deployment result must be stored outside the immutable artifact")
    result = {"status": "running", "commit": _commit(commit), "stages": {},
              "calendar_scope": COVERAGE_SCOPE, "regional_holidays_complete": False,
              "transaction_scope": "Each stage commits separately; earlier stages survive a later failure."}
    stage = "validate"
    try:
        manifest = validate_deployment(directory, commit)
        result.update(year=manifest["year"], counts=manifest["counts"])
        result["stages"][stage] = {"status": "complete"}
        _write_json(report, result)
        database_url = os.environ.get("DATABASE_URL")
        if not database_url:
            raise ValueError("DATABASE_URL is required")
        stage = "migration"
        apply_migration(database_url)
        result["stages"][stage] = {"status": "complete"}
        _write_json(report, result)
        stage = "calendar_dry_run"
        difference = load_calendar(database_url, directory / "calendar", dry_run=True)
        result["stages"][stage] = difference
        _write_json(report, result)
        stage = "calendar_load"
        # Refuse a calendar replacement if another writer changed the active
        # build between the comparison above and the loader's transaction.
        result["stages"][stage] = load_calendar(database_url, directory / "calendar",
            expected_current_build_id=difference["expected_current_build_id"])
        _write_json(report, result)
        stage = "speed_load"
        result["stages"][stage] = {"status": "complete", **load(database_url, directory / "speed")}
        _write_json(report, result)
        stage = "crash_load"
        result["stages"][stage] = {"status": "complete", **load_crashes(database_url, directory / "crashes")}
        _write_json(report, result)
        result["status"] = "complete"
    except Exception as error:
        # Driver messages can contain connection details; record only the class.
        result.update(status="failed", failed_stage=stage, error_type=type(error).__name__)
        result["stages"][stage] = {"status": "failed", "error_type": type(error).__name__}
        raise RuntimeError(f"Deployment failed at {stage}; see {report}") from None
    finally:
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        _write_json(report, result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare", help="Download and validate all inputs without PostgreSQL")
    preparation.add_argument("--year", type=int, required=True)
    preparation.add_argument("--as-of", help="Speed-sign eligibility date; defaults to today's Melbourne date")
    preparation.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    for command in (preparation, commands.add_parser("validate"), commands.add_parser("deploy")):
        command.add_argument("--commit", required=True)
        if command is not preparation:
            command.add_argument("--input", type=Path, required=True)
        if command.prog.endswith(" deploy"):
            command.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            result = prepare(args.year, args.commit, args.output, as_of=args.as_of)
        elif args.command == "validate":
            result = {"status": "valid", **validate_deployment(args.input, args.commit)}
        else:
            result = deploy(args.input, args.commit, args.report)
        print(json.dumps(result, indent=2))
    except Exception as error:
        # Never print raw HTTP/database exceptions to public CI logs.
        print(json.dumps({"status": "failed",
                          "stage": error.stage if isinstance(error, PreparationError) else args.command,
                          "error_type": error.error_type if isinstance(error, PreparationError) else type(error).__name__}),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
