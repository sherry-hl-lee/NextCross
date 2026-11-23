"""Verify candidate integrity, domain isolation and reviewed publication gates.

OperationTests uses synthetic source files and mocked database/network boundaries
to exercise failures before a write, baseline adoption, durable annual history
and date rollover. Mocked success is not evidence of production connectivity.

OperationsPostgisTests additionally exercises the real loaders and status reader
only when ITERATION2_TEST_DATABASE_URL is set. Its setup checks the server's
database name before dropping/recreating iteration2_v2. These tests must use a
disposable database and must not run concurrently with another schema-resetting
suite against the same database. Neither class needs live official downloads.
"""

from contextlib import contextmanager, redirect_stderr
from copy import deepcopy
from datetime import date, datetime, timezone
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from deploy import operations as ops
from iteration3_v1_pipeline.operations_policy import annual_range, baseline_range
from test_deployment import speed_download, calendar_download, COMMIT
from test_crash import crash, write_source, write_locations


def crash_download(output, first=2022, last=2026):
    """Write synthetic crash inputs and matching source metadata, without HTTP.

    The inclusive year range contains one qualifying event in every month so
    completeness failures can be introduced deliberately by individual tests.
    Creates output and its CSV/JSON files; returns None.
    """
    output.mkdir(parents=True, exist_ok=True)
    rows = [crash(f"{year}-{month}", ACCIDENT_DATE=f"{year}-{month:02}-15")
            for year in range(first, last + 1) for month in range(1, 13)]
    write_source(output / "crashes.csv", rows)
    write_locations(output / "accident-location.csv", [])
    for name in ("crashes", "accident-location"):
        ops._write(output / f"{name}.source.json", {
            "sha256": ops.sha256(output / f"{name}.csv"),
            "resource": {"reporting_period_end": f"{last}-12-31"}})


