"""Acquisition accepts the official CSV as its sole calendar data source."""

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import requests

from iteration3_v1_pipeline.calendar_download import (
    MAX_SOURCE_BYTES, METADATA_URL, PACKAGE_ID, PACKAGE_NAME,
    SOURCE_FILES, download_calendar, source_urls, validate_csv_url,
)

RESOURCE_ID = "caaa47de-8626-46a6-aa28-3d948c15c5d9"
CSV_URL = f"https://discover.data.vic.gov.au/dataset/{PACKAGE_ID}/resource/{RESOURCE_ID}/download/important-dates-august-2026.csv"
CSV = ("arun,dateType,name,important_date,publisher,description,source\n"
       "1,PUBLIC_HOLIDAY,Labour Day,9/03/2026,Victoria,,https://business.vic.gov.au/\n").encode()
VERIFIED = "2026-09-01T00:00:00+00:00"
METADATA = {"success": True, "result": {"id": PACKAGE_ID, "name": PACKAGE_NAME, "state": "active",
            "resources": [{"id": RESOURCE_ID, "package_id": PACKAGE_ID, "state": "active", "format": "CSV",
                           "url": CSV_URL, "last_modified": "2026-08-18T00:44:10.743358"}]}}
PATCH = "iteration3_v1_pipeline.calendar_download."


class CalendarDownloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.output = self.root / "snapshots"

    def response(self, url, *, status=200, content=CSV, content_type="text/csv; charset=utf-8"):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status_code = status
        response.url = url
        response.headers = {"Content-Type": content_type}
        response.iter_content.return_value = [content]
        if status >= 400:
            response.raise_for_status.side_effect = requests.HTTPError(f"HTTP {status}")
        return response

    def replies(self, metadata=None, csv=CSV):
        return [self.response(METADATA_URL, content=json.dumps(metadata or METADATA).encode(), content_type="application/json"),
                self.response(CSV_URL, content=csv)]

    def download(self, *, metadata=None, csv=CSV):
        with patch(PATCH + "requests.get", side_effect=self.replies(metadata, csv)) as get:
            result = download_calendar(2026, self.output)
        return result, get

    def manifest(self, result):
        return json.loads((Path(result["source_dir"]) / "sources.json").read_text())

    def assert_no_snapshot(self):
        self.assertEqual(list(self.output.iterdir()) if self.output.exists() else [], [])

    def test_http_fetches_catalogue_and_original_csv_only(self):
        result, get = self.download()
        self.assertEqual([call.args[0] for call in get.call_args_list], [METADATA_URL, CSV_URL])
        snapshot = Path(result["source_dir"])
        manifest = self.manifest(result)
        self.assertEqual(manifest["schema_version"], 3)
        self.assertEqual(set(manifest["sources"]), set(SOURCE_FILES))
        source = manifest["sources"]["important_dates"]
        self.assertEqual(source["resource_id"], RESOURCE_ID)
        self.assertEqual(source["metadata_url"], METADATA_URL)
        self.assertEqual(source["url"], CSV_URL)
        self.assertEqual(source["last_modified"], METADATA["result"]["resources"][0]["last_modified"])
        self.assertEqual(source["verified_at"], manifest["fetched_at"])
        self.assertEqual((snapshot / SOURCE_FILES["important_dates"]).read_bytes(), CSV)
        self.assertEqual(source["sha256"], hashlib.sha256(CSV).hexdigest())
        self.assertEqual(set(path.name for path in snapshot.iterdir()), {"important_dates.csv", "sources.json"})
        self.assertEqual(set(manifest["sources"]), {"important_dates"})
        self.assertNotIn("regional_acquisition", result)

    def test_manual_snapshot_import_preserves_only_official_csv_bytes(self):
        first, _ = self.download()
        checked = "2026-09-02T00:00:00+00:00"
        with patch(PATCH + "requests.get") as get:
            result = download_calendar(2026, self.output, import_dir=first["source_dir"], verified_at=checked)
        get.assert_not_called()
        manifest = self.manifest(result)
        self.assertEqual(manifest["verified_at"], checked)
        self.assertEqual(manifest["sources"]["important_dates"]["acquisition"], "local_csv")
        self.assertEqual(manifest["sources"]["important_dates"]["verified_at"], checked)
        for filename in SOURCE_FILES.values():
            self.assertEqual((Path(result["source_dir"]) / filename).read_bytes(),
                             (Path(first["source_dir"]) / filename).read_bytes())

    def test_import_requires_a_nonfuture_utc_verification_time(self):
        for stamp in [None, "invalid", "2026-09-01T00:00:00", "2026-09-01T00:00:00+10:00", "9998-01-01T00:00:00Z"]:
            with self.subTest(stamp=stamp), self.assertRaises(ValueError):
                download_calendar(2026, self.output, import_dir=self.root, verified_at=stamp)
        self.assertFalse(self.output.exists())

    def test_import_requires_intact_provenance_and_fixed_filenames(self):
        first, _ = self.download()
        source_dir = Path(first["source_dir"])
        baseline = self.manifest(first)
        variants = []
        for key, value in [("url", "https://example.com/calendar.csv"), ("file", "../calendar.csv"),
                           ("sha256", "0" * 64), ("metadata_url", "https://example.com/api"),
                           ("resource_id", "wrong-id")]:
            manifest = deepcopy(baseline)
            manifest["sources"]["important_dates"][key] = value
            variants.append(manifest)
        manifest = deepcopy(baseline)
        manifest["year"] = 2027
        variants.append(manifest)
        for manifest in variants:
            with self.subTest(manifest=manifest):
                (source_dir / "sources.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    download_calendar(2026, self.output, import_dir=source_dir, verified_at=VERIFIED)
                self.assertEqual(list(self.output.iterdir()), [source_dir])
        (source_dir / "sources.json").write_text(json.dumps(baseline))
        (source_dir / SOURCE_FILES["important_dates"]).write_bytes(CSV + b"changed")
        with self.assertRaisesRegex(ValueError, "hash"):
            download_calendar(2026, self.output, import_dir=source_dir, verified_at=VERIFIED)

    def test_latest_active_resource_selected_and_ties_rejected(self):
        metadata = deepcopy(METADATA)
        older = deepcopy(metadata["result"]["resources"][0])
        older["last_modified"] = "2025-01-01T00:00:00"
        metadata["result"]["resources"].insert(0, older)
        result, _ = self.download(metadata=metadata)
        self.assertEqual(self.manifest(result)["sources"]["important_dates"]["last_modified"],
                         METADATA["result"]["resources"][0]["last_modified"])
        metadata["result"]["resources"][0]["last_modified"] = metadata["result"]["resources"][1]["last_modified"]
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            self.download(metadata=metadata)
        self.assertEqual(len(list(self.output.iterdir())), 1)

    def test_wrong_catalogue_or_resource_identity_never_published(self):
        changes = [("id", "another-package"), ("name", "other-data"), ("state", "deleted")]
        for key, value in changes:
            metadata = deepcopy(METADATA)
            metadata["result"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.download(metadata=metadata)
            self.assert_no_snapshot()
        for key, value in [("package_id", "another-package"), ("url", "https://example.com/file.csv"),
                           ("id", "wrong-id"), ("state", "deleted"), ("format", "HTML")]:
            metadata = deepcopy(METADATA)
            metadata["result"]["resources"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.download(metadata=metadata)
            self.assert_no_snapshot()

    def test_partial_failure_preserves_previous_snapshot_and_cleans_attempt(self):
        first, _ = self.download()
        with patch(PATCH + "requests.get", side_effect=[self.replies()[0], requests.Timeout("unavailable")]):
            with self.assertRaises(requests.Timeout):
                download_calendar(2026, self.output)
        self.assertEqual(list(self.output.iterdir()), [Path(first["source_dir"])])

    def test_http_403_does_not_publish_or_request_html(self):
        with patch(PATCH + "requests.get", return_value=self.response(METADATA_URL, status=403)) as get:
            with self.assertRaises(requests.HTTPError):
                download_calendar(2026, self.output)
        self.assertEqual(get.call_count, 1)
        self.assert_no_snapshot()

    def test_wrong_host_and_wrong_content_type_rejected(self):
        for url, content_type in [("https://example.com/error", "text/csv"),
                                  (CSV_URL, "text/html"), (CSV_URL, "application/json")]:
            replies = [self.replies()[0], self.response(url, content_type=content_type)]
            with self.subTest(url=url, type=content_type), patch(PATCH + "requests.get", side_effect=replies):
                with self.assertRaises(ValueError):
                    download_calendar(2026, self.output)
            self.assert_no_snapshot()

    def test_html_empty_bad_header_malformed_and_oversized_csv_rejected(self):
        for data in [b"", b"<html>Just a moment...</html>", b"date,name\n2026-03-09,Labour Day\n",
                     CSV + b"wrong,row\n", b"a" * (MAX_SOURCE_BYTES + 1),
                     CSV.splitlines(keepends=True)[0], CSV + b"\xff"]:
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                self.download(csv=data)
            self.assert_no_snapshot()

    def test_old_regional_snapshot_schema_is_rejected(self):
        first, _ = self.download()
        source_dir = Path(first["source_dir"])
        manifest = self.manifest(first)
        manifest["schema_version"] = 2
        manifest["sources"]["regional_holidays"] = {"file": "regional_holidays.json"}
        (source_dir / "sources.json").write_text(json.dumps(manifest))
        with patch(PATCH + "requests.get") as get, self.assertRaisesRegex(ValueError, "schema version 3"):
            download_calendar(2026, self.output, import_dir=source_dir, verified_at=VERIFIED)
        get.assert_not_called()
        self.assertEqual(list(self.output.iterdir()), [source_dir])

    def test_import_does_not_read_or_copy_unlisted_local_supplement(self):
        first, _ = self.download()
        source_dir = Path(first["source_dir"])
        (source_dir / "regional_holidays.json").write_bytes(b"This is not valid JSON")
        with patch(PATCH + "requests.get") as get:
            result = download_calendar(2026, self.output, import_dir=source_dir, verified_at=VERIFIED)
        get.assert_not_called()
        copied = Path(result["source_dir"])
        self.assertEqual(set(path.name for path in copied.iterdir()), {"important_dates.csv", "sources.json"})
        self.assertEqual(set(self.manifest(result)["sources"]), {"important_dates"})

    def test_official_csv_url_validation_rejects_lookalike_hosts_and_query_urls(self):
        self.assertEqual(validate_csv_url(CSV_URL, RESOURCE_ID), CSV_URL)
        for url in [CSV_URL.replace("https://", "http://"), CSV_URL + "?x=1",
                    CSV_URL + "#fragment", CSV_URL.replace("discover.data.vic.gov.au", "discover.data.vic.gov.au.evil.test"),
                    CSV_URL.replace(PACKAGE_ID, "another-dataset"), CSV_URL.replace(".csv", ".html")]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_csv_url(url, RESOURCE_ID)


if __name__ == "__main__":
    unittest.main()
