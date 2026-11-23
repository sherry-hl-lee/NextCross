"""Load prepared crash builds and measure storage for the data pipeline.

``load_crashes`` verifies ``crash_report.json`` against the prepared CSVs, then
replaces ``crash_build``, ``hotspot``, and ``crash_event`` in one transaction.
These are batch database mutations, not backend request-time queries. The
normal all-table migration lives in ``load.apply_migration``; this module also
offers the compatibility crash-only migration.

``measure_crashes`` is a destructive capacity experiment restricted to a
disposable database name. It measures crash tables plus any existing v2
notification table against the 200 MB budget; notification and speed tables
are observed without changing their data. Test measurements do not establish
production notification capacity or future growth.
"""

import csv
from datetime import datetime, timezone
import json
from pathlib import Path

from iteration3_v1_pipeline.crash import EVENT_FIELDS, HOTSPOT_FIELDS, sha256
from iteration3_v1_pipeline.settings import ITERATION_DIR, PROCESSED_DIR


CRASH_TABLES = ("crash_event", "hotspot", "crash_build")
# The stated budget uses decimal megabytes (200 MB), not binary mebibytes.
BUDGET_BYTES = 200_000_000


def apply_crash_migration(database_url):
    """Apply this iteration's crash-only SQL migration in one transaction.

    Args:
        database_url: PostgreSQL connection URL permitted to create the schema,
            crash tables, indexes, and required extension.

    Raises:
        Exception: Propagates migration-file, connection, and SQL failures;
            database changes roll back if execution fails.

    Side effects:
        Executes ``002_crash.sql`` without replacing existing data. This creates
        only the crash subset; use the normal ``migrate`` command for all nine
        tables, including notification storage.
    """
    import psycopg

    # The compatibility command installs only crash tables, not the full schema.
    migration = ITERATION_DIR / "postgresql/migrations/002_crash.sql"
    with psycopg.connect(database_url) as connection:
        connection.execute(migration.read_text(encoding="utf-8"))


def _prepared_inputs(processed_dir):
    """Validate that both prepared crash CSVs belong to the reported build.

    Args:
        processed_dir: ``Path`` containing the two CSVs and crash report JSON.

    Returns:
        ``(report, files)`` where each file entry contains its path, ordered
        field names, and row count, ready for the loader's COPY step.

    Raises:
        ValueError: If filenames, byte sizes, hashes, headers, build IDs, row
            shapes, or counts disagree with the report.
        Exception: Propagates file, CSV, JSON, and missing manifest-field errors.

    The checks prevent a mixed or partially published build being loaded before
    any database mutation. They verify local consistency, not a signed source
    attestation; source provenance is recorded by the build report.
    """
    # This is the build manifest beside the CSVs, not a historical reports/ file.
    report = json.loads((processed_dir / "crash_report.json").read_text(encoding="utf-8"))
    files = {}
    for name, fields in (("hotspot", HOTSPOT_FIELDS), ("crash_event", EVENT_FIELDS)):
        entry = report["files"][name]
        path = processed_dir / f"{name}.csv"
        if entry["file"] != path.name:
            raise ValueError(f"Unexpected prepared filename for {name}")
        # Check the actual file bytes before trusting any row-level metadata.
        if path.stat().st_size != entry["bytes"] or sha256(path) != entry["sha256"]:
            raise ValueError(f"Prepared file does not match crash_report.json: {path.name}")
        with path.open(newline="", encoding="utf-8") as stream:
            reader = csv.DictReader(stream)
            # COPY uses this exact column order when transferring the prepared CSV.
            if tuple(reader.fieldnames or ()) != fields:
                raise ValueError(f"Unexpected CSV header: {path.name}")
            rows = 0
            for row in reader:
                # DictReader uses None for extra columns or missing field values.
                # Empty strings are valid CSV fields here: they represent null
                # points or hotspot IDs and must not be mistaken for a short row.
                if None in row or None in row.values() or row["build_id"] != report["build_id"]:
                    raise ValueError(f"Invalid row or mixed build IDs: {path.name}")
                rows += 1
        if rows != entry["rows"]:
            raise ValueError(f"Prepared row count differs from report: {path.name}")
        files[name] = (path, fields, rows)
    # File-level counts must also agree with the manifest's overall build totals.
    if files["crash_event"][2] != report["selected_totals"]["crash_count"] or files["hotspot"][2] != report["hotspot_count"]:
        raise ValueError("Prepared counts disagree with report totals")
    return report, files


