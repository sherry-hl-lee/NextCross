"""Expose the small Iteration 1 v2 pipeline as five CLI commands."""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict
from pathlib import Path

from iteration1_v2_pipeline.download import download_speed_zones
from iteration1_v2_pipeline.load import replace_road_speeds
from iteration1_v2_pipeline.lookup import query_speed
from iteration1_v2_pipeline.migrate import apply_migration
from iteration1_v2_pipeline.settings import RAW_FILE
from iteration1_v2_pipeline.validate import validate_raw


def _positive_int(value: str) -> int:
    """Parse one command-line value as an integer greater than zero."""

    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a positive integer") from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse download, validate, migrate, load, and lookup arguments."""

    parser = argparse.ArgumentParser(description="Iteration 1 v2 data pipeline")
    commands = parser.add_subparsers(dest="command", required=True)

    download = commands.add_parser("download", help="download Speed Zones")
    download.add_argument("--output", type=Path, default=RAW_FILE)

    validate = commands.add_parser("validate", help="count accepted road rows")
    validate.add_argument("--input", type=Path, default=RAW_FILE)

    migrate = commands.add_parser("migrate", help="create the v2 database schema")
    migrate.add_argument("--database-url", default=os.environ.get("DATABASE_URL"))

    load = commands.add_parser("load", help="replace v2 road-speed rows")
    load.add_argument("--input", type=Path, default=RAW_FILE)
    load.add_argument("--database-url", default=os.environ.get("DATABASE_URL"))
    load.add_argument(
        "--expect-accepted",
        type=_positive_int,
        help="rollback unless COPY accepts exactly this positive row count",
    )

    lookup = commands.add_parser("lookup", help="query the nearest road speed")
    lookup.add_argument("--latitude", type=float, required=True)
    lookup.add_argument("--longitude", type=float, required=True)
    lookup.add_argument("--radius-m", type=float, default=30.0)
    lookup.add_argument(
        "--database-url",
        default=os.environ.get("RUNTIME_DATABASE_URL") or os.environ.get("DATABASE_URL"),
    )
    return parser.parse_args(argv)


def _require_database_url(args: argparse.Namespace) -> str:
    """Return a CLI database URL or raise a clear configuration error."""

    if not args.database_url:
        raise ValueError("--database-url or DATABASE_URL is required")
    return str(args.database_url)


def main(argv: list[str] | None = None) -> int:
    """Run one selected command and print a compact operator result."""

    args = parse_args(argv)
    try:
        if args.command == "download":
            result = {"rawFile": str(download_speed_zones(args.output))}
        elif args.command == "validate":
            result = asdict(validate_raw(args.input))
        elif args.command == "migrate":
            apply_migration(_require_database_url(args))
            result = {"schema": "iteration1_v2", "status": "ready"}
        elif args.command == "load":
            result = asdict(
                replace_road_speeds(
                    _require_database_url(args),
                    args.input,
                    expected_accepted=args.expect_accepted,
                )
            )
        else:
            speed = query_speed(
                _require_database_url(args),
                latitude=args.latitude,
                longitude=args.longitude,
                radius_m=args.radius_m,
            )
            result = {"speedKmh": speed}
    except Exception as exc:
        print(f"{args.command} failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
