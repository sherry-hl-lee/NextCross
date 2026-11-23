"""Validate CI artifact handoffs and failure reporting without a live database."""

from contextlib import ExitStack, redirect_stderr
from copy import deepcopy
import csv
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / "src"), str(ROOT / "tests")]

from deploy import github_actions as deployment
from test_build import normal_road
from test_calendar import write_snapshot
from test_crash import crash, write_locations, write_source

COMMIT = "a" * 40


def calendar_download(year, output):
    return {"source_dir": str(write_snapshot(Path(output) / "snapshot", year=year))}


def speed_download(output):
    """Write tiny source files while accepting a caller-created raw directory.

    Both the historical helper and the independent candidate preparer use this
    fixture; directory handling mirrors the real downloader without HTTP calls.
    """
    output.mkdir(parents=True, exist_ok=True)
    for name, features in (("speed-zones", [normal_road(1, 0, 100)]), ("speed-signs", [])):
        (output / f"{name}.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": features}))
    return {"source": "synthetic test fixtures"}


def crash_download(output):
    output.mkdir(parents=True, exist_ok=True)
    write_source(output / "crashes.csv", [crash(str(i), x=2500250 + 20*i) for i in range(3)])
    write_locations(output / "accident-location.csv", [])
    return {"source": "synthetic test fixtures"}


class DeploymentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prepared = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.prepared.cleanup)
        cls.baseline = Path(cls.prepared.name) / "bundle"
        cls.order = []
        def tracked(name, function):
            def invoke(*args, **kwargs):
                cls.order.append(name)
                return function(*args, **kwargs)
            return invoke
        with patch.object(deployment, "download_calendar", side_effect=tracked("calendar_download", calendar_download)), \
             patch.object(deployment, "build_calendar", side_effect=tracked("calendar_build", deployment.build_calendar)), \
             patch.object(deployment, "download", side_effect=tracked("speed_download", speed_download)), \
             patch.object(deployment, "download_crashes", side_effect=tracked("crash_download", crash_download)):
            cls.result = deployment.prepare(2026, COMMIT, cls.baseline, as_of="2026-09-17")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.bundle = self.root / "bundle"
        shutil.copytree(self.baseline, self.bundle)
        self.report = self.root / "deployment-result.json"

    def manifest(self):
        return json.loads((self.bundle / "deployment_manifest.json").read_text())

    def save_manifest(self, value):
        (self.bundle / "deployment_manifest.json").write_text(json.dumps(value))

    def rehash(self, name):
        manifest = self.manifest()
        manifest["files"][name] = deployment._file_info(self.bundle / name)
        self.save_manifest(manifest)

    def prepare_large_road_bundle(self):
        def large_speed_download(output):
            metadata = speed_download(output)
            road = normal_road(1, 0, 100)
            # Real road EWKT can exceed the CSV parser's default 128 KiB limit.
            # Generate the long geometry here instead of storing a large fixture.
            road["geometry"]["coordinates"] = [
                [145.13 + index * 0.000001, -37.9 + (index % 2) * 0.000001]
                for index in range(8000)
            ]
            (output / "speed-zones.geojson").write_text(json.dumps({
                "type": "FeatureCollection", "features": [road],
            }))
            return metadata

        bundle = self.root / "large-road-bundle"
        with patch.object(deployment, "download_calendar", side_effect=calendar_download), \
             patch.object(deployment, "download", side_effect=large_speed_download), \
             patch.object(deployment, "download_crashes", side_effect=crash_download):
            deployment.prepare(2026, COMMIT, bundle, as_of="2026-09-17")
        return bundle

    def test_large_road_geometry_survives_prepare_and_validate_without_changing_parser_limit(self):
        previous_limit = csv.field_size_limit(131072)
        self.addCleanup(csv.field_size_limit, previous_limit)
        bundle = self.prepare_large_road_bundle()
        self.assertEqual(csv.field_size_limit(), 131072)
        manifest = deployment.validate_deployment(bundle, COMMIT)
        self.assertEqual(csv.field_size_limit(), 131072)
        self.assertEqual(manifest["counts"]["speed"], {"road_rows": 1, "rule_rows": 0})

        # Inspect the fixture only after exercising both production entry points.
        # This temporary test-only limit cannot mask their original failure.
        csv.field_size_limit(1024 * 1024)
        try:
            with (bundle / "speed/road_speed.csv").open(newline="", encoding="utf-8") as stream:
                geometry = next(csv.DictReader(stream))["road_geom"]
        finally:
            csv.field_size_limit(131072)
        self.assertTrue(geometry.startswith("SRID=4326;MULTILINESTRING"))
        self.assertGreater(len(geometry), 131072)

    def test_malformed_row_after_large_geometry_is_rejected_and_parser_limit_restored(self):
        previous_limit = csv.field_size_limit(131072)
        self.addCleanup(csv.field_size_limit, previous_limit)
        self.bundle = self.prepare_large_road_bundle()
        self.assertEqual(csv.field_size_limit(), 131072)
        with (self.bundle / "speed/road_speed.csv").open("a", encoding="utf-8") as stream:
            stream.write("malformed,row\n")
        self.rehash("speed/road_speed.csv")
        with self.assertRaisesRegex(ValueError, "Malformed prepared speed row"):
            deployment.validate_deployment(self.bundle, COMMIT)
        self.assertEqual(csv.field_size_limit(), 131072)

    def test_prepare_builds_real_csv_only_payloads_and_fails_calendar_first(self):
        self.assertEqual(self.order, ["calendar_download", "calendar_build", "speed_download", "crash_download"])
        manifest = deployment.validate_deployment(self.bundle, COMMIT)
        self.assertEqual(manifest["counts"]["speed"], {"road_rows": 1, "rule_rows": 0})
        self.assertEqual(manifest["counts"]["crashes"], {"event_rows": 3, "hotspot_rows": 1})
        self.assertEqual(manifest["counts"]["calendar"]["rows"], 18)
        self.assertIs(manifest["regional_holidays_complete"], False)
        self.assertEqual({p.relative_to(self.bundle).as_posix() for p in self.bundle.rglob("*") if p.is_file()},
                         set(deployment.ARTIFACT_FILES) | {"deployment_manifest.json"})
        failed = self.root / "pending"
        with patch.object(deployment, "download_calendar", side_effect=calendar_download), \
             patch.object(deployment, "download") as speed, patch.object(deployment, "download_crashes") as crashes:
            with self.assertRaises(deployment.PreparationError) as failure:
                deployment.prepare(2027, COMMIT, failed)
            self.assertEqual(failure.exception.stage, "calendar_build")
            self.assertEqual(failure.exception.error_type, "ValueError")
            speed.assert_not_called()
            crashes.assert_not_called()
        self.assertFalse(failed.exists())
        self.assertFalse(list(self.root.glob(".deployment-*")))

    def test_hash_and_commit_mismatch_stop_before_any_database_connection(self):
        for altered in (False, True):
            with self.subTest(altered=altered):
                if altered:
                    with (self.bundle / "speed/road_speed.csv").open("a") as stream:
                        stream.write("altered\n")
                with patch("psycopg.connect") as connect, patch.object(deployment, "apply_migration") as migrate:
                    with self.assertRaises(RuntimeError):
                        deployment.deploy(self.bundle, COMMIT if altered else "b" * 40, self.report)
                    connect.assert_not_called()
                    migrate.assert_not_called()
                result = json.loads(self.report.read_text())
                self.assertEqual(result["failed_stage"], "validate")
                self.assertEqual(result["stages"], {"validate": {"status": "failed", "error_type": "ValueError"}})

    def test_rehashed_count_and_scope_disagreements_are_rejected(self):
        cases = (("speed/report.json", lambda doc: doc["counts"].update(road_rows=99)),
                 ("calendar/calendar_manifest.json", lambda doc: doc.update(regional_holidays_complete=True)))
        for name, change in cases:
            with self.subTest(name=name):
                original = (self.bundle / name).read_bytes()
                document = json.loads(original)
                change(document)
                (self.bundle / name).write_text(json.dumps(document))
                self.rehash(name)
                with patch("psycopg.connect") as connect, self.assertRaises(ValueError):
                    deployment.validate_deployment(self.bundle, COMMIT)
                connect.assert_not_called()
                (self.bundle / name).write_bytes(original)
                self.rehash(name)

    def test_extra_manual_inputs_unsafe_paths_and_symlinks_are_rejected(self):
        extra = self.bundle / "calendar/regional_holidays.json"
        extra.write_text("{}")
        with self.assertRaises(ValueError):
            deployment.validate_deployment(self.bundle, COMMIT)
        extra.unlink()
        original = self.manifest()
        altered = deepcopy(original)
        altered["files"]["../outside.csv"] = altered["files"].pop("speed/road_speed.csv")
        self.save_manifest(altered)
        with self.assertRaises(ValueError):
            deployment.validate_deployment(self.bundle, COMMIT)
        self.save_manifest(original)
        road = self.bundle / "speed/road_speed.csv"
        outside = self.root / "road.csv"
        road.rename(outside)
        road.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            deployment.validate_deployment(self.bundle, COMMIT)

    def test_deploy_uses_dry_run_version_and_records_partial_commits_without_secret(self):
        for fail in (False, True):
            with self.subTest(fail=fail), ExitStack() as stack:
                stack.enter_context(patch.dict("os.environ", {"DATABASE_URL": "postgresql://user:secret@private/db"}))
                order = []
                def migration(url):
                    order.append("migration")
                def calendar(url, directory, **kwargs):
                    order.append("calendar_dry_run" if kwargs.get("dry_run") else "calendar_load")
                    if kwargs.get("dry_run"):
                        return {"status": "dry_run", "expected_current_build_id": "c" * 64, "counts": {"target": 18}}
                    self.assertEqual(kwargs, {"expected_current_build_id": "c" * 64})
                    return {"status": "loaded", "counts": {"target": 18}}
                def speed(url, directory):
                    order.append("speed_load")
                    return {"road_rows": 1, "rule_rows": 0}
                def crashes(url, directory):
                    checkpoint = json.loads(self.report.read_text())
                    self.assertEqual(checkpoint["status"], "running")
                    self.assertEqual(checkpoint["stages"]["speed_load"]["status"], "complete")
                    self.assertEqual(checkpoint["stages"]["calendar_load"]["status"], "loaded")
                    self.assertNotIn("crash_load", checkpoint["stages"])
                    order.append("crash_load")
                    if fail:
                        raise ValueError("postgresql://user:secret@private/db failed")
                    return {"event_rows": 3, "hotspot_rows": 1}
                for name, function in (("apply_migration", migration), ("load_calendar", calendar),
                                       ("load", speed), ("load_crashes", crashes)):
                    stack.enter_context(patch.object(deployment, name, side_effect=function))
                if fail:
                    with self.assertRaisesRegex(RuntimeError, "crash_load"):
                        deployment.deploy(self.bundle, COMMIT, self.report)
                else:
                    self.assertEqual(deployment.deploy(self.bundle, COMMIT, self.report)["status"], "complete")
                self.assertEqual(order, ["migration", "calendar_dry_run", "calendar_load", "speed_load", "crash_load"])
                result = json.loads(self.report.read_text())
                self.assertEqual(result["stages"]["speed_load"]["status"], "complete")
                self.assertEqual(result["stages"]["calendar_load"]["status"], "loaded")
                self.assertEqual(result["status"], "failed" if fail else "complete")
                self.assertNotIn("secret", self.report.read_text())
                self.assertNotIn("private", self.report.read_text())

    def test_cli_names_failed_calendar_stage_without_exposing_exception_text(self):
        output = io.StringIO()
        with patch.object(deployment, "download_calendar", side_effect=calendar_download), \
             patch.object(deployment, "build_calendar", side_effect=ValueError("do-not-print-secret-url")), \
             patch.object(deployment, "download") as speed, redirect_stderr(output):
            code = deployment.main(["prepare", "--year", "2026", "--commit", COMMIT,
                                    "--output", str(self.root / "failed")])
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output.getvalue()),
                         {"status": "failed", "stage": "calendar_build", "error_type": "ValueError"})
        self.assertNotIn("do-not-print-secret", output.getvalue())
        speed.assert_not_called()
        self.assertFalse((self.root / "failed").exists())

    def test_cli_hides_exception_details_and_rejects_report_inside_bundle(self):
        with patch.object(deployment, "prepare", side_effect=ValueError("do-not-print-secret")):
            output = io.StringIO()
            with redirect_stderr(output):
                self.assertEqual(deployment.main(["prepare", "--year", "2026", "--commit", COMMIT]), 1)
            self.assertNotIn("do-not-print-secret", output.getvalue())
        with patch.object(deployment, "apply_migration") as migrate, self.assertRaises(ValueError):
            deployment.deploy(self.bundle, COMMIT, self.bundle / "deployment-result.json")
        migrate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