class OperationTests(unittest.TestCase):
    """Exercise local preparation and publication policy without a live DB."""

    def setUp(self):
        """Give each test an isolated candidate/report directory and cleanup."""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bundle = self.root / "candidate"

    def speed(self):
        """Prepare a tiny speed candidate and assert no other source is fetched."""
        with patch.object(ops, "download", side_effect=speed_download), \
             patch.object(ops, "download_crashes") as crashes, patch.object(ops, "download_calendar") as calendar:
            result = ops.prepare("speed", COMMIT, self.bundle, as_of="2026-10-06")
        crashes.assert_not_called()
        calendar.assert_not_called()
        return result

    def crashes(self):
        """Prepare the first annual release using synthetic September 2027 inputs."""
        with patch.object(ops, "download_crashes", side_effect=crash_download), \
             patch("iteration3_v1_pipeline.operations_policy.melbourne_today", return_value=date(2027, 9, 5)):
            return ops.prepare("crashes", COMMIT, self.bundle, release_year=2027)

    def test_speed_is_independent_and_retains_both_raw_sources(self):
        """Retain reproducible zone/sign inputs and the exact speed as-of date."""
        result = self.speed()
        self.assertEqual(result["counts"]["road_rows"], 1)
        self.assertEqual(result["period"], {"as_of": "2026-10-06"})
        self.assertTrue((self.bundle / "raw/speed-zones.geojson").exists())
        self.assertTrue((self.bundle / "raw/speed-signs.geojson").exists())
        self.assertEqual(ops.validate_candidate(self.bundle, result["candidate_id"], COMMIT)["dataset"], "speed")

    def test_candidate_change_commit_mismatch_and_nested_manifest_rejected(self):
        """Bind approval to both producer and complete file inventory."""
        result = self.speed()
        for expected_id, commit in (("a" * 64, COMMIT), (result["candidate_id"], "b" * 40)):
            with self.assertRaises(ops.OperationError):
                ops.validate_candidate(self.bundle, expected_id, commit)
        (self.bundle / "raw/candidate_manifest.json").write_text("{}")
        with self.assertRaisesRegex(ops.OperationError, "files differ"):
            ops.validate_candidate(self.bundle)

    def test_symlinks_and_immutable_destination_rejected(self):
        """Reject external file references and reuse of a saved candidate path."""
        self.speed()
        with self.assertRaisesRegex(ops.OperationError, "immutable"):
            ops.prepare("speed", COMMIT, self.bundle)
        (self.bundle / "raw/extra").symlink_to(self.root / "outside")
        with self.assertRaisesRegex(ops.OperationError, "symlinks"):
            ops.validate_candidate(self.bundle)

    def test_annual_policy_does_not_roll_at_new_year_or_before_september(self):
        """Keep the accepted baseline fixed until the explicit September window."""
        self.assertEqual(baseline_range(), (date(2021, 1, 1), date(2025, 12, 31)))
        for today in (date(2026, 10, 6), date(2027, 1, 1), date(2027, 8, 31)):
            with self.assertRaises(ValueError):
                annual_range(2027, today=today)
        self.assertEqual(annual_range(2027, today=date(2027, 9, 1)), (date(2022, 1, 1), date(2026, 12, 31)))
        self.assertEqual(annual_range(2028, today=date(2028, 9, 1)), (date(2023, 1, 1), date(2027, 12, 31)))

    def test_crash_candidate_checks_every_source_month_and_freezes_outputs(self):
        """Require all 60 source months even when an output report is readable."""
        result = self.crashes()
        self.assertEqual(result["period"]["start"], "2022-01-01")
        self.assertEqual(result["period"]["end"], "2026-12-31")
        self.assertEqual(result["counts"]["event_rows"], 60)
        self.assertEqual(result["quality"]["status"], "PASS")
        self.assertTrue((self.bundle / "raw/accident-location.source.json").is_file())
        report = ops._json(self.bundle / "payload/crash_report.json")
        report["source_rows_by_month"].pop("2026-12")
        ops._write(self.bundle / "payload/crash_report.json", report)
        quality = ops._quality(self.bundle, "crashes", result["counts"], result["period"])
        self.assertIn("MISSING_SOURCE_MONTHS", quality["issues"])

    def test_crash_source_end_required_and_original_hash_bound(self):
        """Check source period evidence and bind outputs to their original bytes."""
        result = self.crashes()
        path = self.bundle / "raw/accident-location.source.json"
        metadata = ops._json(path)
        metadata["resource"]["reporting_period_end"] = "2026-01-31"
        ops._write(path, metadata)
        quality = ops._quality(self.bundle, "crashes", result["counts"], result["period"])
        self.assertIn("SOURCE_PERIOD_UNCONFIRMED:accident-location", quality["issues"])
        manifest = ops._json(self.bundle / "candidate_manifest.json")
        # Rehash the outer candidate deliberately: internal source/report linkage
        # must still reject changed raw data, not just stale manifest hashes.
        manifest["files"] = ops._files(self.bundle)
        manifest.pop("candidate_id")
        manifest["candidate_id"] = ops._digest(manifest)
        ops._write(self.bundle / "candidate_manifest.json", manifest)
        (self.bundle / "raw/crashes.csv").write_text("changed")
        manifest["files"] = ops._files(self.bundle)
        manifest.pop("candidate_id")
        manifest["candidate_id"] = ops._digest(manifest)
        ops._write(self.bundle / "candidate_manifest.json", manifest)
        with self.assertRaisesRegex(ops.OperationError, "frozen original"):
            ops.validate_candidate(self.bundle)

    def test_freeze_baseline_without_download_and_restore_in_later_year(self):
        """Permit legacy recovery with its warning, without inventing provenance."""
        raw, payload = self.root / "old-raw", self.root / "old-payload"
        crash_download(raw, 2021, 2025)
        sidecar = ops._json(raw / "accident-location.source.json")
        sidecar.pop("resource")
        sidecar["resource_id"] = "e1beb92b-5836-448a-9769-e7aa6a9e7413"
        ops._write(raw / "accident-location.source.json", sidecar)
        ops.build_crashes(raw / "crashes.csv", payload, location_source=raw / "accident-location.csv")
        with patch.object(ops, "download_crashes") as network:
            result = ops.prepare("crashes", COMMIT, self.bundle, frozen_input=payload, frozen_source_dir=raw)
        network.assert_not_called()
        self.assertIsNone(result["release_year"])
        self.assertEqual(result["quality"]["status"], "PASS")
        self.assertEqual(result["quality"]["warnings"], ["LEGACY_BASELINE_LOCATION_PERIOD_UNCONFIRMED"])
        with self.assertRaisesRegex(ops.OperationError, "recovery"):
            ops._publication_gate(result, {"crashes": None}, history=[])
        with patch.object(ops, "melbourne_today", return_value=date(2029, 1, 1)):
            ops._publication_gate(result, {"crashes": None}, restore=True, history=[])

    def test_annual_published_period_cannot_be_silently_refreshed(self):
        """Separate exact historical recovery from normal latest-release updates."""
        result = self.crashes()
        state = {"crashes": {"end": "2026-12-31", "build_id": "different", "event_rows": 60}}
        with patch.object(ops, "melbourne_today", return_value=date(2027, 9, 5)):
            with self.assertRaisesRegex(ops.OperationError, "already published"):
                ops._publication_gate(result, state, history=[])
            with self.assertRaisesRegex(ops.OperationError, "previously successful"):
                ops._publication_gate(result, state, restore=True, history=[])
            ops._publication_gate(result, state, restore=True, history=[{
                "status": "complete", "dataset": "crashes", "candidate_id": result["candidate_id"],
                "period": result["period"]}])
        with patch.object(ops, "melbourne_today", return_value=date(2028, 10, 5)):
            with self.assertRaisesRegex(ops.OperationError, "latest September release"):
                ops._publication_gate(result, {"crashes": None}, history=[])
        with patch.object(ops, "melbourne_today", return_value=date(2028, 1, 1)):
            # A missed September release remains eligible after New Year, until
            # the next September replaces it as the latest normal release.
            ops._publication_gate(result, {"crashes": None}, history=[])

    def test_count_drop_is_blocked_before_loader(self):
        """Apply current-data drop limits to updates while allowing explicit recovery."""
        result = self.speed()
        state = {"speed": {"road_rows": 100, "unresolved_rows": 0}}
        with self.assertRaisesRegex(ops.OperationError, "Road count dropped"):
            ops._publication_gate(result, state, history=[])
        ops._publication_gate(result, state, restore=True)

    def test_durable_history_still_freezes_annual_snapshot_after_baseline_restore(self):
        """A rollback must not erase proof that an annual snapshot was published."""
        result = self.crashes()
        state = {"crashes": {"end": "2025-12-31", "build_id": "baseline", "event_rows": 60}}
        history = [{"status": "complete", "dataset": "crashes", "restore": False,
                    "candidate_id": "a" * 64, "period": {"end": "2026-12-31"}}]
        with patch.object(ops, "melbourne_today", return_value=date(2027, 10, 1)):
            with self.assertRaisesRegex(ops.OperationError, "already published"):
                ops._publication_gate(result, state, history=history)
            with self.assertRaisesRegex(ops.OperationError, "durable publication history"):
                ops._publication_gate(result, state)
            history[0]["candidate_id"] = result["candidate_id"]
            ops._publication_gate(result, state, history=history)

    def test_unresolved_publication_blocks_restore_before_database(self):
        """Stop recovery before connecting when an earlier commit is uncertain."""
        result = self.crashes()
        history = self.root / "history.json"
        ops._write(history, {"schema_version": 1, "publications": [], "unresolved_intents": ["123-1"]})
        with patch.dict("os.environ", {"DATABASE_URL": "test-only"}), \
             patch.object(ops, "_locked_database") as database, \
             self.assertRaisesRegex(ops.OperationError, "Reconcile uncertain"):
            ops.publish(self.bundle, result["candidate_id"], "operator", "restore", restore=True,
                        report=self.root / "restore.json", publication_history=history)
        database.assert_not_called()

    def test_publish_exact_speed_candidate_never_downloads_or_touches_other_domains(self):
        """Publication dispatches only the selected loader using approved bytes."""
        result = self.speed()
        @contextmanager
        def database(url):
            """Stand in for the lock connection without opening PostgreSQL."""
            yield object()
        with patch.dict("os.environ", {"DATABASE_URL": "test-only"}), \
             patch.object(ops, "_locked_database", database), \
             patch.object(ops, "_database_state", return_value={"speed": {"road_rows": 1, "unresolved_rows": 0}}), \
             patch.object(ops, "load", return_value={"road_rows": 1}) as load, \
             patch.object(ops, "download") as download, patch.object(ops, "load_crashes") as crashes, \
             patch.object(ops, "load_calendar") as calendar:
            record = ops.publish(self.bundle, result["candidate_id"], "operator", "Monthly source review",
                                 report=self.root / "publication.json")
        self.assertEqual(record["status"], "complete")
        self.assertEqual(record["candidate_id"], result["candidate_id"])
        load.assert_called_once()
        download.assert_not_called()
        crashes.assert_not_called()
        calendar.assert_not_called()

    def test_tampered_candidate_fails_before_database_and_report_excludes_credentials(self):
        """Reject changed payloads before connecting and sanitize arbitrary errors."""
        result = self.speed()
        (self.bundle / "payload/speed_rule.csv").write_text("changed")
        with patch.object(ops, "_locked_database") as connection, self.assertRaises(ops.OperationError):
            ops.publish(self.bundle, result["candidate_id"], "operator", "review", report=self.root / "failed.json")
        connection.assert_not_called()
        self.assertEqual(ops._json(self.root / "failed.json")["error_code"], "FILE_CHANGED")
        self.assertNotIn("secret", json.dumps(ops._error(ValueError("postgres://secret@private"))))

    def test_original_change_while_waiting_for_lock_cannot_change_loaded_bytes(self):
        """The private verified snapshot survives edits to the operator's copy."""
        result = self.speed()
        original = (self.bundle / "payload/road_speed.csv").read_bytes()
        @contextmanager
        def database(url):
            """Simulate a concurrent edit after the publication snapshot exists."""
            (self.bundle / "payload/road_speed.csv").write_text("concurrent edit")
            yield object()
        def loader(url, directory):
            """Assert the loader receives the original bytes in a private path."""
            self.assertNotEqual(directory, self.bundle / "payload")
            self.assertEqual((directory / "road_speed.csv").read_bytes(), original)
            return {"road_rows": 1}
        with patch.dict("os.environ", {"DATABASE_URL": "test-only"}), \
             patch.object(ops, "_locked_database", database), \
             patch.object(ops, "_database_state", return_value={"speed": {"road_rows": 1, "unresolved_rows": 0}}), \
             patch.object(ops, "load", side_effect=loader):
            self.assertEqual(ops.publish(self.bundle, result["candidate_id"], "operator", "review",
                             report=self.root / "publication.json")["status"], "complete")

    def test_pending_calendar_keeps_actionable_failure_after_temporary_cleanup(self):
        """Retain validation reasons after failed temporary candidate cleanup."""
        with patch.object(ops, "download_calendar", side_effect=calendar_download), self.assertRaises(ValueError):
            ops.prepare("calendar", COMMIT, self.bundle, year=2027)
        report = ops._json(self.root / "candidate-failure.json")
        self.assertEqual(report["stage"], "calendar_build")
        self.assertTrue(report["calendar_validation"]["errors"] or report["calendar_validation"]["pending"])
        self.assertFalse(self.bundle.exists())
        self.assertFalse(list(self.root.glob(".candidate-*")))

    def test_original_snapshot_does_not_archive_extra_local_files(self):
        """Copy only named calendar source files, excluding unrelated local data."""
        source = Path(calendar_download(2026, self.root / "calendar")["source_dir"])
        (source / "private.txt").write_text("must not archive")
        with patch.object(ops, "download_calendar") as download:
            result = ops.prepare("calendar", COMMIT, self.bundle, year=2026, calendar_source_dir=source)
        download.assert_not_called()
        self.assertFalse(any("private" in name for name in result["files"]))

    def test_status_without_credentials_is_explicit_and_writes_report(self):
        """Missing monitoring credentials must not produce a healthy status."""
        with patch.dict("os.environ", {}, clear=True):
            report = ops.status(self.root / "health.json")
        self.assertEqual(report["status"], "not_configured")
        self.assertTrue(report["warnings"])

    def test_missed_september_update_remains_overdue_after_new_year(self):
        """Carry overdue evidence across January until the active period advances."""
        state = {"calendar": [], "database_bytes": 0,
                 "crashes": {"end": "2025-12-31"}}
        with patch.dict("os.environ", {"DATABASE_URL": "test-only"}), \
             patch("psycopg.connect"), patch.object(ops, "_database_state", return_value=state), \
             patch.object(ops, "melbourne_today", return_value=date(2028, 1, 1)):
            report = ops.status(self.root / "overdue.json")
        self.assertEqual(report["next_crash_update"], "2027-09")
        self.assertTrue(any("September 2027 has not been published" in item for item in report["warnings"]))
        state["crashes"]["end"] = "2026-12-31"
        with patch.dict("os.environ", {"DATABASE_URL": "test-only"}), \
             patch("psycopg.connect"), patch.object(ops, "_database_state", return_value=state), \
             patch.object(ops, "melbourne_today", return_value=date(2028, 1, 1)):
            report = ops.status(self.root / "current.json")
        self.assertEqual(report["next_crash_update"], "2028-09")
        self.assertFalse(any("Annual crash update" in item for item in report["warnings"]))


