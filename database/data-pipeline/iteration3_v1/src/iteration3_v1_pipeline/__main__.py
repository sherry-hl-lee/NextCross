"""Expose offline data pipeline stages as a CLI.

Pipeline commands are download, build, migrate and load, their crash-specific
counterparts, and the separate calendar-download/build/load stages.
crash-measure performs capacity checks in a disposable database.
The backend owns request-time searches and API responses for the loaded data.

Run with ``PYTHONPATH=src python -m iteration3_v1_pipeline <command>`` from
the iteration folder. Each command prints its result as JSON. Database-writing
commands use DATABASE_URL. An explicit --database-url overrides that value.
No .env file is read automatically.
"""

import argparse
from datetime import date
import json
import os
from pathlib import Path

from iteration3_v1_pipeline.operations_policy import baseline_range
from iteration3_v1_pipeline.settings import PROCESSED_DIR, RAW_DIR


def calendar_year(value):
    """Reject invalid calendar years before fetching sources or writing files."""
    year = int(value)
    if not 1900 <= year <= 9998:
        raise argparse.ArgumentTypeError("calendar year must be in 1900..9998")
    return year


def main(argv=None):
    """Parse one command, execute its stage, and print a JSON result.

    Args:
        argv: CLI argument strings, or None to use the process arguments.

    Returns:
        None; the selected stage's result is written to standard output.

    Raises:
        SystemExit: For argparse help, invalid arguments, or a missing required
            database URL. Help exits successfully; argument errors do not.
        Exception: Propagates errors from the selected pipeline stage.

    Side effects:
        Depends on the command: source downloads and builds write local files;
        migrations and loads write the selected database.
        crash-measure reloads crash tables in a guarded disposable database.
    """
    # 1. Define the CLI before running any stage. A required subcommand chooses
    # exactly one operation; invoking the package does not run the whole pipeline.
    parser = argparse.ArgumentParser(description="Victoria-wide speed rules and five-year crash hotspots")
    commands = parser.add_subparsers(dest="command", required=True)
    # Download produces the local raw snapshots that build will consume later.
    download = commands.add_parser("download", help="Download Speed Zones and Speed Signs")
    download.add_argument("--output", type=Path, default=RAW_DIR)
    # Path converts file arguments to Path objects. date.fromisoformat accepts
    # YYYY-MM-DD and lets argparse reject malformed dates before processing.
    build = commands.add_parser("build", help="Build statewide speed roads and rules")
    build.add_argument("--zones", type=Path, default=RAW_DIR / "speed-zones.geojson")
    build.add_argument("--signs", type=Path, default=RAW_DIR / "speed-signs.geojson")
    build.add_argument("--output", type=Path, default=PROCESSED_DIR)
    build.add_argument("--as-of", type=date.fromisoformat, help="Ignore signs installed after this date (default: today in Melbourne)")
    # Crash preparation is a separate flow with its own inputs and date range.
    # The default five-year cohort is fixed here, rather than moving with today.
    crash_download = commands.add_parser("crash-download", help="Download crash records and location labels")
    crash_download.add_argument("--output", type=Path, default=RAW_DIR)
    crash_build = commands.add_parser("crash-build", help="Build fixed 100m crash clusters and individual records")
    crash_build.add_argument("--source", type=Path, default=RAW_DIR / "crashes.csv")
    crash_build.add_argument("--locations", type=Path, default=RAW_DIR / "accident-location.csv")
    crash_build.add_argument("--output", type=Path, default=PROCESSED_DIR)
    # The low-level builder defaults to the accepted baseline, not a rolling
    # wall-clock range. Reviewed annual publication is gated in operations.py.
    crash_build.add_argument("--start-date", type=date.fromisoformat, default=baseline_range()[0])
    crash_build.add_argument("--end-date", type=date.fromisoformat, default=baseline_range()[1])

    # Calendar candidates are prepared independently of the road/crash datasets.
    # An explicit year prevents an annual refresh from silently changing scope.
    calendar_download = commands.add_parser("calendar-download", help="Download the official DataVic calendar CSV")
    calendar_download.add_argument("--year", type=calendar_year, required=True)
    calendar_download.add_argument("--output", type=Path, default=RAW_DIR / "calendar")
    calendar_download.add_argument("--import-dir", type=Path, help="Import a saved DataVic CSV snapshot with sources.json instead of HTTP requests")
    calendar_download.add_argument("--verified-at", help="Actual UTC source-check timestamp; required with --import-dir")
    calendar_build = commands.add_parser("calendar-build", help="Validate the official DataVic CSV for one calendar year")
    calendar_build.add_argument("--year", type=calendar_year, required=True)
    calendar_build.add_argument("--source-dir", type=Path, required=True)
    calendar_build.add_argument("--output", type=Path, default=PROCESSED_DIR / "calendar")
    calendar_build.add_argument("--previous", type=Path, help="Optional previous local bundle for a date comparison")

    # Review is an offline build stage: corrected bytes and human evidence are
    # explicit inputs, and the original snapshot remains part of the bundle.
    calendar_review = commands.add_parser("calendar-review", help="Build a reviewed calendar while preserving the official original")
    calendar_review.add_argument("--year", type=calendar_year, required=True)
    calendar_review.add_argument("--source-dir", type=Path, required=True)
    calendar_review.add_argument("--corrected-csv", type=Path, required=True)
    calendar_review.add_argument("--review-file", type=Path, required=True)
    calendar_review.add_argument("--output", type=Path, default=PROCESSED_DIR / "calendar")
    calendar_review.add_argument("--previous", type=Path)

    # 2. Share connection options across database stages. Dataset loads require
    # a writer/owner connection. An explicit flag overrides the environment;
    # these commands do not read .env files or use backend runtime credentials.
    database_commands = ("migrate", "load", "crash-migrate", "crash-load", "crash-measure", "calendar-load")
    for name in database_commands:
        command = commands.add_parser(name)
        command.add_argument("--database-url", default=os.environ.get("DATABASE_URL"))
        # Load and measurement consume prepared files; migrations need only SQL.
        if name in ("load", "crash-load", "crash-measure"):
            command.add_argument("--input", type=Path, default=PROCESSED_DIR)
        # Capacity measurement returns JSON and can also save it to a file.
        if name == "crash-measure":
            command.add_argument("--output", type=Path)
        if name == "calendar-load":
            command.add_argument("--input", type=Path, required=True, help="Versioned calendar bundle directory")
            command.add_argument("--dry-run", action="store_true", help="Read and report the DB difference without writing")
            command.add_argument("--allow-bounded-coverage", action="store_true", help="Confirm the deployed backend enforces coverage start/end dates")
            command.add_argument("--expected-current-build-id", help="Current year build from dry-run, or none for first publication")
    # 3. Parse once, then reject a missing database URL before opening a connection.
    # With argv=None, argparse reads the arguments supplied in the terminal.
    args = parser.parse_args(argv)
    if args.command in database_commands and not args.database_url:
        parser.error("DATABASE_URL or --database-url is required")
    # Publication must name the version the operator reviewed. The literal
    # "none" means an expected first publication; omission is not that assertion.
    if args.command == "calendar-load" and not args.dry_run and args.expected_current_build_id is None:
        parser.error("calendar-load requires --dry-run or --expected-current-build-id")
    # An offline import needs the actual source-check time, while HTTP acquisition
    # records its own timestamp. Neither option is meaningful alone.
    if args.command == "calendar-download" and bool(args.import_dir) != bool(args.verified_at):
        parser.error("--import-dir and --verified-at must be supplied together")

    # 4. Dispatch the selected stage. Imports stay in their branches so --help
    # does not need to load the processing libraries or database driver.
    if args.command == "download":
        from iteration3_v1_pipeline.download import download
        result = download(args.output)
    elif args.command == "build":
        from iteration3_v1_pipeline.build import build
        result = build(args.zones, args.signs, args.output, as_of=args.as_of)
    elif args.command == "migrate":
        # The full migration creates speed, crash and notification storage.
        # Table creation and dataset loading are separate commands.
        from iteration3_v1_pipeline.load import apply_migration
        apply_migration(args.database_url)
        result = {"schema": "iteration2_v2", "speed_tables": "ready",
                  "crash_tables": "ready", "notification_table": "ready",
                  "calendar_coverage_table": "ready", "report_email_dispatch_table": "ready"}
    elif args.command == "load":
        from iteration3_v1_pipeline.load import load
        result = load(args.database_url, args.input)
    elif args.command == "crash-download":
        from iteration3_v1_pipeline.crash_download import download_crashes
        result = download_crashes(args.output)
    elif args.command == "crash-build":
        from iteration3_v1_pipeline.crash import build_crashes
        result = build_crashes(args.source, args.output, start_date=args.start_date,
                               end_date=args.end_date, location_source=args.locations)
    elif args.command == "crash-migrate":
        # Retain the narrower command for creating only the crash tables.
        from iteration3_v1_pipeline.crash_storage import apply_crash_migration
        apply_crash_migration(args.database_url)
        result = {"schema": "iteration2_v2", "crash_tables": "ready"}
    elif args.command == "crash-load":
        from iteration3_v1_pipeline.crash_storage import load_crashes
        result = load_crashes(args.database_url, args.input)
    elif args.command == "calendar-download":
        from iteration3_v1_pipeline.calendar_download import download_calendar
        result = download_calendar(args.year, args.output, import_dir=args.import_dir,
                                   verified_at=args.verified_at)
    elif args.command == "calendar-build":
        from iteration3_v1_pipeline.calendar import build_calendar
        result = build_calendar(args.source_dir, args.year, args.output, previous=args.previous)
    elif args.command == "calendar-review":
        from iteration3_v1_pipeline.calendar_review import build_reviewed_calendar
        result = build_reviewed_calendar(args.source_dir, args.year, args.corrected_csv,
                                         args.review_file, output=args.output, previous=args.previous)
    elif args.command == "calendar-load":
        from iteration3_v1_pipeline.calendar_storage import load_calendar
        # Omission preserves the loader's protective default; the flag records
        # the operator's assertion that request-time coverage checks are live.
        options = {"allow_bounded_coverage": True} if args.allow_bounded_coverage else {}
        result = load_calendar(args.database_url, args.input, dry_run=args.dry_run,
                               expected_current_build_id=args.expected_current_build_id, **options)
    else:
        # argparse already limited the choices; the only remaining stage is
        # crash-measure, whose implementation checks for a disposable database.
        from iteration3_v1_pipeline.crash_storage import measure_crashes
        result = measure_crashes(args.database_url, args.input, output=args.output)
    # 5. Give terminal users and calling scripts one compact JSON result.
    # Stage errors propagate instead of being reported as successful results.
    print(json.dumps(result, separators=(",", ":")))


# python -m iteration3_v1_pipeline enters here. Importing this module for a test
# exposes main() without starting a download or database operation.
if __name__ == "__main__":
    main()
