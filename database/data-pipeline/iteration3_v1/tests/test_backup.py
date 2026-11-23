"""Check backup integrity, credential handling and disposable restore guards.

All PostgreSQL client processes are replaced with an in-memory command recorder.
Synthetic archives exercise command ordering, manifests and failure cleanup
without contacting any database. These tests do not replace a real disposable
restore rehearsal or demonstrate that restored application queries succeed.
"""

from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from deploy import backup


class BackupTests(unittest.TestCase):
    """Keep backup/restore safeguards observable without real credentials or DBs."""

    def setUp(self):
        """Install isolated storage, conflicting libpq settings and fake clients."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "backup"
        self.database = "source_database"
        self.tables = backup.TABLES[:]
        self.calls = []
        self.failure = None
        self.secret = "do-not-print-this-secret"
        # Conflicting inherited settings make accidental target selection
        # visible; the explicit DATABASE_URL must be the only connection source.
        self.environment = patch.dict("os.environ", {
            "DATABASE_URL": f"postgresql://writer:{self.secret}@localhost/source_database?sslmode=require",
            "PGSERVICE": "unrelated-production-service", "PGDATABASE": "wrong_database",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.runner = patch.object(backup.subprocess, "run", side_effect=self.run_command)
        self.runner.start()
        self.addCleanup(self.runner.stop)

    def run_command(self, arguments, **kwargs):
        """Record argv/env and emulate only the PostgreSQL commands under test.

        Identity and TABLE DATA listings use mutable test state. pg_dump writes
        a tiny fake archive so normal hash checks run; configured failures put
        the synthetic secret in stderr to verify that it is never echoed.
        """
        self.calls.append((arguments, kwargs))
        if arguments[0] == self.failure:
            return subprocess.CompletedProcess(arguments, 1, "", self.secret)
        output = ""
        if arguments[0] == "psql" and arguments[-1] == backup.IDENTITY_SQL:
            output = json.dumps({"database": self.database, "tables": self.tables})
        elif arguments[0] == "pg_dump":
            Path(arguments[arguments.index("--file") + 1]).write_bytes(b"PGDMP synthetic archive")
        elif arguments[0] == "pg_restore" and "--list" in arguments:
            output = "\n".join(f"{i}; 0 {i} TABLE DATA iteration2_v2 {table} owner" for i, table in enumerate(self.tables))
        return subprocess.CompletedProcess(arguments, 0, output, "")

    def make_backup(self):
        """Create the standard synthetic backup with a reproducible fake commit."""
        return backup.backup(self.output, commit="a" * 40)

    def test_backup_contains_all_tables_and_keeps_credentials_out_of_arguments_and_manifest(self):
        """Preserve all nine tables while keeping the secret in child env only."""
        result = self.make_backup()
        self.assertEqual(result["tables"], backup.TABLES)
        self.assertIn("report_email_dispatch", backup.verify(self.output)["tables"])
        self.assertEqual(set(path.name for path in self.output.iterdir()), {backup.ARCHIVE, backup.MANIFEST})
        self.assertNotIn(self.secret, (self.output / backup.MANIFEST).read_text())
        for arguments, kwargs in self.calls:
            self.assertNotIn(self.secret, " ".join(arguments))
            if arguments[0] in ("psql", "pg_dump"):
                self.assertEqual(kwargs["env"]["PGPASSWORD"], self.secret)
                self.assertEqual(kwargs["env"]["PGDATABASE"], "source_database")
                self.assertNotIn("PGSERVICE", kwargs["env"])
                self.assertNotIn("DATABASE_URL", kwargs["env"])

    def test_corrupt_archive_is_rejected_before_restore_connection(self):
        """Fail byte-integrity checks before any PostgreSQL command is launched."""
        self.make_backup()
        (self.output / backup.ARCHIVE).write_bytes(b"corrupted")
        self.calls.clear()
        with self.assertRaisesRegex(ValueError, "hash or size"):
            backup.restore(self.output, confirm_database="iteration2_test_restore")
        self.assertEqual(self.calls, [])

    def test_server_database_guard_rejects_production_before_any_mutation(self):
        """An operator-supplied test name cannot override actual server identity."""
        self.make_backup()
        self.database = "neondb"
        self.calls.clear()
        with self.assertRaisesRegex(ValueError, "does not match"):
            backup.restore(self.output, confirm_database="iteration2_test_restore")
        self.assertTrue(self.calls)
        self.assertTrue(all((args[0] == "pg_restore" and "--list" in args)
                            or (args[0] == "psql" and args[-1] == backup.IDENTITY_SQL)
                            for args, _ in self.calls))

    def test_restore_requires_explicit_disposable_name_even_if_connection_is_test(self):
        """Reject production names and strings that could add connection options."""
        self.make_backup()
        self.database = "iteration2_test_restore"
        self.calls.clear()
        for name in ("neondb", "iteration2_test_restore host=other", "iteration2_test_restore;DROP"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "confirm-database"):
                backup.restore(self.output, confirm_database=name)
        self.assertTrue(all(args[0] == "pg_restore" and "--list" in args for args, _ in self.calls))

    def test_restore_rehearsal_uses_one_transaction_after_server_identity_check(self):
        """Confirm identity before extension creation and restore in one pg_restore transaction."""
        self.make_backup()
        self.database = "iteration2_test_restore"
        self.calls.clear()
        result = backup.restore(self.output, confirm_database=self.database)
        self.assertEqual(result["status"], "restored")
        mutating = [args for args, _ in self.calls if args[0] == "pg_restore" and "--list" not in args]
        self.assertEqual(len(mutating), 1)
        self.assertIn("--single-transaction", mutating[0])
        self.assertIn("--exit-on-error", mutating[0])
        first_identity = next(i for i, (args, _) in enumerate(self.calls) if args[-1] == backup.IDENTITY_SQL)
        first_write = next(i for i, (args, _) in enumerate(self.calls) if "CREATE EXTENSION IF NOT EXISTS postgis" in args)
        self.assertLess(first_identity, first_write)

    def test_missing_table_stops_backup_and_failed_dump_leaves_no_completed_backup(self):
        """Incomplete scope or client failure must leave no completed backup path."""
        self.tables.remove("report_email_dispatch")
        with self.assertRaisesRegex(ValueError, "nine"):
            self.make_backup()
        self.assertFalse(any(args[0] == "pg_dump" for args, _ in self.calls))
        self.tables = backup.TABLES[:]
        self.failure = "pg_dump"
        output = io.StringIO()
        with redirect_stdout(output):
            result = backup.main(["backup", "--output", str(self.output)])
        self.assertEqual(result, 1)
        self.assertNotIn(self.secret, output.getvalue())
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".backup-*")))

    def test_existing_backup_is_never_replaced(self):
        """Refuse an existing destination before opening even a read connection."""
        self.make_backup()
        self.calls.clear()
        with self.assertRaises(FileExistsError):
            self.make_backup()
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main()