TEST_DATABASE_URL = os.environ.get("ITERATION2_TEST_DATABASE_URL")


@unittest.skipUnless(TEST_DATABASE_URL, "Disposable PostGIS required")
class OperationsPostgisTests(unittest.TestCase):
    """Exercise real per-domain writes in an explicitly disposable PostGIS DB."""

    def setUp(self):
        """Check the actual DB name before replacing its schema and seeding history."""
        import psycopg
        from iteration3_v1_pipeline.load import apply_migration
        self.connection = psycopg.connect(TEST_DATABASE_URL, autocommit=True)
        self.addCleanup(self.connection.close)
        # A caller-provided URL is not enough: inspect the connected database
        # before any destructive test setup, including schema teardown cleanup.
        if not self.connection.execute("SELECT current_database()").fetchone()[0].startswith("iteration2_test"):
            raise ValueError("Operations tests require a disposable iteration2_test database")
        self.connection.execute("DROP SCHEMA IF EXISTS iteration2_v2 CASCADE")
        self.addCleanup(lambda: self.connection.execute("DROP SCHEMA IF EXISTS iteration2_v2 CASCADE"))
        apply_migration(TEST_DATABASE_URL)
        # Application history is deliberately seeded before reference-data loads
        # so domain independence is checked against persisted non-pipeline state.
        self.connection.execute("INSERT INTO iteration2_v2.report_email_dispatch VALUES ('key','digest','ACCEPTED',NULL,123)")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_independent_publication_and_read_only_status_use_existing_schema(self):
        """Preserve email state while publishing into the existing nine-table schema."""
        with patch.object(ops, "download", side_effect=speed_download):
            speed = ops.prepare("speed", COMMIT, self.root / "speed", as_of="2026-10-06")
        with patch.object(ops, "download_calendar", side_effect=calendar_download):
            calendar = ops.prepare("calendar", COMMIT, self.root / "calendar", year=2026)
        with patch.dict("os.environ", {"DATABASE_URL": TEST_DATABASE_URL}):
            for kind, manifest in (("speed", speed), ("calendar", calendar)):
                result = ops.publish(self.root / kind, manifest["candidate_id"], "test-reviewer", "Synthetic reviewed publication",
                                     report=self.root / f"{kind}-published.json")
                self.assertEqual(result["status"], "complete")
            health = ops.status(self.root / "status.json")
        self.assertIn(health["status"], ("ok", "attention"))
        self.assertEqual(len(health["database"]["table_bytes"]), 9)
        self.assertEqual(self.connection.execute("SELECT status FROM iteration2_v2.report_email_dispatch").fetchone(), ("ACCEPTED",))
        self.assertEqual(self.connection.execute("SELECT count(*) FROM iteration2_v2.road_speed").fetchone(), (1,))
        self.assertEqual(self.connection.execute("SELECT count(*) FROM iteration2_v2.calendar_coverage").fetchone(), (1,))

    def test_annual_publication_replay_does_not_rewrite_active_build(self):
        """An exact annual replay preserves build metadata and application history."""
        with patch.object(ops, "download_crashes", side_effect=crash_download), \
             patch("iteration3_v1_pipeline.operations_policy.melbourne_today", return_value=date(2027, 9, 5)):
            manifest = ops.prepare("crashes", COMMIT, self.root / "crashes", release_year=2027)
        ops._write(self.root / "history.json", {"schema_version": 1, "publications": []})
        with patch.dict("os.environ", {"DATABASE_URL": TEST_DATABASE_URL}), \
             patch.object(ops, "melbourne_today", return_value=date(2027, 9, 5)):
            first = ops.publish(self.root / "crashes", manifest["candidate_id"], "test-reviewer", "Annual update",
                                report=self.root / "first.json", publication_history=self.root / "history.json")
            original = self.connection.execute("SELECT * FROM iteration2_v2.crash_build").fetchall()
            # Simulate the workflow's durable successful history between runs;
            # local publish itself does not create external publication evidence.
            ops._write(self.root / "history.json", {"schema_version": 1, "publications": [first]})
            second = ops.publish(self.root / "crashes", manifest["candidate_id"], "test-reviewer", "Retry same reviewed candidate",
                                 report=self.root / "second.json", publication_history=self.root / "history.json")
        self.assertEqual(second["outcome"]["status"], "unchanged")
        self.assertEqual(self.connection.execute("SELECT * FROM iteration2_v2.crash_build").fetchall(), original)
        self.assertEqual(self.connection.execute("SELECT status FROM iteration2_v2.report_email_dispatch").fetchone(), ("ACCEPTED",))


if __name__ == "__main__":
    unittest.main()
