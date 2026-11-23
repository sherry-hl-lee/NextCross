"""Back up all nine pipeline-schema tables; restore only into a disposable DB.

Connection details come from DATABASE_URL, never command arguments. Archives
include only iteration2_v2 schema definitions and all nine tables, including
that schema's notification and dispatch history. The owner intentionally excludes
iteration1_v2.notification_event as temporary storage. Cluster roles, their
passwords, and runtime grants are also excluded. Restore is a rehearsal/new
environment operation; production recovery requires an operator-led runbook.

The PostgreSQL command-line clients must be installed separately. Verification
checks byte integrity and the expected table-data inventory, not archive trust:
pg_restore can execute SQL, so operators must retain backups in controlled
storage and restore only an archive produced by a trusted source. The manifest
is not a signature, and neither backup creation nor verification proves that
application reads work after a restore.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


# The backup contract covers only iteration2_v2, including its notification and
# dispatch tables. The owner treats iteration1_v2.notification_event as temporary
# storage and intentionally excludes it; no notification cutover is performed.
TABLES = sorted(("road_speed", "speed_rule", "calendar_period", "calendar_coverage",
                 "crash_build", "crash_event", "hotspot", "notification_event",
                 "report_email_dispatch"))
# iteration3_v1 still writes the existing iteration2_v2 database schema; the
# archive name follows that schema rather than the pipeline directory version.
ARCHIVE = "iteration2_v2.dump"
MANIFEST = "backup_manifest.json"
IDENTITY_SQL = """SELECT json_build_object('database', current_database(), 'tables',
    (SELECT coalesce(json_agg(tablename ORDER BY tablename), '[]'::json)
     FROM pg_tables WHERE schemaname = 'iteration2_v2'))"""
# Only recognized libpq settings are translated from the selected connection.
# No connection URI or password needs to appear in a subprocess argument list.
CONNECTION_VARIABLES = {
    "host": "PGHOST", "hostaddr": "PGHOSTADDR", "port": "PGPORT",
    "dbname": "PGDATABASE", "user": "PGUSER", "password": "PGPASSWORD",
    "sslmode": "PGSSLMODE", "sslrootcert": "PGSSLROOTCERT",
    "sslcert": "PGSSLCERT", "sslkey": "PGSSLKEY", "sslpassword": "PGSSLPASSWORD",
    "channel_binding": "PGCHANNELBINDING", "connect_timeout": "PGCONNECT_TIMEOUT",
    "options": "PGOPTIONS", "application_name": "PGAPPNAME",
    "target_session_attrs": "PGTARGETSESSIONATTRS", "sslnegotiation": "PGSSLNEGOTIATION",
}


class BackupValidationError(ValueError):
    """A fixed, non-sensitive validation message suitable for an operator."""


class BackupClientError(RuntimeError):
    """A safe client failure; raw PostgreSQL stderr is deliberately excluded."""


def _environment():
    """Return a fresh libpq client environment derived only from DATABASE_URL.

    Require explicit host/database and supported options, raising a sanitized
    BackupValidationError for an invalid connection. The process environment
    itself is not changed. Secrets remain in the returned child environment,
    which callers must not log; this avoids argv exposure, not all local access.
    """
    from psycopg.conninfo import conninfo_to_dict

    value = os.environ.get("DATABASE_URL")
    if not value:
        raise BackupValidationError("Set DATABASE_URL for the selected source or disposable target")
    try:
        parts = conninfo_to_dict(value)
    except Exception:
        raise BackupValidationError("DATABASE_URL is not a valid PostgreSQL connection") from None
    if set(parts) - set(CONNECTION_VARIABLES) or not parts.get("dbname") or not parts.get("host"):
        raise BackupValidationError("Use an explicit database and host with supported PostgreSQL connection options")
    # Inherited service files or PGOPTIONS must not redirect the selected target.
    environment = {key: item for key, item in os.environ.items()
                   if not key.startswith("PG") and key != "DATABASE_URL"}
    environment.update({CONNECTION_VARIABLES[key]: item for key, item in parts.items()})
    environment.setdefault("PGCONNECT_TIMEOUT", "15")
    environment["PGAPPNAME"] = "iteration3_v1_backup"
    return environment


def _run(arguments, environment=None):
    """Run a fixed client argv without a shell and return captured text stdout.

    environment supplies the selected connection, or is omitted for an offline
    archive listing. Raise BackupClientError on nonzero exit, startup failure or
    the 30-minute timeout. Never include raw stderr in the raised exception.
    This helper does not decide whether a command is read-only; callers do.
    """
    try:
        result = subprocess.run(arguments, env=environment, capture_output=True,
                                text=True, timeout=1800, check=False)
    except (OSError, subprocess.SubprocessError):
        raise BackupClientError(f"{arguments[0]} could not complete; check client installation and connection") from None
    if result.returncode:
        # PostgreSQL messages can contain connection details or application data.
        raise BackupClientError(f"{arguments[0]} failed; check access, client version and destination capacity")
    return result.stdout


def _identity(environment):
    """Read the actual connected database name and ordered schema table names.

    Return a parsed identity dictionary, rejecting malformed output with
    BackupClientError. The query is read-only and uses psql -X so user startup
    files cannot change this preflight's behaviour or output.
    """
    result = _run(["psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", IDENTITY_SQL], environment)
    try:
        identity = json.loads(result)
        if not isinstance(identity["database"], str) or not isinstance(identity["tables"], list):
            raise ValueError
        return identity
    except (ValueError, KeyError, TypeError):
        raise BackupClientError("Could not verify the selected database identity") from None


def _file_info(path):
    """Return bytes and SHA-256 for a stable local archive; propagate I/O errors.

    Stream the digest rather than loading the entire database dump into memory.
    The caller must keep the file unchanged while hashing and using the result.
    """
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"bytes": path.stat().st_size, "sha256": digest}


def verify(directory):
    """Check local archive integrity and the exact nine-table data inventory.

    Args:
        directory: Trusted backup directory containing only ARCHIVE and MANIFEST.

    Returns:
        The parsed manifest after its schema, size, digest and table list match.

    Raises:
        BackupValidationError: For symlinks, unexpected files, unsupported
            manifest metadata, altered archive bytes or a wrong table inventory.
        BackupClientError: If pg_restore cannot list the local archive.
        Exception: Propagates filesystem and JSON parsing failures.

    Side effects:
        Reads local files and invokes pg_restore --list without a database
        connection. It neither restores data nor authenticates the archive's
        origin, and it does not audit every SQL object stored in the archive.
    """
    directory = Path(directory)
    if directory.is_symlink() or any(path.is_symlink() for path in directory.rglob("*")):
        raise BackupValidationError("Backup paths must not contain symlinks")
    if {path.name for path in directory.iterdir()} != {ARCHIVE, MANIFEST}:
        raise BackupValidationError("Backup must contain exactly its archive and manifest")
    manifest = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("schema") != "iteration2_v2" or manifest.get("tables") != TABLES:
        raise BackupValidationError("Backup schema or table manifest is not supported")
    # Reject changed bytes before asking PostgreSQL's archive reader to inspect
    # the contents. The subsequent inventory check answers a different question.
    if manifest.get("archive") != {"file": ARCHIVE, **_file_info(directory / ARCHIVE)}:
        raise BackupValidationError("Backup archive hash or size does not match its manifest")
    # Even an empty table must have a TABLE DATA entry. Checking definitions
    # alone could accept an archive that omitted application history or data.
    listing = _run(["pg_restore", "--list", str(directory / ARCHIVE)])
    tables = sorted(re.findall(r";\s+\d+\s+\d+\s+TABLE DATA iteration2_v2 (\S+) ", listing))
    if tables != TABLES:
        raise BackupValidationError("Backup archive does not contain all nine schema tables")
    return manifest


def backup(output, *, commit=None):
    """Create a new verified PostgreSQL custom-format schema/data backup.

    Args:
        output: New destination directory; an existing backup is never replaced.
        commit: Optional full lowercase Git SHA recorded as operator provenance.

    Returns:
        A backed_up result with the absolute directory and completed manifest.

    Raises:
        FileExistsError: If output already exists.
        BackupValidationError: For invalid connection/commit, unexpected source
            tables, or a dump that fails the offline inventory checks.
        BackupClientError: For PostgreSQL client or connection failures.
        Exception: Propagates filesystem or manifest parsing failures.

    Side effects:
        Reads the DATABASE_URL database using psql and pg_dump, without changing
        its data. Writes a private temporary bundle, verifies it, then renames
        it into output. Temporary output is cleaned up on failure. Schema/data
        in iteration2_v2 are included. The temporary iteration1_v2 notification
        table is excluded by owner decision; roles, ownership and grants require
        separate provisioning.
    """
    if commit is not None and not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise BackupValidationError("--commit requires the full lowercase Git commit")
    output = Path(output).resolve()
    if output.exists():
        raise FileExistsError("Choose a new backup output directory")
    environment = _environment()
    identity = _identity(environment)
    # Reject both missing and unexpected tables so a changed schema cannot be
    # silently advertised as a complete backup under this nine-table contract.
    if identity["tables"] != TABLES:
        raise BackupValidationError("Source must have the expected nine iteration2_v2 tables")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".backup-", dir=output.parent) as temporary:
        work = Path(temporary) / "bundle"
        work.mkdir(mode=0o700)
        archive = work / ARCHIVE
        _run(["pg_dump", "--format=custom", "--schema=iteration2_v2", "--no-owner",
              "--no-privileges", "--file", str(archive)], environment)
        # The bundle directory is private while pg_dump writes; keep the dump
        # owner-readable only after the completed bundle is moved into place.
        archive.chmod(0o600)
        manifest = {"schema_version": 1, "schema": "iteration2_v2", "tables": TABLES,
                    "created_at": datetime.now(timezone.utc).isoformat(),
                    "source_database": identity["database"], "code_commit": commit,
                    "archive": {"file": ARCHIVE, **_file_info(archive)},
                    "scope": "iteration2_v2 schema and all nine tables, including iteration2_v2.notification_event and iteration2_v2.report_email_dispatch; intentionally excludes temporary iteration1_v2.notification_event, roles, credentials and grants."}
        (work / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        # A failed dump or failed verification never becomes a completed backup
        # directory. The final rename stays on the output parent's filesystem.
        verify(work)
        work.rename(output)
    return {"status": "backed_up", "directory": str(output), **manifest}


def restore(directory, *, confirm_database):
    """Rehearse restoration into an explicitly named disposable database.

    Args:
        directory: Trusted, stable local backup accepted by verify. Operators
            must keep its bytes unchanged until pg_restore has finished.
        confirm_database: Exact server database name beginning iteration2_test
            and containing only letters, digits or underscores thereafter.

    Returns:
        A restored result with target database, table list and archive digest.

    Raises:
        BackupValidationError: If backup verification, disposable-name rules or
            the connected server's actual database name fail their checks.
        BackupClientError: If a client command fails or restored tables differ.
        Exception: Propagates local archive/manifest read failures.

    Side effects:
        Reads DATABASE_URL, confirms the actual server database name, then may
        create PostGIS. pg_restore replaces archive-owned objects using --clean
        in one transaction. Extension creation is a separate earlier operation
        and is not rolled back if restoration fails. Runtime grants and an
        application smoke test remain separate steps; production targets are
        deliberately unsupported by this rehearsal command.
    """
    manifest = verify(directory)
    if not re.fullmatch(r"iteration2_test[A-Za-z0-9_]*", confirm_database):
        raise BackupValidationError("Restore requires --confirm-database iteration2_test* using letters, digits or underscores")
    environment = _environment()
    identity = _identity(environment)
    if identity["database"] != confirm_database:
        raise BackupValidationError("Connected database does not match the explicitly confirmed disposable database")
    # No mutating command runs until the server's own database name was checked.
    _run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-c", "CREATE EXTENSION IF NOT EXISTS postgis"], environment)
    # Passing only the already-confirmed database name keeps credentials out of
    # argv. Host, user and TLS configuration stay in the selected environment.
    _run(["pg_restore", "--clean", "--if-exists", "--no-owner", "--no-privileges",
          "--single-transaction", "--exit-on-error", "--dbname", confirm_database,
          str(Path(directory) / ARCHIVE)], environment)
    if _identity(environment)["tables"] != TABLES:
        raise BackupClientError("Restored schema does not match the expected nine tables")
    return {"status": "restored", "database": confirm_database, "tables": TABLES,
            "backup_sha256": manifest["archive"]["sha256"],
            "note": "Apply runtime-role grants separately; verify application reads before recovery sign-off."}


def main(argv=None):
    """Dispatch the backup, verify or disposable-restore CLI and print JSON.

    Args:
        argv: Optional argument list; None uses the process command line.

    Returns:
        0 on success or 1 for an operation failure after arguments are parsed.

    Raises:
        SystemExit: argparse handles --help and invalid CLI syntax itself.

    Side effects:
        Performs the selected command's documented work and writes its result
        to stdout. Only deliberately sanitized validation/client messages are
        printed on failure; other exceptions expose their class name alone.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("backup")
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--commit")
    for name in ("verify", "restore"):
        command = commands.add_parser(name)
        command.add_argument("--input", type=Path, required=True)
        if name == "restore":
            command.add_argument("--confirm-database", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "backup":
            result = backup(args.output, commit=args.commit)
        elif args.command == "verify":
            result = {"status": "verified", **verify(args.input)}
        else:
            result = restore(args.input, confirm_database=args.confirm_database)
        print(json.dumps(result, indent=2))
        return 0
    except Exception as error:
        # Never echo raw subprocess/database errors or source/target credentials.
        result = {"status": "failed", "command": args.command,
                  "error_type": type(error).__name__}
        if isinstance(error, (BackupValidationError, BackupClientError)):
            result["message"] = str(error)
        print(json.dumps(result))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
