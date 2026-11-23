"""Exercise reviewed provenance, uncertain dates and bounded publication guards.

All dates and reviews below are synthetic; no network or database is used.
The fixture deliberately separates an intact downloaded snapshot from a manual
correction. Tests exercise both artifact validation and the loader's explicit
backend-compatibility gate; mocked publication checks the handoff manifest, not
PostgreSQL transaction behaviour or the truth of a reviewer's real evidence.
"""

import copy
import csv
import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from test_calendar import write_snapshot, update_source
from iteration3_v1_pipeline.calendar import CSV_FIELDS, build_calendar, validate_bundle
from iteration3_v1_pipeline.calendar_review import build_reviewed_calendar
from iteration3_v1_pipeline.calendar_storage import load_calendar


def review_fixture(root, *, bounded=True, pending_afl=False):
    """Write a synthetic official snapshot, corrected CSV and reviewer evidence.

    Args:
        root: Existing temporary directory receiving the fixture files.
        bounded: Limit attested coverage to April and defer the unknown AFL date;
            False builds a complete annual correction with all families present.
        pending_afl: In bounded mode, retain an explicitly provisional AFL row
            rather than omitting it from the corrected CSV.

    Returns:
        (source_directory, corrected_csv_path, review_json_path, review_dict).
        Callers can change the declaration and serialize it before a build.

    Side effects:
        Writes only beneath root. Evidence URLs are illustrative identifiers;
        this helper never downloads them or treats fixture dates as real policy.
    """
    source = write_snapshot(root / "original")
    records = list(csv.DictReader(io.StringIO((source / "important_dates.csv").read_text())))
    original_records = records + [next(row for row in records if row["name"] == "New Year's Day").copy()]
    def encode(rows):
        """Serialize fixture rows with the exact official source column order."""
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
        return stream.getvalue()
    # Keep the original snapshot's byte metadata internally consistent even
    # though its duplicate row intentionally fails normal calendar validation.
    update_source(source, "important_dates", encode(original_records))
    if bounded:
        if pending_afl:
            next(row for row in records if "AFL" in row["name"])["description"] = "Subject to AFL schedule"
        else:
            records = [row for row in records if "AFL" not in row["name"]]
    corrected = root / "corrected.csv"
    corrected.write_text(encode(records))
    url = "https://business.vic.gov.au/business-information/public-holidays/victorian-public-holidays-2026"
    review = {
        "schema_version": 1, "reviewer": "Synthetic test reviewer",
        "reviewed_at": "2026-09-17T00:00:00+00:00", "reason": "Remove duplicate New Year row; synthetic fixture review.",
        "original_sha256": hashlib.sha256((source / "important_dates.csv").read_bytes()).hexdigest(),
        "corrected_sha256": hashlib.sha256(corrected.read_bytes()).hexdigest(),
        "coverage_start": "2026-01-01", "coverage_end": "2026-04-30" if bounded else "2026-12-31",
        "coverage_complete": True,
        "evidence": [{"url": url, "checked_at": "2026-09-16T00:00:00Z",
                      "note": "Synthetic attestation: every in-scope date through the stated end was checked."}],
        "unconfirmed_events": [{"family": "afl", "not_before": "2026-09-01",
                                "reason": "Synthetic evidence supports this lower bound, not an exact date.",
                                "evidence_urls": [url]}] if bounded else [],
    }
    review_path = root / "review.json"
    review_path.write_text(json.dumps(review))
    return source, corrected, review_path, review


