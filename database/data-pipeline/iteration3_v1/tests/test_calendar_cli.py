"""Exercise calendar command routing and guards without opening a database."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from iteration3_v1_pipeline.__main__ import main


class CalendarCliTests(unittest.TestCase):
    def invoke(self, argv):
        output = io.StringIO()
        with redirect_stdout(output):
            main(argv)
        return json.loads(output.getvalue())

    def test_import_and_build_arguments_reach_their_stages(self):
        with patch("iteration3_v1_pipeline.calendar_download.download_calendar", return_value={"ok": True}) as stage:
            self.assertEqual(self.invoke(["calendar-download", "--year", "2026", "--output", "raw",
                                          "--import-dir", "snapshot", "--verified-at", "2026-09-01T00:00:00Z"]), {"ok": True})
            stage.assert_called_once_with(2026, Path("raw"), import_dir=Path("snapshot"),
                                          verified_at="2026-09-01T00:00:00Z")
        with patch("iteration3_v1_pipeline.calendar.build_calendar", return_value={"status": "READY"}) as stage:
            self.invoke(["calendar-build", "--year", "2026", "--source-dir", "raw/snapshot",
                         "--output", "processed", "--previous", "old"])
            stage.assert_called_once_with(Path("raw/snapshot"), 2026, Path("processed"), previous=Path("old"))

    def test_manual_regional_option_is_not_supported(self):
        with patch("iteration3_v1_pipeline.calendar_download.download_calendar") as stage:
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                main(["calendar-download", "--year", "2026", "--regional-overrides", "reviewed.json"])
            self.assertEqual(error.exception.code, 2)
            stage.assert_not_called()

    def test_dry_run_and_write_token_are_forwarded(self):
        with patch("iteration3_v1_pipeline.calendar_storage.load_calendar", return_value={"ok": True}) as stage:
            self.invoke(["calendar-load", "--database-url", "test-db", "--input", "bundle", "--dry-run"])
            stage.assert_called_once_with("test-db", Path("bundle"), dry_run=True, expected_current_build_id=None)
            stage.reset_mock()
            self.invoke(["calendar-load", "--database-url", "test-db", "--input", "bundle",
                         "--expected-current-build-id", "none"])
            stage.assert_called_once_with("test-db", Path("bundle"), dry_run=False, expected_current_build_id="none")

    def test_missing_database_and_write_token_stop_before_connection(self):
        for argv in [
            ["calendar-load", "--input", "bundle", "--dry-run"],
            ["calendar-load", "--input", "bundle", "--database-url", "test-db"],
        ]:
            with self.subTest(argv=argv), patch.dict("os.environ", {}, clear=True), redirect_stderr(io.StringIO()):
                with patch("iteration3_v1_pipeline.calendar_storage.load_calendar") as stage:
                    with self.assertRaises(SystemExit) as error:
                        main(argv)
                    self.assertEqual(error.exception.code, 2)
                    stage.assert_not_called()


if __name__ == "__main__":
    unittest.main()