def load_crashes(database_url, processed_dir=PROCESSED_DIR):
    """Replace all three crash tables with one validated build atomically.

    Args:
        database_url: PostgreSQL connection URL with TRUNCATE/INSERT access to
            the migrated crash tables and permission to analyze them.
        processed_dir: Directory containing ``crash_report.json``,
            ``hotspot.csv``, and ``crash_event.csv`` from the same build.

    Returns:
        Build ID and loaded event, hotspot, and missing-point row counts.

    Raises:
        ValueError: If prepared input checks fail before database mutation.
        Exception: Propagates file, manifest, connection, COPY, and SQL failures.
            Database failures roll back the entire three-table replacement.

    Side effects:
        Truncates ``crash_event``, ``hotspot``, and ``crash_build``, inserts the
        build metadata, copies both CSVs, and analyzes all three tables in one
        transaction. Requires migrations already applied. Speed and notification
        tables retain their data. No clustering or backend response is produced.
    """
    import psycopg

    # Fail on inconsistent local artifacts before opening a write transaction.
    report, files = _prepared_inputs(Path(processed_dir))
    # The connection context commits on success or rolls back the whole replacement.
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            # Truncate all related tables in one statement so foreign-key
            # references between the old build, hotspots and events are covered.
            cursor.execute("TRUNCATE iteration2_v2.crash_event, iteration2_v2.hotspot, iteration2_v2.crash_build")
            # Insert parent build metadata before the hotspot and event records.
            cursor.execute("""
                INSERT INTO iteration2_v2.crash_build
                    (build_id, start_date, end_date, region, cohort_policy, eps_m, min_samples,
                     crs_epsg, time_policy_version, cluster_policy_version, source_sha256,
                     location_source_sha256, event_rows, hotspot_rows, missing_point_rows)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (report["build_id"], report["period_start"], report["period_end"], report["region"],
                  report["cohort_policy"], report["eps_m"], report["min_samples"], report["crs_epsg"],
                  report["time_policy_version"], report["cluster_policy_version"], report["source_sha256"],
                  report["location_source_sha256"], files["crash_event"][2], files["hotspot"][2],
                  report["missing_coordinate_totals"]["crash_count"]))
            # _prepared_inputs orders hotspots before events to satisfy references.
            for name, (path, fields, _) in files.items():
                with path.open("rb") as stream:
                    # Stream bulk data through COPY instead of issuing per-row inserts.
                    # Table and column names come from fixed internal constants;
                    # CSV values stay in the COPY data stream, outside SQL text.
                    with cursor.copy(f"COPY iteration2_v2.{name} ({', '.join(fields)}) FROM STDIN WITH (FORMAT CSV, HEADER TRUE)") as copy:
                        while block := stream.read(1024 * 1024):
                            copy.write(block)
            # Refresh planner statistics for the replacement dataset before commit.
            for name in CRASH_TABLES:
                cursor.execute(f"ANALYZE iteration2_v2.{name}")
    return {"build_id": report["build_id"], "event_rows": files["crash_event"][2],
            "hotspot_rows": files["hotspot"][2],
            "missing_point_rows": report["missing_coordinate_totals"]["crash_count"]}


def _relation_size(connection, relation):
    """Read a PostgreSQL relation's table/TOAST, index, and total byte sizes.

    Args:
        connection: Open psycopg connection used for this read-only query.
        relation: Existing schema-qualified relation name accepted as regclass.

    Returns:
        A dictionary with ``table_including_toast_bytes``, ``indexes_bytes``,
        and ``total_relation_bytes``.

    Raises:
        Exception: Propagates driver/SQL failures, including a missing relation.
    """
    # Include indexes and out-of-line TOAST storage when measuring physical cost.
    row = connection.execute("""
        SELECT pg_table_size(%s::regclass), pg_indexes_size(%s::regclass),
               pg_total_relation_size(%s::regclass)
    """, (relation, relation, relation)).fetchone()
    return dict(zip(("table_including_toast_bytes", "indexes_bytes", "total_relation_bytes"), row))


def measure_crashes(database_url, processed_dir=PROCESSED_DIR, output=None):
    """Replace crash data in a disposable database and report physical capacity.

    Args:
        database_url: PostgreSQL URL for a disposable database whose actual
            name starts with ``iteration2_test`` or ``iteration2_v2_test``.
            The caller must ensure the database can be overwritten; the name
            guard is not a backup or ownership check.
        processed_dir: Directory containing a prepared, internally consistent
            crash build, as accepted by ``load_crashes``.
        output: Optional destination for a JSON measurement report.

    Returns:
        Load counts, per-relation byte sizes, the crash-plus-notification budget
        result, and database-wide diagnostic sizes. The budget includes TOAST
        and indexes, excludes speed/other schemas/shared catalogs, and only
        observes notification rows already present in this test database.

    Raises:
        ValueError: If the actual database name is not disposable by the guard
            or the prepared build fails validation.
        Exception: Propagates file, connection, migration, load, and SQL errors.

    Side effects:
        Applies the crash-only migration, replaces the three crash tables, and
        VACUUM ANALYZEs them. These steps use separate transactions; a later
        measurement/output failure does not undo a committed load. Notification
        and speed relations are read-only here. If requested, creates the report
        directory and replaces the output through a ``.part`` file. No production
        database or future notification growth is represented by this result.
    """
    import psycopg

    # VACUUM requires autocommit; migration and loading use separate connections.
    with psycopg.connect(database_url, autocommit=True) as connection:
        database = connection.execute("SELECT current_database()").fetchone()[0]
        # Check the server's actual database name before any destructive operation.
        if not database.startswith(("iteration2_test", "iteration2_v2_test")):
            raise ValueError("Measurement requires a disposable database named iteration2_test* or iteration2_v2_test*")
        apply_crash_migration(database_url)
        loaded = load_crashes(database_url, processed_dir)
        # Clean up the freshly loaded crash tables before recording their sizes.
        for name in CRASH_TABLES:
            connection.execute(f"VACUUM (ANALYZE) iteration2_v2.{name}")
        relations = {name: _relation_size(connection, f"iteration2_v2.{name}") for name in CRASH_TABLES}
        crash_bytes = sum(r["total_relation_bytes"] for r in relations.values())
        # Count only notification storage already present in this test database.
        notification = connection.execute("SELECT to_regclass('iteration2_v2.notification_event')").fetchone()[0]
        notification_size = _relation_size(connection, notification) if notification else None
        # An absent notification table contributes zero bytes; its row count
        # remains None to distinguish absence from an existing empty table.
        notification_bytes = notification_size["total_relation_bytes"] if notification_size else 0
        notification_rows = connection.execute("SELECT count(*) FROM iteration2_v2.notification_event").fetchone()[0] if notification else None
        # Speed sizes are diagnostics and are excluded from the crash/notification budget.
        speed_bytes = 0
        for name in ("road_speed", "speed_rule", "calendar_period"):
            relation = connection.execute("SELECT to_regclass(%s)", (f"iteration2_v2.{name}",)).fetchone()[0]
            if relation:
                speed_bytes += _relation_size(connection, relation)["total_relation_bytes"]
        database_bytes = connection.execute("SELECT pg_database_size(current_database())").fetchone()[0]
    # Keep the scoped budget separate from database-wide overhead and unrelated data.
    result = {
        "measured_at": datetime.now(timezone.utc).isoformat(), "database": database,
        "load": loaded, "crash_relations": relations, "crash_total_bytes": crash_bytes,
        "notification": {"relation": str(notification) if notification else None, "rows": notification_rows,
                         "size": notification_size, "scope": "Observed disposable test database only; not production volume."},
        "budget": {"limit_bytes": BUDGET_BYTES, "speed_data_excluded": True,
                   "measured_crash_plus_notification_bytes": crash_bytes + notification_bytes,
                   "remaining_bytes": BUDGET_BYTES - crash_bytes - notification_bytes,
                   "within_limit": crash_bytes + notification_bytes <= BUDGET_BYTES,
                   "scope": "iteration2_v2 crash tables and its observed notification table including TOAST/indexes. Excludes speed, other schemas, shared catalogs, and unmeasured production notification growth."},
        "database_bytes": database_bytes, "current_v2_speed_relation_bytes": speed_bytes,
        "database_minus_current_v2_speed_bytes": database_bytes - speed_bytes,
        "database_overhead_note": "Diagnostic only: includes system catalogs, free pages and any other schemas; not a production budget measurement.",
    }
    # Optional report publication happens after the load has already committed.
    if output is not None:
        target = Path(output)
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_suffix(target.suffix + ".part")
        # A completed JSON replaces the previous report without exposing a partial file.
        part.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        part.replace(target)
    return result