class ReviewedCalendarTests(unittest.TestCase):
    """Protect review provenance and prevent uncertain dates gaining coverage."""

    def setUp(self):
        """Give each test an isolated bounded fixture, cleaned up automatically."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source, self.corrected, self.review_path, self.review = review_fixture(self.root)

    def build(self):
        """Persist this test's edited declaration and build local output only."""
        self.review_path.write_text(json.dumps(self.review))
        return build_reviewed_calendar(self.source, 2026, self.corrected, self.review_path, self.root / "bundles")

    def test_bounded_review_retains_original_and_does_not_invent_afl(self):
        """A reviewed duplicate fix must preserve originals and disclose limits."""
        original = {name: (self.source / name).read_bytes() for name in ("important_dates.csv", "sources.json")}
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            build_calendar(self.source, 2026, self.root / "official-build")
        report = self.build()
        path = Path(report["input_dir"])
        manifest, rows = validate_bundle(path)
        self.assertEqual(manifest["status"], "READY_BOUNDED")
        self.assertEqual(manifest["coverage_end"], "2026-04-30")
        self.assertFalse(manifest["full_year_complete"])
        self.assertTrue(manifest["requires_bounded_backend"])
        self.assertEqual(manifest["unconfirmed_events"], self.review["unconfirmed_events"])
        self.assertNotIn("2026-09-25", {row["start_date"] for row in rows})
        for name, raw in original.items():
            self.assertEqual((self.source / name).read_bytes(), raw)
            self.assertEqual((path / "original" / name).read_bytes(), raw)
        self.assertEqual((path / "reviewed/important_dates.csv").read_bytes(), self.corrected.read_bytes())
        removed = manifest["corrections"]["removed"]
        self.assertTrue(any(item["record"]["name"] == "New Year's Day" and item["occurrences"] == 1 for item in removed))

    def test_pending_afl_row_is_retained_as_evidence_not_a_confirmed_date(self):
        """Provisional source text survives without creating a holiday row."""
        root = self.root / "pending"
        root.mkdir()
        source, corrected, review_file, _ = review_fixture(root, pending_afl=True)
        result = build_reviewed_calendar(source, 2026, corrected, review_file, root / "bundles")
        manifest, rows = validate_bundle(result["input_dir"])
        self.assertEqual(manifest["status"], "READY_BOUNDED")
        self.assertNotIn("2026-09-25", {row["start_date"] for row in rows})
        self.assertIn("Subject to AFL schedule", (Path(result["input_dir"]) / "reviewed/important_dates.csv").read_text())

    def test_full_year_correction_preserves_ready_contract(self):
        """A complete corrected year keeps READY and requires no bounded opt-in."""
        root = self.root / "full"
        root.mkdir()
        args = review_fixture(root, bounded=False)
        result = build_reviewed_calendar(args[0], 2026, args[1], args[2], root / "bundles")
        manifest, _ = validate_bundle(result["input_dir"])
        self.assertEqual(manifest["status"], "READY")
        self.assertTrue(manifest["full_year_complete"])
        self.assertFalse(manifest["requires_bounded_backend"])

    def test_repeat_build_is_immutable_and_review_change_gets_new_identity(self):
        """Identical bytes reuse a bundle; a new explanation gets a separate ID."""
        first = self.build()
        path = Path(first["input_dir"])
        before = {p.relative_to(path).as_posix(): p.read_bytes() for p in path.rglob("*") if p.is_file()}
        self.assertTrue(self.build()["reused"])
        self.review["reason"] += " Additional reviewer explanation."
        later = self.build()
        self.assertNotEqual(later["build_id"], first["build_id"])
        self.assertEqual(before, {p.relative_to(path).as_posix(): p.read_bytes() for p in path.rglob("*") if p.is_file()})

    def test_valid_other_bundle_cannot_be_reused_under_the_requested_build_directory(self):
        """Validation alone cannot justify reusing a mismatched content directory."""
        first = self.build()
        original_review = copy.deepcopy(self.review)
        self.review["reason"] += " Separate review."
        second = self.build()
        shutil.copytree(second["input_dir"], first["input_dir"], dirs_exist_ok=True)
        self.review = original_review
        with self.assertRaisesRegex(ValueError, "different build ID"):
            self.build()

    def test_missing_evidence_or_attestation_is_rejected(self):
        """Require explicit review fields, including literal true rather than 1."""
        original = copy.deepcopy(self.review)
        for field, value in (("evidence", []), ("reviewer", ""), ("reason", ""), ("coverage_complete", False), ("coverage_complete", 1)):
            with self.subTest(field=field, value=value):
                self.review = {**copy.deepcopy(original), field: value}
                with self.assertRaises(ValueError):
                    self.build()

    def test_failed_review_saves_actionable_report_without_publishable_manifest(self):
        """A failed review leaves diagnostic evidence but no loadable candidate."""
        self.review["coverage_complete"] = False
        with self.assertRaisesRegex(ValueError, "Calendar review report"):
            self.build()
        reports = list((self.root / "bundles").glob("failed-*/calendar_report.json"))
        self.assertEqual(len(reports), 1)
        failure = json.loads(reports[0].read_text())
        self.assertEqual(failure["status"], "ERROR")
        self.assertIn("coverage_complete", failure["errors"][0])
        self.assertEqual([path.name for path in reports[0].parent.iterdir()], ["calendar_report.json"])

    def test_unknown_date_within_coverage_or_full_year_is_rejected(self):
        """An uncertainty bound equal to coverage_end is still inside coverage."""
        self.review["coverage_end"] = "2026-09-01"
        with self.assertRaisesRegex(ValueError, "may affect the covered interval"):
            self.build()
        self.review["coverage_end"] = "2026-12-31"
        with self.assertRaises(ValueError):
            self.build()

    def test_uncertain_afl_requires_explicit_attestation(self):
        """A short interval does not implicitly excuse an absent AFL family."""
        self.review["unconfirmed_events"] = []
        with self.assertRaisesRegex(ValueError, "missing families: afl"):
            self.build()

    def test_other_missing_holiday_families_are_not_excused_by_partial_coverage(self):
        """The narrowly defined AFL exception must not relax other date checks."""
        raw = self.corrected.read_text()
        self.corrected.write_text("\n".join(line for line in raw.splitlines() if "Australia Day" not in line) + "\n")
        self.review["corrected_sha256"] = hashlib.sha256(self.corrected.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, "missing families: australia"):
            self.build()

    def test_wrong_hash_unchecked_evidence_or_nonofficial_url_is_rejected(self):
        """Bind corrections to exact bytes and deferral to checked official URLs."""
        original = copy.deepcopy(self.review)
        self.review["original_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hashes"):
            self.build()
        self.review = copy.deepcopy(original)
        self.review["unconfirmed_events"][0]["evidence_urls"] = ["https://www.vic.gov.au/unchecked"]
        with self.assertRaisesRegex(ValueError, "checked evidence"):
            self.build()
        self.review = copy.deepcopy(original)
        self.review["evidence"][0]["url"] = "https://vic.gov.au.attacker.example/"
        with self.assertRaisesRegex(ValueError, "official"):
            self.build()

    def test_tampering_with_any_provenance_file_or_output_is_rejected(self):
        """Even a newline change must break the retained byte-level identity."""
        for filename in ("original/important_dates.csv", "original/sources.json", "reviewed/important_dates.csv", "review.json", "calendar.csv"):
            with self.subTest(file=filename):
                result = self.build()
                path = Path(result["input_dir"]) / filename
                before = path.read_bytes()
                path.write_bytes(before + b"\n")
                with self.assertRaises(ValueError):
                    validate_bundle(result["input_dir"])
                path.write_bytes(before)

    def test_manifest_cannot_claim_full_year_or_ready_for_bounded_input(self):
        """Reconstruction must defeat edited coverage/status fields in a manifest."""
        result = self.build()
        path = Path(result["input_dir"]) / "calendar_manifest.json"
        original = json.loads(path.read_text())
        for field, value in (("coverage_end", "2026-12-31"), ("status", "READY"), ("full_year_complete", True)):
            with self.subTest(field=field):
                path.write_text(json.dumps({**original, field: value}))
                with self.assertRaises(ValueError):
                    validate_bundle(result["input_dir"])
        path.write_text(json.dumps(original))

    def test_bounded_publication_is_blocked_before_database_connection(self):
        """Only a literal True may authorize a backend-aware bounded publication."""
        result = self.build()
        with patch("psycopg.connect") as connect:
            for opt_in in (False, "yes", 1):
                with self.assertRaisesRegex(ValueError, "Bounded calendar publication"):
                    load_calendar("unused", result["input_dir"], expected_current_build_id="none", allow_bounded_coverage=opt_in)
        connect.assert_not_called()

    def test_bounded_dry_run_exposes_bounds_without_opt_in(self):
        """A read-only preview can explain bounds before publication is authorized."""
        result = self.build()
        with patch("psycopg.connect"), patch("iteration3_v1_pipeline.calendar_storage._read_current", return_value=(None, [])):
            preview = load_calendar("unused", result["input_dir"], dry_run=True)
        self.assertEqual(preview["status"], "dry_run")
        self.assertEqual(preview["calendar_status"], "READY_BOUNDED")
        self.assertEqual(preview["coverage_end"], "2026-04-30")
        self.assertTrue(preview["requires_bounded_backend"])

    def test_explicit_bounded_opt_in_preserves_status_in_publication_manifest(self):
        """The loader must store the same bounds that a compatible backend reads."""
        result = self.build()
        with patch("psycopg.connect") as connect, patch("iteration3_v1_pipeline.calendar_storage._read_current", return_value=(None, [])):
            published = load_calendar("unused", result["input_dir"], expected_current_build_id="none", allow_bounded_coverage=True)
        self.assertEqual(published["status"], "loaded")
        cursor = connect.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
        # Inspect the manifest passed to the coverage upsert, not just the
        # user-facing success report, because the backend reads database state.
        source_manifest = cursor.execute.call_args_list[-1].args[1][-1].obj
        self.assertEqual(source_manifest["status"], "READY_BOUNDED")
        self.assertEqual(source_manifest["coverage_end"], "2026-04-30")


if __name__ == "__main__":
    unittest.main()
