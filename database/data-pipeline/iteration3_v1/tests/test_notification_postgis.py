"""Verify notification DDL and history preservation across pipeline operations.

These opt-in PostGIS tests recreate iteration2_v2 in an explicitly supplied
iteration2_test* database. They check nine-table migration, duplicate claims,
status constraints, and notification preservation during migration/reloads and
capacity measurement. Notification rows are synthetic; no delivery provider is
called. Database absence skips the suite; a non-test database name is rejected.
"""

import contextlib
import csv
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ITERATION_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ITERATION_DIR / "src"))
from iteration3_v1_pipeline.__main__ import main
from iteration3_v1_pipeline.build import ROAD_FIELDS, RULE_FIELDS
from iteration3_v1_pipeline.calendar import build_calendar
from iteration3_v1_pipeline.calendar_storage import load_calendar
from iteration3_v1_pipeline.crash import SOURCE_FIELDS, build_crashes
from iteration3_v1_pipeline.crash_storage import load_crashes, measure_crashes
from iteration3_v1_pipeline.load import apply_migration, load
from test_calendar import write_snapshot

TEST_DATABASE_URL = os.environ.get("ITERATION2_TEST_DATABASE_URL")
EXPECTED_TABLES = {"road_speed", "speed_rule", "calendar_period", "crash_build",
                   "hotspot", "crash_event", "notification_event", "calendar_coverage",
                   "report_email_dispatch"}


