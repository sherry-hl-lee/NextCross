"""Inspect and publish one validated calendar year without reloading road data.

The local bundle is validated before opening PostgreSQL. A dry run reads one
consistent snapshot and takes no writer locks. Publication serializes writers
for the same year, checks the expected version, and replaces that year's periods
and coverage together, including the source-scope limitations in the manifest.

The calendar-load command calls load_calendar() with the bundle directory made
by calendar-build or calendar-review. calendar.csv holds the periods, while
calendar_manifest.json records their source, validation policy, hashes, and
coverage limitations.
calendar_period stores the dates used by the backend; calendar_coverage stores
the matching publication metadata. No schema changes, grants, source downloads,
or backend request-time evaluation occur here.
"""

from datetime import date, datetime
import hashlib
import re

from iteration3_v1_pipeline.calendar import (
    CALENDAR_FIELDS, calendar_csv_bytes, validate_bundle,
)


# Use the two-integer advisory-lock namespace so distinct calendar years can be
# published independently, including a first publication with no coverage row.
CALENDAR_LOCK_NAMESPACE = int.from_bytes(b"CAL2", "big")


def _expected_version(value):
    """Normalize the caller's expected published version before connecting.

    The string ``none`` explicitly means that no coverage row should exist yet.
    Python None means the caller omitted a required write guard, so it is invalid.
    """
    # The literal token distinguishes an intentional first publication from an
    # omitted argument, which must not silently overwrite an existing calendar.
    if value == "none":
        return None
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        raise ValueError("expected_current_build_id must be a 64-character build ID or 'none'")
    return value


def _read_current(cursor, year):
    """Return ``(coverage, rows)`` for comparison with the prepared bundle.

    Coverage is a metadata dictionary, or None before the first publication.
    Period dates become ISO strings so the database rows and CSV rows use the
    same representation when calculating differences and integrity hashes.
    """
    # A coverage row identifies the published version, independently of whether
    # older calendar_period rows already exist for this year.
    cursor.execute("""
        SELECT build_id, policy_version, verified_at, loaded_at, source_manifest
        FROM iteration2_v2.calendar_coverage WHERE year = %s
    """, (year,))
    record = cursor.fetchone()
    coverage = None if record is None else dict(zip(
        ("build_id", "policy_version", "verified_at", "loaded_at", "source_manifest"), record,
    ))
    # Overlap selection also finds a malformed existing interval whose start is
    # in another year. Never silently split or delete such an interval.
    cursor.execute("""
        SELECT kind, start_date, end_date FROM iteration2_v2.calendar_period
        WHERE start_date <= %s AND end_date >= %s
        ORDER BY kind, start_date, end_date
    """, (date(year, 12, 31), date(year, 1, 1)))
    periods = cursor.fetchall()
    if any(start.year != year or end.year != year for _, start, end in periods):
        raise ValueError(f"Existing calendar periods cross the boundary of {year}")
    rows = [dict(zip(CALENDAR_FIELDS, (kind, start.isoformat(), end.isoformat())))
            for kind, start, end in periods]
    return coverage, rows


def _check_published_state(year, coverage, rows):
    """Reject calendar edits that no longer match their published metadata.

    Legacy rows without coverage may be adopted by an explicitly requested first
    publication. Once coverage exists, its original manifest must still describe
    the periods; matching build IDs alone do not establish that integrity.
    """
    if coverage is None:
        # Pre-coverage imports have no manifest to check. The caller must still
        # supply the explicit "none" token before replacing these legacy rows.
        return
    manifest = coverage["source_manifest"]
    # Recreate the exact CSV representation used during the build so a manual
    # database edit cannot hide behind an unchanged published build ID.
    raw = calendar_csv_bytes(rows)
    try:
        # Check both the coverage row's metadata and the period rows' content
        # against the saved manifest; the candidate bundle is validated separately.
        consistent = (
            manifest["status"] in ("READY", "READY_BOUNDED")
            and manifest["year"] == year
            and manifest["build_id"] == coverage["build_id"]
            and manifest["policy_version"] == coverage["policy_version"]
            and datetime.fromisoformat(manifest["verified_at"]) == coverage["verified_at"]
            and manifest["file"]["columns"] == list(CALENDAR_FIELDS)
            and manifest["file"]["rows"] == len(rows)
            and manifest["file"]["bytes"] == len(raw)
            and manifest["file"]["sha256"] == hashlib.sha256(raw).hexdigest()
        )
    except (KeyError, TypeError, ValueError):
        # Missing or malformed metadata is inconsistent, not a first publication.
        consistent = False
    if not consistent:
        raise ValueError(f"Published calendar data or metadata for {year} no longer matches its manifest")


