"""Create the schema and load prepared speed roads and rules into PostgreSQL.

This is the offline database-writing stage. apply_migration applies all of
this iteration's DDL, including calendar coverage, crash and notification tables.
load consumes prepared road_speed.csv and speed_rule.csv. Calendars are published
separately through calendar_storage, so a speed reload cannot replace a newer
calendar with the historical reference CSV. Crash and notification rows remain
independent. The backend owns request-time speed evaluation.
"""

import csv
from pathlib import Path

# Use the same ordered column definitions as the CSV producer to prevent the
# loader from silently mapping values to a different set of database columns.
from iteration3_v1_pipeline.build import ROAD_FIELDS, RULE_FIELDS
from iteration3_v1_pipeline.settings import ITERATION_DIR, PROCESSED_DIR


def apply_migration(database_url):
    """Apply this folder's numbered SQL migrations in one transaction.

    Args:
        database_url: PostgreSQL connection with permission to create schema
            objects and, if necessary, the PostGIS extension.

    Returns:
        None. A successful connection context exit commits the DDL.

    Raises:
        Exception: Propagates file, connection, permission, or SQL errors;
            database changes in this transaction are rolled back on failure.

    Side effects:
        Creates all nine tables and their indexes, constraints and trigger.
        Reapplication preserves existing rows. This is replayed idempotent DDL,
        not a version-tracking framework that upgrades arbitrary old columns.
        It neither loads datasets nor grants application-role privileges.
    """
    # Import the PostgreSQL driver only when a database operation is requested.
    import psycopg

    # Commit all schema statements together on success; an exception rolls back
    # this transaction rather than leaving only some of the new tables created.
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            # Numbered filenames impose the schema order: speed, crash,
            # notification, calendar coverage, then report email dispatch.
            # Query files are not migrations.
            for migration in sorted((ITERATION_DIR / "postgresql/migrations").glob("*.sql")):
                cursor.execute(migration.read_text(encoding="utf-8"))


def _check_csv(path, columns, *, allow_empty=False):
    """Check the exact CSV header and optional presence of bytes after it.

    columns is the expected ordered field tuple. Return None on success;
    raise ValueError for a mismatched header or disallowed empty body, and
    propagate file/decoding errors. Full row validation occurs during COPY and
    database constraint checks, not in this lightweight preflight.
    """
    # Check the ordered header before any table is cleared. This is a quick
    # structural check; PostgreSQL validates row values and constraints later.
    with path.open("rb") as stream:
        header = stream.readline().decode("utf-8")
        if tuple(next(csv.reader([header]), ())) != columns:
            raise ValueError(f"Unexpected CSV header: {path.name}")
        # Looking for one more byte detects a header-only file without reading
        # its full contents. Rules may be empty when all speeds are static.
        if not allow_empty and not stream.read(1):
            raise ValueError(f"Prepared CSV has no data: {path.name}")


def _copy_csv(cursor, table, columns, path, *, allow_empty=False):
    """Stream a prepared CSV through COPY using the caller's transaction.

    table and columns must be fixed internal contract identifiers, never user
    input. Return the COPY row count; reject an unavailable count or a zero-row
    result unless allow_empty is True. No commit is performed here, so a later
    load failure can roll back the entire replacement.
    """
    # Names come only from the fixed internal table/column contracts above.
    statement = f"COPY iteration2_v2.{table} ({', '.join(columns)}) FROM STDIN WITH (FORMAT CSV, HEADER TRUE)"
    # COPY reads local bytes through the connection; the database server does
    # not need filesystem access to the developer's CSV path.
    with path.open("rb") as stream:
        with cursor.copy(statement) as copy:
            # Stream 1 MiB blocks so the entire road dataset need not fit in RAM.
            while block := stream.read(1024 * 1024):
                copy.write(block)
    # Reject an unusable row count while the caller can still roll back its load.
    if cursor.rowcount < 0 or (not allow_empty and cursor.rowcount == 0):
        raise ValueError(f"Refusing to commit an empty {table} table")
    return cursor.rowcount


def load(database_url, processed_dir=PROCESSED_DIR):
    """Replace roads and speed rules in one transaction, preserving calendars.

    Args:
        database_url: PostgreSQL connection with TRUNCATE/INSERT privileges on
            the two speed tables; the schema must already exist.
        processed_dir: Directory containing road_speed.csv and speed_rule.csv.

    Returns:
        A dictionary with road_rows and rule_rows counts.

    Raises:
        ValueError: For wrong CSV headers, empty roads, or
            unacceptable COPY counts. A header-only speed_rule.csv is valid.
        Exception: Propagates input, connection, COPY, and constraint failures.

    Side effects:
        TRUNCATE, COPY and ANALYZE replace the two speed tables together.
        The road write trigger simplifies geometry in PostgreSQL. A failure
        rolls back this replacement. Calendar, crash and notification rows are
        untouched; calendar-load publishes calendar periods and coverage.
        This function does not migrate the schema or check a build manifest.
    """
    import psycopg

    # 1. Locate the build outputs and check their expected column order before
    # starting the replacement transaction. This function does not run build.
    processed_dir = Path(processed_dir)
    roads = processed_dir / "road_speed.csv"
    rules = processed_dir / "speed_rule.csv"
    _check_csv(roads, ROAD_FIELDS)
    _check_csv(rules, RULE_FIELDS, allow_empty=True)
    # 2. Replace roads and rules together in one transaction. If any
    # COPY, insert or constraint check fails, the previous dataset is restored
    # by rollback. Calendars have their own publication workflow; do not read or
    # replace them here, even when the historical reference CSV still exists.
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            # List both sides of the road/rule foreign key explicitly. Avoiding
            # CASCADE keeps the replacement limited to this dataset's tables.
            cursor.execute("""
                TRUNCATE TABLE iteration2_v2.speed_rule, iteration2_v2.road_speed
            """)
            # Insert parent road records before their dependent rule records.
            # The existing database trigger normalizes road geometry on write.
            road_count = _copy_csv(cursor, "road_speed", ROAD_FIELDS, roads)
            rule_count = _copy_csv(cursor, "speed_rule", RULE_FIELDS, rules, allow_empty=True)
            # Refresh planner statistics for queries against the replaced tables.
            cursor.execute("""
                ANALYZE iteration2_v2.road_speed;
                ANALYZE iteration2_v2.speed_rule;
            """)
    # Reaching here means the connection context committed successfully.
    return {"road_rows": road_count, "rule_rows": rule_count}
