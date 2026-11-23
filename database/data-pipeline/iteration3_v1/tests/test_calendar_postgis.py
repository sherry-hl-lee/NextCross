"""Check calendar publication against an explicitly disposable PostGIS database.

Preflight checks use validated synthetic CSV bundles without a database. The
opt-in integration tests recreate iteration2_v2 and verify annual replacement,
rollback, read-only preview, competing writers, and preservation of other data.
They never download sources or import the excluded reference query modules.
"""

from concurrent.futures import ThreadPoolExecutor
import csv
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ITERATION_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ITERATION_DIR / "src"))
from iteration3_v1_pipeline.calendar import build_calendar, validate_bundle
from iteration3_v1_pipeline.calendar_storage import CALENDAR_LOCK_NAMESPACE, load_calendar
from iteration3_v1_pipeline.load import apply_migration
from test_calendar import write_snapshot

TEST_DATABASE_URL = os.environ.get("ITERATION2_TEST_DATABASE_URL")


class BundleFixtures:
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle_number = 0

    def bundle(self, *, moved_holiday=False, revision=""):
        """Build real validated bundles from the clearly labelled test fixtures."""
        self.bundle_number += 1
        source = write_snapshot(self.root / f"source-{self.bundle_number}", year=2026)
        csv_path = source / "important_dates.csv"
        with csv_path.open(newline="") as stream:
            reader = csv.DictReader(stream)
            fields, records = reader.fieldnames, list(reader)
        for record in records:
            if moved_holiday and record["name"] == "Friday before the AFL grand final":
                # Both dates are Fridays. This is a synthetic source revision,
                # not an assertion about the actual AFL schedule.
                record["important_date"] = "18/09/2026"
            if revision and record["name"] == "New Year's Day":
                record["description"] += f" Fixture revision: {revision}."
        with csv_path.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(records)
        sources = json.loads((source / "sources.json").read_text())
        raw = csv_path.read_bytes()
        sources["sources"]["important_dates"].update(
            bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(),
        )
        (source / "sources.json").write_text(json.dumps(sources))
        report = build_calendar(source, 2026, output=self.root / "bundles")
        path = Path(report["input_dir"])
        return path, validate_bundle(path)[0]


class CalendarPreflightTests(BundleFixtures, unittest.TestCase):
    def test_write_requires_explicit_version_before_connecting(self):
        bundle, _ = self.bundle()
        with patch("psycopg.connect") as connect:
            with self.assertRaisesRegex(ValueError, "expected_current_build_id"):
                load_calendar("unused", bundle)
            with self.assertRaisesRegex(ValueError, "expected_current_build_id"):
                load_calendar("unused", bundle, expected_current_build_id="not-a-build")
        connect.assert_not_called()

    def test_modified_bundle_is_rejected_before_connecting(self):
        bundle, _ = self.bundle()
        with (bundle / "calendar.csv").open("ab") as stream:
            stream.write(b"\n")
        with patch("psycopg.connect") as connect:
            with self.assertRaises(ValueError):
                load_calendar("unused", bundle, expected_current_build_id="none")
        connect.assert_not_called()