def _difference(manifest, rows, coverage, current_rows):
    """Describe row and provenance changes without writing to PostgreSQL.

    A row is identified by its kind and inclusive start/end dates. The returned
    expected_current_build_id is the token to pass when publishing this preview.
    Include supported dates and the bounded-backend requirement in the dry-run
    result so the reviewer sees the actual publication scope. Metadata changes
    still require publication even when all dates are unchanged.
    """
    # Set differences ignore input order; sorting keeps previews deterministic.
    keys = lambda items: {tuple(row[field] for field in CALENDAR_FIELDS) for row in items}
    current, target = keys(current_rows), keys(rows)
    added, removed = sorted(target - current), sorted(current - target)
    current_id = coverage["build_id"] if coverage else None
    # Source verification time and other manifest details may change even when
    # the source content has the same build ID and produces identical periods.
    metadata_changed = coverage is None or coverage["source_manifest"] != manifest
    return {
        "year": manifest["year"],
        "calendar_status": manifest["status"],
        "coverage_start": manifest["coverage_start"],
        "coverage_end": manifest["coverage_end"],
        "requires_bounded_backend": manifest.get("requires_bounded_backend", False),
        "current_build_id": current_id,
        "target_build_id": manifest["build_id"],
        "expected_current_build_id": current_id or "none",
        "added": [dict(zip(CALENDAR_FIELDS, row)) for row in added],
        "removed": [dict(zip(CALENDAR_FIELDS, row)) for row in removed],
        "counts": {"current": len(current_rows), "target": len(rows),
                   "added": len(added), "removed": len(removed)},
        "record_counts": manifest["record_counts"],
        "metadata_changed": metadata_changed,
        "changed": bool(added or removed or metadata_changed),
    }