@unittest.skipUnless(TEST_DATABASE_URL, "Set ITERATION2_TEST_DATABASE_URL for disposable PostGIS tests")
class NotificationPostgisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import psycopg

        cls.connection = psycopg.connect(TEST_DATABASE_URL, autocommit=True)
        database = cls.connection.execute("SELECT current_database()").fetchone()[0]
        if not database.startswith("iteration2_test"):
            cls.connection.close()
            raise ValueError("Notification tests require a disposable iteration2_test* database")

    @classmethod
    def tearDownClass(cls):
        try:
            cls.connection.execute("DROP SCHEMA IF EXISTS iteration2_v2 CASCADE")
        finally:
            cls.connection.close()

    def setUp(self):
        self.connection.execute("DROP SCHEMA IF EXISTS iteration2_v2 CASCADE")
        apply_migration(TEST_DATABASE_URL)

    def claim(self):
        return self.connection.execute("""
            INSERT INTO iteration2_v2.notification_event (trip_id,event_id,occurred_at)
            VALUES ('trip-1','event-1','2026-09-12T00:00:00Z')
            ON CONFLICT (trip_id,event_id) DO NOTHING RETURNING trip_id
        """).fetchone()

    def notification(self):
        return self.connection.execute("SELECT * FROM iteration2_v2.notification_event ORDER BY trip_id,event_id").fetchall()

    def seed_report_dispatch(self):
        """Seed backend-owned delivery state that dataset refreshes must retain."""
        self.connection.execute("""
            INSERT INTO iteration2_v2.report_email_dispatch
                (request_hash, digest, status, reason, created_epoch)
            VALUES ('request-1', 'digest-1', 'ACCEPTED', NULL, 1789171200)
        """)

    def report_dispatch(self):
        """Read complete ordered dispatch rows for before/after equality checks."""
        return self.connection.execute("SELECT * FROM iteration2_v2.report_email_dispatch ORDER BY request_hash").fetchall()

    def prepare(self, root):
        with (root / "road_speed.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(ROAD_FIELDS)
            writer.writerow(("00000000-0000-0000-0000-000000000001", 60,
                "SRID=4326;MULTILINESTRING((144.9 -37.8,144.901 -37.8))", True, 90,
                '{"Normal Operation"}', "static"))
        with (root / "speed_rule.csv").open("w", newline="") as stream:
            csv.writer(stream).writerow(RULE_FIELDS)
        source, locations = root / "source.csv", root / "locations.csv"
        with source.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=SOURCE_FIELDS)
            writer.writeheader()
            writer.writerow({"ACCIDENT_NO": "event-a", "ACCIDENT_DATE": "2025-01-15",
                "ACCIDENT_TIME": "08:30", "LATITUDE": "-37.8", "LONGITUDE": "144.9",
                "YOUNG_DRIVER_18_25": "1", "INJ_OR_FATAL": "1", "FATALITY": "0",
                "SERIOUSINJURY": "1", "OTHERINJURY": "0"})
        locations.write_text("ACCIDENT_NO,ROAD_NAME,ROAD_TYPE,ROAD_NAME_INT,ROAD_TYPE_INT\n")
        build_crashes(source, root, location_source=locations)

    def test_cli_migrate_creates_all_nine_tables(self):
        self.connection.execute("DROP SCHEMA iteration2_v2 CASCADE")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            main(["migrate", "--database-url", TEST_DATABASE_URL])
        self.assertEqual(json.loads(output.getvalue())["notification_table"], "ready")
        tables = {r[0] for r in self.connection.execute("SELECT tablename FROM pg_tables WHERE schemaname='iteration2_v2'")}
        self.assertEqual(tables, EXPECTED_TABLES)

    def test_defaults_idempotent_claim_and_status_constraints(self):
        import psycopg

        self.assertEqual(self.claim(), ("trip-1",))
        self.assertIsNone(self.claim())
        row = self.notification()[0]
        self.assertEqual(row[3:6], ("pending", None, None))
        self.assertIsNotNone(row[6])
        self.assertIsNone(row[7])
        with self.assertRaises(psycopg.errors.UniqueViolation):
            self.connection.execute("INSERT INTO iteration2_v2.notification_event SELECT * FROM iteration2_v2.notification_event")
        with self.assertRaises(psycopg.errors.CheckViolation):
            self.connection.execute("UPDATE iteration2_v2.notification_event SET status='invalid'")
        self.connection.execute("UPDATE iteration2_v2.notification_event SET status='failed',error_message='test failure'")
        self.connection.execute("UPDATE iteration2_v2.notification_event SET status='sent',sent_at=CURRENT_TIMESTAMP,resend_email_id='test-delivery',error_message=NULL")
        self.assertEqual(self.notification()[0][3:6], ("sent", "test-delivery", None))
        self.assertIsNone(self.claim())

    def test_repeated_migration_and_dataset_loads_preserve_history(self):
        self.claim()
        self.seed_report_dispatch()
        original_dispatch = self.report_dispatch()
        self.connection.execute("UPDATE iteration2_v2.notification_event SET status='sent',sent_at=CURRENT_TIMESTAMP,resend_email_id='recorded-delivery'")
        original = self.notification()
        # A speed reload must preserve an independently published calendar,
        # including dates outside the historical reference CSV's 2026 coverage.
        self.connection.execute("""
            INSERT INTO iteration2_v2.calendar_period VALUES
                ('PUBLIC_HOLIDAY', '2029-01-01', '2029-01-01')
        """)
        self.connection.execute("""
            INSERT INTO iteration2_v2.calendar_coverage
                (year, build_id, policy_version, verified_at, source_manifest)
            VALUES (2029, %s, 'test-calendar-policy', '2029-01-01T00:00:00Z',
                    '{"fixture":"calendar preservation only"}'::jsonb)
        """, ("a" * 64,))
        calendar = self.connection.execute("SELECT * FROM iteration2_v2.calendar_period").fetchall()
        coverage = self.connection.execute("SELECT * FROM iteration2_v2.calendar_coverage").fetchall()
        apply_migration(TEST_DATABASE_URL)
        self.assertEqual(self.notification(), original)
        self.assertEqual(self.report_dispatch(), original_dispatch)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.prepare(root)
            self.assertEqual(load(TEST_DATABASE_URL, root), {"road_rows": 1, "rule_rows": 0})
            load_crashes(TEST_DATABASE_URL, root)
            source = write_snapshot(root / "calendar-source", year=2026)
            bundle = build_calendar(source, 2026, output=root / "calendar-build")
            load_calendar(TEST_DATABASE_URL, bundle["input_dir"], expected_current_build_id="none")
        self.assertEqual(self.notification(), original)
        self.assertEqual(self.report_dispatch(), original_dispatch)
        self.assertEqual(self.connection.execute("SELECT * FROM iteration2_v2.calendar_period WHERE start_date >= '2029-01-01'").fetchall(), calendar)
        self.assertEqual(self.connection.execute("SELECT * FROM iteration2_v2.calendar_coverage WHERE year=2029").fetchall(), coverage)
        self.assertEqual(self.connection.execute("SELECT count(*) FROM iteration2_v2.road_speed").fetchone()[0], 1)
        self.assertEqual(self.connection.execute("SELECT count(*) FROM iteration2_v2.crash_event").fetchone()[0], 1)

    def test_report_dispatch_contract_rejects_duplicate_key_and_unknown_status(self):
        """Keep report delivery's key/status contract separate from notifications."""
        import psycopg

        self.seed_report_dispatch()
        original = self.report_dispatch()
        with self.assertRaises(psycopg.errors.UniqueViolation):
            self.connection.execute("INSERT INTO iteration2_v2.report_email_dispatch SELECT * FROM iteration2_v2.report_email_dispatch")
        with self.assertRaises(psycopg.errors.CheckViolation):
            self.connection.execute("UPDATE iteration2_v2.report_email_dispatch SET status='sent'")
        with self.assertRaises(psycopg.errors.NotNullViolation):
            self.connection.execute("UPDATE iteration2_v2.report_email_dispatch SET created_epoch=NULL")
        self.assertEqual(self.report_dispatch(), original)

    def test_failed_speed_replacement_rolls_back_existing_data_and_email_history(self):
        """Force a COPY constraint failure after replacement starts, then compare state."""
        import psycopg

        self.claim()
        self.seed_report_dispatch()
        original_history = (self.notification(), self.report_dispatch())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.prepare(root)
            load(TEST_DATABASE_URL, root)
            original = self.connection.execute("SELECT source_speed_zone_id,speed_kmh,ST_AsEWKT(road_geom::geometry) FROM iteration2_v2.road_speed").fetchall()
            with (root / "road_speed.csv").open(newline="") as stream:
                reader = csv.DictReader(stream)
                rows = list(reader)
            rows[0]["speed_kmh"] = "0"
            with (root / "road_speed.csv").open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=ROAD_FIELDS)
                writer.writeheader()
                writer.writerows(rows)
            with self.assertRaises(psycopg.errors.CheckViolation):
                load(TEST_DATABASE_URL, root)
            self.assertEqual(self.connection.execute("SELECT source_speed_zone_id,speed_kmh,ST_AsEWKT(road_geom::geometry) FROM iteration2_v2.road_speed").fetchall(), original)
        self.assertEqual((self.notification(), self.report_dispatch()), original_history)

    def test_failed_crash_replacement_rolls_back_all_three_tables_and_email_history(self):
        """Inject a database-side failure to test rollback beyond file validation."""
        import psycopg

        self.claim()
        self.seed_report_dispatch()
        original_history = (self.notification(), self.report_dispatch())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.prepare(root)
            load_crashes(TEST_DATABASE_URL, root)
            tables = ("crash_build", "hotspot", "crash_event")
            original = {table: self.connection.execute(f"SELECT to_jsonb(t) FROM iteration2_v2.{table} t").fetchall() for table in tables}
            self.connection.execute("""
                CREATE FUNCTION iteration2_v2.reject_crash_insert() RETURNS trigger
                LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic publication failure'; END; $$;
                CREATE TRIGGER reject_crash_insert BEFORE INSERT ON iteration2_v2.crash_build
                FOR EACH ROW EXECUTE FUNCTION iteration2_v2.reject_crash_insert();
            """)
            with self.assertRaises(psycopg.errors.RaiseException):
                load_crashes(TEST_DATABASE_URL, root)
            restored = {table: self.connection.execute(f"SELECT to_jsonb(t) FROM iteration2_v2.{table} t").fetchall() for table in tables}
            self.assertEqual(restored, original)
        self.assertEqual((self.notification(), self.report_dispatch()), original_history)

    def test_capacity_measurement_counts_current_notification_and_preserves_it(self):
        self.claim()
        original = self.notification()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.prepare(root)
            result = measure_crashes(TEST_DATABASE_URL, root)
        self.assertEqual(result["notification"]["relation"], "iteration2_v2.notification_event")
        self.assertEqual(result["notification"]["rows"], 1)
        self.assertEqual(result["budget"]["measured_crash_plus_notification_bytes"],
            result["crash_total_bytes"] + result["notification"]["size"]["total_relation_bytes"])
        self.assertTrue(result["budget"]["speed_data_excluded"])
        self.assertEqual(self.notification(), original)


if __name__ == "__main__":
    unittest.main()
