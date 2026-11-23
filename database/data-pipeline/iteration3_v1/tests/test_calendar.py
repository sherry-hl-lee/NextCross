"""Check DataVic CSV-only parsing and immutable calendar handoffs.

Fixtures are small synthetic CSV files, not scraped pages. Tests exercise
source format and calendar consistency without fetching, accessing a database or
comparing to the legacy generated reference CSV.
"""

import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from iteration3_v1_pipeline.calendar import (  # noqa: E402
    CALENDAR_FIELDS, COVERAGE_SCOPE, CSV_FIELDS, KINDS, LIMITATIONS, build_calendar, calendar_csv_bytes, validate_bundle,
)
from iteration3_v1_pipeline.calendar_download import SOURCE_FILES, download_calendar, source_urls  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures/calendar"
CSV_URL = "https://discover.data.vic.gov.au/dataset/a8bc540d-181b-4fb5-8110-5fe52a04f6a7/resource/caaa47de-8626-46a6-aa28-3d948c15c5d9/download/important-dates-august-2026.csv"


def write_snapshot(directory, *, year=2026):
    """Write a CSV-only source envelope for unit and disposable-DB tests."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    filename = SOURCE_FILES["important_dates"]
    fixture = filename if year == 2026 else "important_dates_2027_pending.csv"
    data = (FIXTURES / fixture).read_bytes()
    (directory / filename).write_bytes(data)
    sources = {"important_dates": {"file": filename, "url": CSV_URL,
               "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "acquisition": "http",
               "verified_at": "2026-09-16T00:00:00+00:00", "last_modified": "2026-08-18",
               "metadata_url": source_urls(year)["important_dates"],
               "resource_id": "caaa47de-8626-46a6-aa28-3d948c15c5d9",
               "package_id": "a8bc540d-181b-4fb5-8110-5fe52a04f6a7"}}
    manifest = {"schema_version": 3, "year": year, "snapshot_id": "synthetic-fixture",
                "fetched_at": "2026-09-16T00:00:00+00:00",
                "verified_at": sources["important_dates"]["verified_at"], "sources": sources}
    (directory / "sources.json").write_text(json.dumps(manifest), encoding="utf-8")
    return directory


def update_source(directory, name, text):
    """Model new CSV source bytes; shared by disposable-DB tests."""
    path = Path(directory) / SOURCE_FILES[name]
    data = text.encode("utf-8")
    path.write_bytes(data)
    manifest_path = Path(directory) / "sources.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["sources"][name].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")


class CalendarTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = write_snapshot(self.root / "raw")
        self.output = self.root / "prepared"

    def build(self, **kwargs):
        return build_calendar(self.source, 2026, self.output, **kwargs)

    def change_csv(self, change):
        rows = list(csv.DictReader(io.StringIO((self.source / "important_dates.csv").read_text())))
        change(rows)
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
        update_source(self.source, "important_dates", stream.getvalue())

    def assert_failed(self, *, year=2026, expected_status="ERROR"):
        with self.assertRaises(ValueError):
            build_calendar(self.source, year, self.output)
        failures = list(self.output.glob("failed-*/calendar_report.json"))
        self.assertTrue(failures)
        report = json.loads(failures[-1].read_text())
        self.assertEqual(report["status"], expected_status)
        self.assertEqual(sorted(path.name for path in failures[-1].parent.iterdir()), ["calendar_report.json"])
        return report

    def test_csv_alone_builds_2026_dates_with_explicit_regional_limitations(self):
        report = self.build()
        manifest, rows = validate_bundle(report["input_dir"])
        self.assertEqual(manifest["schema_version"], 3)
        self.assertEqual(manifest["record_counts"], {"SCHOOL_TERM": 4, "PUBLIC_HOLIDAY": 13, "REGIONAL_HOLIDAY_UNCERTAIN": 1})
        for start, end in [("2026-01-27", "2026-04-02"), ("2026-10-05", "2026-12-18")]:
            self.assertIn({"kind": "SCHOOL_TERM", "start_date": start, "end_date": end}, rows)
        for value in ("2026-03-09", "2026-04-03", "2026-04-06", "2026-06-08", "2026-12-26", "2026-12-28"):
            self.assertIn({"kind": "PUBLIC_HOLIDAY", "start_date": value, "end_date": value}, rows)
        self.assertNotIn({"kind": "PUBLIC_HOLIDAY", "start_date": "2026-09-03", "end_date": "2026-09-03"}, rows)
        regional = {item["date"]: item["declarations"] for item in manifest["evidence"]["regional_dates"]}
        self.assertEqual(set(regional), {"2026-11-03"})
        self.assertEqual(set(manifest["sources"]), {"important_dates"})
        self.assertEqual({path.name for path in self.source.iterdir()}, {"sources.json", "important_dates.csv"})
        for artifact in (manifest, report):
            self.assertEqual(artifact["coverage_scope"], COVERAGE_SCOPE)
            self.assertIs(artifact["regional_holidays_complete"], False)
            self.assertEqual(artifact["limitations"], list(LIMITATIONS))
        self.assertEqual(regional["2026-11-03"][0]["source"], "important_dates")
        self.assertEqual(len(manifest["evidence"]["public_holiday_notes"]), 3)
        self.assertEqual(len(manifest["evidence"]["important_dates_records"]), 22)
        self.assertIn("students start 28 January", manifest["evidence"]["important_dates_records"][0]["name"])

    def test_same_sources_are_immutable_even_with_later_retrieval_time(self):
        first = self.build()
        path = Path(first["input_dir"])
        original = {entry.name: entry.read_bytes() for entry in path.iterdir()}
        source_manifest = self.source / "sources.json"
        metadata = json.loads(source_manifest.read_text())
        metadata["fetched_at"] = "2026-09-17T00:00:00+00:00"
        metadata["sources"]["important_dates"]["verified_at"] = metadata["fetched_at"]
        source_manifest.write_text(json.dumps(metadata))
        second = self.build(previous=path)
        self.assertTrue(second["reused"])
        self.assertEqual(first["build_id"], second["build_id"])
        self.assertEqual(original, {entry.name: entry.read_bytes() for entry in path.iterdir()})
        self.assertEqual(second["diff"]["added"], [])
        self.assertEqual(second["diff"]["removed"], [])
        self.assertIs(second["regional_holidays_complete"], False)
        self.assertEqual(second["coverage_scope"], COVERAGE_SCOPE)

    def test_diff_compares_only_explicit_previous_bundle(self):
        first = self.build()
        self.change_csv(lambda rows: next(row for row in rows if row["name"] == "Term 4 2026 - End date").update(important_date="17/12/2026"))
        second = self.build(previous=first["input_dir"])
        self.assertEqual(second["diff"]["added"], [{"kind": "SCHOOL_TERM", "start_date": "2026-10-05", "end_date": "2026-12-17"}])
        self.assertEqual(second["diff"]["removed"], [{"kind": "SCHOOL_TERM", "start_date": "2026-10-05", "end_date": "2026-12-18"}])
        self.assertNotEqual(first["build_id"], second["build_id"])
        validate_bundle(first["input_dir"])

    def test_pending_2027_produces_report_without_ready_manifest(self):
        self.source = write_snapshot(self.root / "future", year=2027)
        report = self.assert_failed(year=2027, expected_status="PENDING")
        self.assertIn("afl", report["pending"][0])
        self.assertFalse(list(self.output.glob("*/calendar_manifest.json")))

    def test_source_bytes_are_checked_before_reusing_build(self):
        first = self.build()
        original = (Path(first["input_dir"]) / "calendar.csv").read_bytes()
        with (self.source / "important_dates.csv").open("a") as stream:
            stream.write("unexpected local edit")
        self.assert_failed()
        self.assertEqual((Path(first["input_dir"]) / "calendar.csv").read_bytes(), original)

    def test_csv_header_and_dates_fail_closed(self):
        original = (self.source / "important_dates.csv").read_text()
        for number, (before, after) in enumerate([
                ("important_date", "unrecognised_date"), ("27/01/2026", "2026-01-27"),
                ("27/01/2026", "01/27/2026"), ("2/04/2026", "32/04/2026"),
                ("Term 4 2026 - End date", "Term 4 2027 - End date"),
                ("Term 4 2026 - End date", "Term 3 2026 - End date"),
                ("9/03/2026", "3/09/2026")]):
            with self.subTest(after=after):
                self.source = write_snapshot(self.root / f"bad-{number}")
                self.output = self.root / f"result-{number}"
                update_source(self.source, "important_dates", original.replace(before, after))
                self.assert_failed()

    def test_missing_requested_year_or_boundary_is_pending(self):
        self.change_csv(lambda rows: rows.pop(0))
        self.assert_failed(expected_status="PENDING")
        self.source = write_snapshot(self.root / "missing-year")
        self.output = self.root / "missing-year-result"
        text = (self.source / "important_dates.csv").read_text().replace("2026", "2025")
        update_source(self.source, "important_dates", text)
        self.assert_failed(expected_status="PENDING")

    def test_duplicate_relevant_records_are_errors(self):
        self.change_csv(lambda rows: rows.append(dict(next(row for row in rows if row["name"] == "Labour Day"))))
        self.assert_failed()

    def test_easter_monday_must_follow_easter_sunday(self):
        self.change_csv(lambda rows: next(row for row in rows if row["name"] == "Easter Monday").update(important_date="5/04/2026"))
        report = self.assert_failed()
        self.assertIn("consecutive", report["errors"][0])

    def test_incomplete_holidays_are_pending_unknown_names_are_errors(self):
        self.change_csv(lambda rows: rows.remove(next(row for row in rows if row["name"] == "Labour Day")))
        self.assert_failed(expected_status="PENDING")
        self.source = write_snapshot(self.root / "unknown")
        self.output = self.root / "unknown-result"
        self.change_csv(lambda rows: next(row for row in rows if row["name"] == "Labour Day").update(name="Unreviewed special holiday"))
        self.assert_failed()

    def test_pending_source_description_stops_publication(self):
        self.change_csv(lambda rows: next(row for row in rows if row["name"] == "Friday before the AFL grand final").update(description="Subject to AFL schedule"))
        self.assert_failed(expected_status="PENDING")

    def test_only_csv_source_verification_time_is_used(self):
        manifest, _ = validate_bundle(self.build()["input_dir"])
        self.assertEqual(manifest["verified_at"], "2026-09-16T00:00:00+00:00")

    def test_imported_csv_z_timestamp_builds(self):
        acquired = download_calendar(2026, self.root / "imported", import_dir=self.source,
                                     verified_at="2026-09-15T00:00:00Z")
        snapshot = Path(acquired["source_dir"])
        self.assertEqual((snapshot / "important_dates.csv").read_bytes(), (self.source / "important_dates.csv").read_bytes())
        self.assertEqual({path.name for path in snapshot.iterdir()}, {"sources.json", "important_dates.csv"})
        report = build_calendar(snapshot, 2026, self.output)
        manifest, _ = validate_bundle(report["input_dir"])
        self.assertEqual(manifest["verified_at"], "2026-09-15T00:00:00+00:00")

    def test_invalid_provenance_and_old_supplement_snapshot_are_rejected(self):
        for number, change in enumerate([
                lambda item: item["sources"]["important_dates"].update(url="https://example.com/calendar.csv"),
                lambda item: item["sources"]["important_dates"].update(resource_id="00000000-0000-0000-0000-000000000000"),
                lambda item: item["sources"]["important_dates"].update(package_id="another-package"),
                lambda item: item["sources"]["important_dates"].pop("resource_id"),
                lambda item: item["sources"]["important_dates"].update(verified_at="9998-01-01T00:00:00Z"),
                lambda item: item.update(fetched_at="9998-01-01T00:00:00Z"),
                lambda item: item["sources"].update(regional_holidays={"file": "regional_holidays.json"}),
                lambda item: item.update(schema_version=1),
                lambda item: item.update(schema_version=2),
                lambda item: item["sources"]["important_dates"].update(acquisition="local_html")]):
            with self.subTest(number=number):
                self.source = write_snapshot(self.root / f"source-{number}")
                self.output = self.root / f"source-result-{number}"
                path = self.source / "sources.json"
                item = json.loads(path.read_text())
                change(item)
                path.write_text(json.dumps(item))
                self.assert_failed()

    def mutate_bundle(self, mutator):
        report = self.build()
        directory = Path(report["input_dir"])
        manifest, rows = validate_bundle(directory)
        mutator(manifest, rows)
        data = calendar_csv_bytes(rows)
        (directory / "calendar.csv").write_bytes(data)
        manifest["file"].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), rows=len(rows))
        manifest["record_counts"] = {kind: sum(row["kind"] == kind for row in rows) for kind in KINDS}
        (directory / "calendar_manifest.json").write_text(json.dumps(manifest))
        return directory

    def test_semantic_validation_rejects_bad_rows_even_with_matching_hash(self):
        mutations = [
            lambda rows: rows.append(dict(rows[0])),
            lambda rows: rows[0].update(kind="UNKNOWN"),
            lambda rows: rows[0].update(start_date="2027-01-01"),
            lambda rows: rows[0].update(start_date="2026-12-31", end_date="2026-01-01"),
            lambda rows: rows[0].update(start_date="2026-1-01"),
            lambda rows: next(row for row in rows if row["kind"] == "SCHOOL_TERM" and row["start_date"] == "2026-04-20").update(start_date="2026-04-01"),
            lambda rows: rows.append({"kind": "PUBLIC_HOLIDAY", "start_date": "2026-11-03", "end_date": "2026-11-03"}),
        ]
        for number, change in enumerate(mutations):
            with self.subTest(number=number):
                self.output = self.root / f"mutation-{number}"
                path = self.mutate_bundle(lambda manifest, rows: change(rows))
                with self.assertRaises(ValueError):
                    validate_bundle(path)

    def test_manifest_status_counts_hash_and_identity_are_checked(self):
        changes = [("status", "PENDING"), ("year", True), ("build_id", "0" * 64),
                   ("policy_version", "unknown"), ("coverage_end", "2026-12-28"),
                   ("verified_at", "2026-09-16T10:00:00+10:00"),
                   ("verified_at", "9998-01-01T00:00:00Z"),
                   ("verified_at", "2026-09-15T00:00:00+00:00"),
                   ("coverage_scope", "complete_victorian_calendar"),
                   ("regional_holidays_complete", True),
                   ("regional_holidays_complete", None),
                   ("limitations", []), ("schema_version", 2)]
        for number, (key, value) in enumerate(changes):
            with self.subTest(key=key):
                self.output = self.root / f"manifest-{number}"
                path = self.mutate_bundle(lambda manifest, rows: manifest.update({key: value}))
                with self.assertRaises(ValueError):
                    validate_bundle(path)
        self.output = self.root / "tampered-csv"
        path = Path(self.build()["input_dir"])
        with (path / "calendar.csv").open("a") as stream:
            stream.write("\n")
        with self.assertRaisesRegex(ValueError, "hash/size"):
            validate_bundle(path)

    def test_bad_evidence_and_missing_school_term_are_rejected(self):
        path = self.mutate_bundle(lambda manifest, rows: manifest["evidence"]["regional_dates"].pop())
        with self.assertRaisesRegex(ValueError, "evidence"):
            validate_bundle(path)
        self.output = self.root / "missing-term"
        path = self.mutate_bundle(lambda manifest, rows: rows.remove(next(row for row in rows if row["kind"] == "SCHOOL_TERM")))
        with self.assertRaisesRegex(ValueError, "four school terms"):
            validate_bundle(path)


if __name__ == "__main__":
    unittest.main()