def load_calendar(database_url, input_dir, *, dry_run=False, expected_current_build_id=None,
                  allow_bounded_coverage=False):
    """Compare or publish a calendar year within its declared source coverage scope.

    A write requires the build ID returned by dry-run, or the literal ``none``
    when no coverage exists. Calendar writers recheck that expectation after
    taking the year's transaction advisory lock. The token guards published
    versions; before the first publication, existing legacy rows have no version.

    Return added/removed rows, counts, compared and target build IDs, and status
    ``dry_run``, ``loaded``, or ``unchanged``. A repeated identical publication
    preserves loaded_at. Validation and stale/inconsistent-state failures raise
    ValueError; database failures propagate and roll back the replacement.

    input_dir must identify one validated build bundle, not the raw CSV folder.
    Dry-run needs no expected version and reports the token for a later write;
    it does not reserve that version against concurrent publications.

    Bounded reviewed calendars require ``allow_bounded_coverage=True`` for a
    write, asserting that every consuming backend checks the published bounds.
    A dry-run remains available without that assertion and exposes the bounds.

    Args:
        database_url: Connection for the existing iteration2_v2 schema; a dry
            run needs reads, while publication also needs write privileges.
        input_dir: Local schema-3 or schema-4 bundle accepted by validate_bundle.
        dry_run: Report the current-to-candidate difference without writing.
        expected_current_build_id: Previously reviewed build ID, or ``none``
            for first publication. Required for writes and ignored by dry-run.
        allow_bounded_coverage: Operator confirmation of backend compatibility;
            this function cannot inspect or deploy the backend itself.

    Side effects:
        Reads current state. A successful write replaces this year's periods
        and coverage in one transaction, retaining other years, other datasets
        and both application email-history tables. No migrations are run.
    """
    # Do not connect or take locks until the candidate CSV and manifest agree.
    manifest, rows = validate_bundle(input_dir)
    # A valid bounded bundle alone cannot prove that its consumers enforce the
    # bounds. Fail before connecting unless the operator confirms that rollout.
    if manifest.get("requires_bounded_backend") and not dry_run and allow_bounded_coverage is not True:
        raise ValueError("Bounded calendar publication requires explicit allow_bounded_coverage=True and a backend that checks coverage bounds")
    # A preview discovers the current version; only a write must name the
    # version it expects to replace (or explicitly expect no published version).
    expected = None if dry_run else _expected_version(expected_current_build_id)
    year = manifest["year"]

    import psycopg
    from psycopg.types.json import Jsonb

    # The connection context commits on success and rolls back on an exception,
    # keeping the period rows and their coverage manifest in one transaction.
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            if dry_run:
                # Both SELECTs see the same snapshot even if another publisher
                # commits between them. No publication advisory lock is taken.
                cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            else:
                # READ COMMITTED gives a waiting writer fresh metadata after the
                # preceding publisher commits and releases this year's lock.
                cursor.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED, READ WRITE")
                # Serialize cooperating publishers for this year, even when no
                # coverage row exists to lock. Commit/rollback releases the lock.
                cursor.execute("SELECT pg_advisory_xact_lock(%s, %s)",
                               (CALENDAR_LOCK_NAMESPACE, year))
            coverage, current_rows = _read_current(cursor, year)
            _check_published_state(year, coverage, current_rows)
            result = _difference(manifest, rows, coverage, current_rows)
            if dry_run:
                return {**result, "status": "dry_run", "dry_run": True}
            # Check after acquiring the lock: the preview may have become stale
            # while this publisher waited for another transaction to finish.
            if result["current_build_id"] != expected:
                raise ValueError("Calendar version changed; run dry-run again before publishing")
            if not result["changed"]:
                # Re-publishing an identical bundle performs no writes, leaving
                # loaded_at as the time of the last actual publication.
                return {**result, "status": "unchanged", "dry_run": False}

            # DELETE/INSERT keeps ordinary MVCC snapshots readable and affects
            # only this year. A later failure also rolls back these deletions.
            cursor.execute("""
                DELETE FROM iteration2_v2.calendar_period
                WHERE start_date <= %s AND end_date >= %s
            """, (date(year, 12, 31), date(year, 1, 1)))
            cursor.executemany("""
                INSERT INTO iteration2_v2.calendar_period (kind, start_date, end_date)
                VALUES (%s, %s, %s)
            """, [tuple(row[field] for field in CALENDAR_FIELDS) for row in rows])
            # Insert coverage for a first publication, or replace its metadata
            # for an existing year in the same transaction as the period rows.
            # verified_at is the source-check time; loaded_at is publication time
            # (the table default on INSERT, CURRENT_TIMESTAMP on UPDATE).
            # Jsonb preserves the complete manifest, including coverage limits
            # and the CSV hash needed to detect later edits to the stored dates.
            cursor.execute("""
                INSERT INTO iteration2_v2.calendar_coverage
                    (year, build_id, policy_version, verified_at, source_manifest)
                VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (year) DO UPDATE SET
                    build_id = EXCLUDED.build_id,
                    policy_version = EXCLUDED.policy_version,
                    verified_at = EXCLUDED.verified_at,
                    loaded_at = CURRENT_TIMESTAMP,
                    source_manifest = EXCLUDED.source_manifest
            """, (year, manifest["build_id"], manifest["policy_version"],
                  manifest["verified_at"], Jsonb(manifest)))
    # Reaching here means the connection context successfully committed both.
    return {**result, "status": "loaded", "dry_run": False}