@unittest.skipUnless(TEST_DATABASE_URL, "Set ITERATION2_TEST_DATABASE_URL for disposable PostGIS tests")
class CalendarPostgisTests(BundleFixtures, unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import psycopg

        cls.connection = psycopg.connect(TEST_DATABASE_URL, autocommit=True)
        database = cls.connection.execute("SELECT current_database()").fetchone()[0]
        if not database.startswith(("iteration2_test", "iteration2_v2_test")):
            cls.connection.close()
            raise ValueError("Calendar tests require a disposable iteration2_test* database")

    @classmethod
    def tearDownClass(cls):
        try:
            cls.connection.execute("DROP SCHEMA IF EXISTS iteration2_v2 CASCADE")
        finally:
            cls.connection.close()

    def setUp(self):
        super().setUp()
        self.connection.execute("DROP SCHEMA IF EXISTS iteration2_v2 CASCADE")
        apply_migration(TEST_DATABASE_URL)

    def state(self):
        periods = self.connection.execute("""
            SELECT * FROM iteration2_v2.calendar_period ORDER BY kind, start_date, end_date
        """).fetchall()
        coverage = self.connection.execute("SELECT * FROM iteration2_v2.calendar_coverage ORDER BY year").fetchall()
        return periods, coverage

    def seed_other_data(self):
        """Keep real FK-linked rows in every unrelated table during publication."""
        self.connection.execute("""
            INSERT INTO iteration2_v2.road_speed VALUES
              ('00000000-0000-0000-0000-000000000001',60,
               'SRID=4326;MULTILINESTRING((144.9 -37.8,144.901 -37.8))',
               false,90,ARRAY['School'],'scheduled');
            INSERT INTO iteration2_v2.speed_rule VALUES
              ('00000000-0000-0000-0000-000000000001',1,40,ARRAY[1,2,3,4,5],
               480,570,true,false,ARRAY['fixture-sign']);
            INSERT INTO iteration2_v2.crash_build
              (build_id,start_date,end_date,region,cohort_policy,eps_m,min_samples,
               crs_epsg,time_policy_version,cluster_policy_version,source_sha256,
               location_source_sha256,event_rows,hotspot_rows,missing_point_rows)
            VALUES ('fixture','2025-01-01','2025-12-31','Victoria','fixture',100,3,
                    3111,'fixture','fixture',repeat('a',64),repeat('b',64),1,1,0);
            INSERT INTO iteration2_v2.hotspot VALUES
              ('fixture-hotspot','fixture','SRID=4326;POINT(144.9 -37.8)','Fixture road');
            INSERT INTO iteration2_v2.crash_event VALUES
              ('fixture-event','fixture','fixture-hotspot','2025-01-01','morning',
               'SRID=4326;POINT(144.9 -37.8)','Fixture road',0,1);
            INSERT INTO iteration2_v2.notification_event (trip_id,event_id,occurred_at)
              VALUES ('fixture-trip','fixture-notification','2026-01-01T00:00:00Z');
            INSERT INTO iteration2_v2.report_email_dispatch
              (request_hash,digest,status,created_epoch)
              VALUES ('fixture-report','fixture-digest','ACCEPTED',1767225600);
            INSERT INTO iteration2_v2.calendar_period VALUES
              ('PUBLIC_HOLIDAY','2025-01-01','2025-01-01');
            INSERT INTO iteration2_v2.calendar_coverage
              (year,build_id,policy_version,verified_at,source_manifest)
              VALUES (2025,repeat('c',64),'fixture','2025-01-01T00:00:00Z','{}');
        """)

    def unrelated(self):
        result = {}
        for table in ("road_speed", "speed_rule", "crash_build", "hotspot", "crash_event", "notification_event", "report_email_dispatch"):
            result[table] = self.connection.execute(f"SELECT * FROM iteration2_v2.{table} ORDER BY 1,2").fetchall()
        result["calendar_period"] = self.connection.execute("""
            SELECT * FROM iteration2_v2.calendar_period WHERE start_date < '2026-01-01'
            ORDER BY kind, start_date, end_date
        """).fetchall()
        result["calendar_coverage"] = self.connection.execute("""
            SELECT * FROM iteration2_v2.calendar_coverage WHERE year <> 2026 ORDER BY year
        """).fetchall()
        return result

    def test_reviewed_bounded_publication_requires_opt_in_and_preserves_other_domains(self):
        """Check preview, explicit rollout consent, replay and history retention.

        The fixture includes unrelated speed/crash rows and both email tables;
        their equality before/after catches accidental cross-domain replacement.
        """
        from test_calendar_review import review_fixture
        from iteration3_v1_pipeline.calendar_review import build_reviewed_calendar

        source, corrected, review_file, _ = review_fixture(self.root)
        report = build_reviewed_calendar(source, 2026, corrected, review_file, self.root / "reviewed-bundles")
        bundle = report["input_dir"]
        self.seed_other_data()
        before = self.state(), self.unrelated()
        preview = load_calendar(TEST_DATABASE_URL, bundle, dry_run=True)
        self.assertEqual(preview["calendar_status"], "READY_BOUNDED")
        self.assertEqual((self.state(), self.unrelated()), before)
        with self.assertRaisesRegex(ValueError, "Bounded calendar publication"):
            load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        self.assertEqual((self.state(), self.unrelated()), before)
        result = load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none", allow_bounded_coverage=True)
        self.assertEqual(result["status"], "loaded")
        manifest = self.connection.execute("SELECT source_manifest FROM iteration2_v2.calendar_coverage WHERE year=2026").fetchone()[0]
        self.assertEqual(manifest["status"], "READY_BOUNDED")
        self.assertEqual(manifest["coverage_end"], "2026-04-30")
        again = load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id=result["target_build_id"], allow_bounded_coverage=True)
        self.assertEqual(again["status"], "unchanged")
        self.assertEqual(self.unrelated(), before[1])

    def test_migration_does_not_infer_coverage_from_existing_periods(self):
        self.connection.execute("""
            INSERT INTO iteration2_v2.calendar_period VALUES
                ('SCHOOL_TERM','2026-01-27','2026-04-02')
        """)
        before = self.state()
        apply_migration(TEST_DATABASE_URL)
        self.assertEqual(self.state(), before)
        self.assertEqual(before[1], [])

    def test_dry_run_is_read_only_and_does_not_wait_for_writer_lock(self):
        import psycopg

        bundle, manifest = self.bundle()
        self.seed_other_data()
        before = self.state(), self.unrelated()
        connect = psycopg.connect
        with self.connection.transaction():
            self.connection.execute("SELECT pg_advisory_xact_lock(%s,%s)", (CALENDAR_LOCK_NAMESPACE, 2026))
            # A mistaken writer lock would block behind this connection and time
            # out; a read-only dry run must finish without acquiring that lock.
            with patch("psycopg.connect", side_effect=lambda *args, **kwargs: connect(
                    *args, **kwargs, options="-c statement_timeout=1500 -c default_transaction_read_only=on")):
                result = load_calendar(TEST_DATABASE_URL, bundle, dry_run=True)
        self.assertEqual(result["status"], "dry_run")
        self.assertEqual(result["expected_current_build_id"], "none")
        self.assertEqual(result["target_build_id"], manifest["build_id"])
        self.assertEqual(result["counts"], {"current": 0, "target": 18, "added": 18, "removed": 0})
        self.assertEqual((self.state(), self.unrelated()), before)

    def test_publication_replaces_one_year_and_preserves_other_domains(self):
        bundle, manifest = self.bundle()
        changed_bundle, changed_manifest = self.bundle(moved_holiday=True)
        self.seed_other_data()
        untouched = self.unrelated()
        load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        preview = load_calendar(TEST_DATABASE_URL, changed_bundle, dry_run=True)
        self.assertEqual(preview["counts"], {"current": 18, "target": 18, "added": 1, "removed": 1})
        self.assertEqual(preview["removed"][0]["start_date"], "2026-09-25")
        self.assertEqual(preview["added"][0]["start_date"], "2026-09-18")
        result = load_calendar(TEST_DATABASE_URL, changed_bundle, expected_current_build_id=manifest["build_id"])
        self.assertEqual(result["status"], "loaded")
        self.assertEqual(self.connection.execute("""
            SELECT build_id, source_manifest FROM iteration2_v2.calendar_coverage WHERE year=2026
        """).fetchone(), (changed_manifest["build_id"], changed_manifest))
        self.assertEqual(self.unrelated(), untouched)

    def test_identical_publication_is_noop_and_stale_expectation_is_rejected(self):
        bundle, manifest = self.bundle()
        load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        before = self.state()
        result = load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id=manifest["build_id"])
        self.assertEqual(result["status"], "unchanged")
        self.assertFalse(result["changed"])
        self.assertEqual(self.state(), before)
        with self.assertRaisesRegex(ValueError, "version changed"):
            load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        self.assertEqual(self.state(), before)

    def test_period_tampering_is_detected_even_when_build_id_matches(self):
        bundle, manifest = self.bundle()
        load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        self.connection.execute("""
            DELETE FROM iteration2_v2.calendar_period
            WHERE kind='PUBLIC_HOLIDAY' AND start_date='2026-01-01'
        """)
        before = self.state()
        with self.assertRaisesRegex(ValueError, "no longer matches"):
            load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id=manifest["build_id"])
        self.assertEqual(self.state(), before)

    def test_cross_year_existing_interval_is_rejected_without_changes(self):
        bundle, _ = self.bundle()
        self.connection.execute("""
            INSERT INTO iteration2_v2.calendar_period VALUES
                ('SCHOOL_TERM','2025-12-31','2026-01-02')
        """)
        before = self.state()
        with self.assertRaisesRegex(ValueError, "cross the boundary"):
            load_calendar(TEST_DATABASE_URL, bundle, dry_run=True)
        with self.assertRaisesRegex(ValueError, "cross the boundary"):
            load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        self.assertEqual(self.state(), before)

    def test_metadata_write_failure_rolls_back_replaced_periods(self):
        import psycopg

        bundle, manifest = self.bundle()
        changed, _ = self.bundle(moved_holiday=True)
        load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        before = self.state()
        self.connection.execute("""
            CREATE FUNCTION iteration2_v2.reject_calendar_publication() RETURNS trigger
            LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic publication failure'; END $$;
            CREATE TRIGGER reject_calendar_publication BEFORE UPDATE ON iteration2_v2.calendar_coverage
            FOR EACH ROW EXECUTE FUNCTION iteration2_v2.reject_calendar_publication();
        """)
        with self.assertRaises(psycopg.errors.RaiseException):
            load_calendar(TEST_DATABASE_URL, changed, expected_current_build_id=manifest["build_id"])
        self.assertEqual(self.state(), before)

    def test_waiting_writer_checks_version_after_previous_writer_commits(self):
        from psycopg.types.json import Jsonb

        bundle, old = self.bundle()
        _, winner = self.bundle(revision="winning publication")
        losing_bundle, _ = self.bundle(moved_holiday=True)
        load_calendar(TEST_DATABASE_URL, bundle, expected_current_build_id="none")
        with ThreadPoolExecutor(max_workers=1) as pool:
            with self.connection.transaction():
                self.connection.execute("SELECT pg_advisory_xact_lock(%s,%s)", (CALENDAR_LOCK_NAMESPACE, 2026))
                waiting = pool.submit(load_calendar, TEST_DATABASE_URL, losing_bundle,
                                      expected_current_build_id=old["build_id"])
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    if self.connection.execute("""
                        SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory'
                            AND classid=%s AND objid=2026 AND NOT granted)
                    """, (CALENDAR_LOCK_NAMESPACE,)).fetchone()[0]:
                        break
                    time.sleep(0.01)
                else:
                    self.fail("The competing publisher did not wait for the year lock")
                # This fixture changes only source provenance, not the generated
                # dates, and publishes valid metadata before releasing the lock.
                self.connection.execute("""
                    UPDATE iteration2_v2.calendar_coverage SET
                        build_id=%s, policy_version=%s, verified_at=%s, source_manifest=%s
                    WHERE year=2026
                """, (winner["build_id"], winner["policy_version"], winner["verified_at"], Jsonb(winner)))
            with self.assertRaisesRegex(ValueError, "version changed"):
                waiting.result(timeout=5)
        self.assertEqual(self.connection.execute("""
            SELECT build_id FROM iteration2_v2.calendar_coverage WHERE year=2026
        """).fetchone()[0], winner["build_id"])


if __name__ == "__main__":
    unittest.main()
