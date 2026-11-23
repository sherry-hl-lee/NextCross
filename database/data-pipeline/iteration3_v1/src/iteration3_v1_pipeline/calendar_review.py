"""Build auditable operator-reviewed calendars without rewriting official input.

Schema 4 is an explicit review policy, not an alternative way of importing an
official schema-3 snapshot. All normal date checks remain. Only an unconfirmed
AFL holiday outside an explicitly attested coverage interval may be deferred.
This module never fetches URLs, connects to a database, or invents holiday dates.

The original CSV and its source manifest remain byte-for-byte evidence. A
separate corrected CSV and reviewer declaration produce a content-addressed
bundle. Hashes detect mixed or changed files; they do not authenticate a reviewer
or independently establish that a cited page supports the declared correction.
The operator owns that judgement, and the backend owns enforcing coverage dates.
"""

from collections import Counter
import csv
from datetime import date
import io
import json
from pathlib import Path
import shutil
import tempfile
import uuid
from urllib.parse import urlparse

from iteration3_v1_pipeline.calendar import (
    CALENDAR_FIELDS, CSV_FIELDS, LIMITATIONS, _diff, _hash, _parse_sources,
    _read_snapshot, _utc, _validate_rows, _write_json, _year, calendar_csv_bytes,
)
from iteration3_v1_pipeline.settings import PROCESSED_DIR


POLICY_VERSION = "victoria-reviewed-calendar-v4"
COVERAGE_SCOPE = "reviewed_victoria_school_terms_and_public_holidays"
# These four inputs are retained verbatim and contribute to the build identity.
# An evidence-only review change therefore gets a new ID even if dates match.
AUDIT_FILES = ("original/important_dates.csv", "original/sources.json",
               "reviewed/important_dates.csv", "review.json")
BUNDLE_FILES = set(AUDIT_FILES) | {"calendar.csv", "calendar_manifest.json", "calendar_report.json"}


def _text(value, label):
    """Return a nonblank string unchanged; raise ValueError using label otherwise.

    Whitespace is checked without normalizing the retained reviewer evidence.
    """
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must contain an explanation or identifier")
    return value


def _day(value, year, label):
    """Parse an exact YYYY-MM-DD date in year, raising ValueError with label.

    Canonical spelling makes the stored coverage bounds unambiguous to consumers.
    """
    if not isinstance(value, str):
        raise ValueError(f"{label} must be an ISO date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO date") from error
    if parsed.year != year or parsed.isoformat() != value:
        raise ValueError(f"{label} must be an ISO date in {year}")
    return parsed


def _official_url(value):
    """Validate an evidence URL's official HTTPS host without fetching the page.

    Return the original URL. Reject credentials, nonstandard ports, or hosts
    outside vic.gov.au with ValueError; this validates origin, not page content.
    """
    value = _text(value, "Evidence URL")
    parsed = urlparse(value)
    host = parsed.hostname or ""
    if (parsed.scheme != "https" or parsed.username or parsed.password
            or parsed.port not in (None, 443)
            or not (host == "vic.gov.au" or host.endswith(".vic.gov.au"))):
        raise ValueError("Review evidence must link to an official Victorian government HTTPS page")
    return value


def _review(review, year, original, corrected):
    """Validate the operator declaration and its binding to the two CSV byte sets.

    review must contain exactly the documented schema-1 fields. Return inclusive
    (start, end, deferred_afl_lower_bound) dates, with None for no deferral.
    Raise ValueError for invalid evidence, timestamps, hashes or coverage claims.
    The evidence URLs are checked syntactically, never downloaded or interpreted.
    """
    if not isinstance(review, dict):
        raise ValueError("Calendar review must be a JSON object")
    required = {"schema_version", "reviewer", "reviewed_at", "reason", "evidence",
                "original_sha256", "corrected_sha256", "coverage_start", "coverage_end",
                "coverage_complete", "unconfirmed_events"}
    if set(review) != required or type(review["schema_version"]) is not int or review["schema_version"] != 1:
        raise ValueError("Calendar review requires exactly the documented schema-version-1 fields")
    _text(review["reviewer"], "Reviewer")
    _text(review["reason"], "Review reason")
    reviewed_at = _utc(review["reviewed_at"])
    # A literal JSON true is an intentional completeness attestation. Python's
    # truthiness would also accept 1 or a nonempty string, weakening the contract.
    if review["coverage_complete"] is not True:
        raise ValueError("Reviewer must explicitly attest coverage_complete: true for the stated interval")
    start = _day(review["coverage_start"], year, "coverage_start")
    end = _day(review["coverage_end"], year, "coverage_end")
    if start > end:
        raise ValueError("Reviewed coverage interval is reversed")
    # Bind the declaration to exact original and corrected bytes, including
    # duplicate rows and CSV formatting, before considering its stated bounds.
    if review["original_sha256"] != _hash(original) or review["corrected_sha256"] != _hash(corrected):
        raise ValueError("Review original/corrected hashes do not match the supplied CSVs")
    evidence = review["evidence"]
    if not isinstance(evidence, list) or not evidence:
        raise ValueError("Official evidence for the corrections and coverage attestation is required")
    urls = set()
    for entry in evidence:
        if not isinstance(entry, dict) or set(entry) != {"url", "checked_at", "note"}:
            raise ValueError("Each evidence entry requires url, checked_at and note")
        urls.add(_official_url(entry["url"]))
        _text(entry["note"], "Evidence note")
        if _utc(entry["checked_at"]) > reviewed_at:
            raise ValueError("Evidence check must not follow the review")
    # An event-specific citation must refer to evidence already checked in this
    # declaration; a new URL alone cannot justify relaxing the annual parser.
    unresolved = review["unconfirmed_events"]
    if not isinstance(unresolved, list) or len(unresolved) > 1:
        raise ValueError("Only the unconfirmed AFL holiday may be deferred")
    bound = None
    for event in unresolved:
        if not isinstance(event, dict) or set(event) != {"family", "not_before", "reason", "evidence_urls"} or event["family"] != "afl":
            raise ValueError("Only an explicit AFL lower-bound review is supported")
        _text(event["reason"], "Unconfirmed event reason")
        links = event["evidence_urls"]
        if not isinstance(links, list) or not links or any(not isinstance(link, str) or link not in urls for link in links):
            raise ValueError("Unconfirmed event must cite checked evidence from this review")
        bound = _day(event["not_before"], year, "AFL not_before")
        # Coverage is inclusive: uncertainty on its last day is still unsafe.
        # A lower bound asserts only that the unknown date is later; it does
        # not become a generated holiday or relax other required families.
        if bound <= end:
            raise ValueError("Unconfirmed AFL holiday may affect the covered interval; shorten coverage or await confirmation")
    return start, end, bound


def _changes(original, corrected):
    """Return sorted added/removed row multisets for the two UTF-8 CSV byte sets.

    Each entry records the original field values and its occurrence count.
    Reject unexpected headers or malformed rows with ValueError; CSV and decoding
    errors propagate. Counting rows instead of using a set exposes deduplication
    as an actual correction even when one identical row remains in the result.
    """
    def records(data):
        """Parse the fixed source columns into an exact-value occurrence counter."""
        reader = csv.DictReader(io.StringIO(data.decode("utf-8-sig"), newline=""), strict=True)
        if tuple(reader.fieldnames or ()) != CSV_FIELDS:
            raise ValueError("Unexpected reviewed/original DataVic CSV header")
        result = Counter()
        for row in reader:
            if set(row) != set(CSV_FIELDS) or any(value is None for value in row.values()):
                raise ValueError("Malformed reviewed/original DataVic CSV row")
            result[tuple(row[field] for field in CSV_FIELDS)] += 1
        return result
    before, after = records(original), records(corrected)
    def entries(values):
        """Serialize a difference counter in stable lexical row order."""
        return [{"record": dict(zip(CSV_FIELDS, row)), "occurrences": count}
                for row, count in sorted(values.items())]
    return {"removed": entries(before - after), "added": entries(after - before)}


def _assemble(year, source, payloads):
    """Derive a schema-4 manifest, rows, canonical CSV and source-table counts.

    source is an already validated official snapshot manifest; payloads contains
    the four retained audit files as bytes. Revalidate the review and corrected
    dates on every call, raising their validation/parsing errors. No files are
    written, so both build and later validation use the same deterministic path.
    """
    original = payloads["original/important_dates.csv"]
    corrected = payloads["reviewed/important_dates.csv"]
    review = json.loads(payloads["review.json"])
    start, end, deferred = _review(review, year, original, corrected)
    # Review authorizes only the documented deferral. Corrected rows still pass
    # the normal term, holiday-family and interval consistency checks.
    rows, evidence, table_counts = _parse_sources(
        {"important_dates": corrected.decode("utf-8-sig")}, year,
        deferred_afl_not_before=deferred)
    counts = _validate_rows(rows, year)
    data = calendar_csv_bytes(rows)
    # Keep validated known annual rows, including those outside this interval.
    # Their presence is evidence, not permission to evaluate dates beyond the
    # declared bounds; only the manifest establishes verified coverage.
    bounded = start != date(year, 1, 1) or end != date(year, 12, 31)
    status = "READY_BOUNDED" if bounded else "READY"
    audits = {name: {"sha256": _hash(value), "bytes": len(value)} for name, value in payloads.items()}
    identity = {"year": year, "policy_version": POLICY_VERSION, "audit_files": audits,
                "calendar_sha256": _hash(data)}
    # Include raw audit-byte digests, not merely the normalized date rows. No
    # build clock or output directory enters this identity, enabling safe reuse.
    build_id = _hash(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode())
    manifest = {
        "schema_version": 4, "status": status, "year": year, "build_id": build_id,
        "policy_version": POLICY_VERSION, "coverage_scope": COVERAGE_SCOPE,
        "regional_holidays_complete": False, "limitations": list(LIMITATIONS),
        "coverage_start": start.isoformat(), "coverage_end": end.isoformat(),
        "coverage_complete": True, "full_year_complete": not bounded,
        "requires_bounded_backend": bounded, "verified_at": _utc(review["reviewed_at"]),
        "reviewer": review["reviewer"], "sources": source["sources"],
        "source_snapshot_id": source["snapshot_id"], "audit_files": audits,
        "unconfirmed_events": review["unconfirmed_events"],
        "corrections": _changes(original, corrected),
        "file": {"file": "calendar.csv", "columns": list(CALENDAR_FIELDS),
                 "rows": len(rows), "bytes": len(data), "sha256": _hash(data)},
        "record_counts": counts, "evidence": evidence,
    }
    return manifest, rows, data, table_counts


def validate_reviewed_bundle(path, manifest=None):
    """Rebuild the reviewed result in memory and check its retained evidence.

    Args:
        path: Local bundle directory containing exactly BUNDLE_FILES.
        manifest: Optional manifest already read by calendar.validate_bundle;
            otherwise read calendar_manifest.json from path.

    Returns:
        The validated (manifest, rows) pair. Rows may extend beyond coverage;
        consumers must still enforce the manifest's inclusive date bounds.

    Raises:
        ValueError: For unsafe file inventory, invalid originals or review,
            changed output, inconsistent report, or unreadable bundle files.

    Side effects:
        Reads local files only. Reconstructs the expected manifest from retained
        inputs instead of trusting a supplied manifest's READY status or hashes.
    """
    path = Path(path)
    try:
        # Keep reconstruction self-contained: symlinks could point outside the
        # retained bundle, and extra files would make the audit inventory unclear.
        items = list(path.rglob("*"))
        if any(item.is_symlink() for item in items):
            raise ValueError("Reviewed calendar bundles must not contain symlinks")
        if {item.relative_to(path).as_posix() for item in items if item.is_file()} != BUNDLE_FILES:
            raise ValueError("Reviewed calendar bundle contains missing or unexpected files")
        if manifest is None:
            manifest = json.loads((path / "calendar_manifest.json").read_text(encoding="utf-8"))
        year = _year(manifest["year"])
        source, _ = _read_snapshot(path / "original", year)
        payloads = {name: (path / name).read_bytes() for name in AUDIT_FILES}
        # Re-derive both output and metadata from the inputs. Checking only the
        # saved output hash would miss a coordinated edit to CSV and manifest.
        expected, rows, data, _ = _assemble(year, source, payloads)
        # JSON equality distinguishes true from 1 and integer fields from floats;
        # ordinary Python dict equality would silently accept those mutations.
        if (json.dumps(manifest, sort_keys=True, allow_nan=False) != json.dumps(expected, sort_keys=True, allow_nan=False)
                or (path / "calendar.csv").read_bytes() != data):
            raise ValueError("Reviewed calendar manifest/output disagrees with original, correction or review evidence")
        report = json.loads((path / "calendar_report.json").read_text(encoding="utf-8"))
        for field in ("status", "year", "build_id", "coverage_start", "coverage_end", "record_counts"):
            if report.get(field) != manifest[field]:
                raise ValueError("Reviewed calendar report disagrees with the bundle")
        return manifest, rows
    except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, AttributeError, csv.Error) as error:
        raise ValueError(f"Invalid reviewed calendar bundle: {error}") from error


def _build_reviewed_calendar(source_dir, year, corrected_csv, review_file, output, previous):
    """Implement a successful build; let the public wrapper record failures.

    Arguments have the same contract as build_reviewed_calendar. Return its
    report after reusing a validated matching directory or renaming a fully
    validated temporary bundle into place. Validation and filesystem exceptions
    propagate; temporary files created by this function are removed on exit.
    """
    year, source_dir, output = _year(year), Path(source_dir), Path(output)
    source, _ = _read_snapshot(source_dir, year)
    payloads = {"original/important_dates.csv": (source_dir / "important_dates.csv").read_bytes(),
                "original/sources.json": (source_dir / "sources.json").read_bytes(),
                "reviewed/important_dates.csv": Path(corrected_csv).read_bytes(),
                "review.json": Path(review_file).read_bytes()}
    # Validate the bytes retained above, rather than only the first read of the
    # source, so a concurrent operator edit cannot create a mixed snapshot.
    if json.loads(payloads["original/sources.json"]) != source:
        raise ValueError("Official source manifest changed while preparing review")
    entry = source["sources"]["important_dates"]
    if entry["sha256"] != _hash(payloads["original/important_dates.csv"]) or entry["bytes"] != len(payloads["original/important_dates.csv"]):
        raise ValueError("Official source changed while preparing review")
    manifest, rows, data, table_counts = _assemble(year, source, payloads)
    target = output / manifest["build_id"]
    report = {key: manifest[key] for key in ("status", "year", "build_id", "coverage_scope",
               "regional_holidays_complete", "limitations", "coverage_start", "coverage_end",
               "full_year_complete", "requires_bounded_backend", "record_counts", "unconfirmed_events")}
    report.update(input_dir=str(target.resolve()), source_dir=str(source_dir.resolve()),
                  source_table_rows=table_counts, pending=[], errors=[],
                  diff=_diff(previous, rows, year), reused=False)
    if target.exists():
        # A valid bundle is insufficient if it belongs to another content ID;
        # the directory requested by this build must contain this exact review.
        existing, _ = validate_reviewed_bundle(target)
        if existing["build_id"] != manifest["build_id"]:
            raise ValueError("Existing reviewed build directory contains a different build ID")
        return {**report, "reused": True}
    output.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".calendar-review-", dir=output))
    try:
        for name, value in payloads.items():
            destination = temporary / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(value)
        (temporary / "calendar.csv").write_bytes(data)
        _write_json(temporary / "calendar_manifest.json", manifest)
        _write_json(temporary / "calendar_report.json", report)
        validate_reviewed_bundle(temporary)
        try:
            # Publish the local directory only after its complete file set has
            # passed the same validation used later by the database loader.
            temporary.rename(target)
        except OSError:
            # Another process may have completed the identical build. Reuse it
            # only after validation; never overwrite a conflicting directory.
            if not target.is_dir():
                raise
            existing, _ = validate_reviewed_bundle(target)
            if existing["build_id"] != manifest["build_id"]:
                raise ValueError("Concurrent reviewed build directory contains a different build ID")
            report["reused"] = True
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return report


def build_reviewed_calendar(source_dir, year, corrected_csv, review_file,
                            output=PROCESSED_DIR / "calendar", previous=None):
    """Build retained review evidence or save an actionable report-only failure.

    Args:
        source_dir: Intact official schema-3 snapshot with important_dates.csv
            and sources.json; the original rows may need a reviewed correction.
        year: Integer calendar year accepted by the normal calendar builder.
        corrected_csv: Separately edited CSV using the official source columns.
        review_file: Schema-1 JSON declaration with reviewer, reason, checked
            official evidence, exact CSV hashes and completeness attestation.
        output: Parent directory for immutable build-ID bundles and failures.
        previous: Optional validated bundle of the same year for a row diff.

    Returns:
        A report containing input_dir, build_id, coverage bounds, row counts,
        diff and reused. Status is READY only for verified full-year coverage;
        a shorter interval is READY_BOUNDED and requires backend-aware opt-in
        when subsequently passed to the calendar loader.

    Raises:
        ValueError: For validation or input failures, with the saved failure
            report path in the message when that report can be written.
        OSError: If the failure report itself cannot be saved.

    Side effects:
        Writes local bundles or a failed-*/calendar_report.json. Original inputs
        and existing bundles are not rewritten. Failed reviews produce no
        publishable manifest; callers may retain the report before deleting
        their temporary work directory. No network or database access occurs.
    """
    try:
        return _build_reviewed_calendar(source_dir, year, corrected_csv, review_file, output, previous)
    except (ValueError, OSError, UnicodeError, KeyError, TypeError, AttributeError, csv.Error) as error:
        output = Path(output)
        output.mkdir(parents=True, exist_ok=True)
        failed = output / ("failed-" + uuid.uuid4().hex)
        failed.mkdir()
        # Failures have unique report-only directories, never a content ID or
        # READY manifest that another command could mistake for a valid bundle.
        report_path = failed / "calendar_report.json"
        _write_json(report_path, {
            "schema_version": 4, "policy_version": POLICY_VERSION, "status": "ERROR",
            "year": year, "source_dir": str(Path(source_dir).resolve()),
            "pending": [], "errors": [str(error)], "error_type": type(error).__name__,
        })
        raise ValueError(f"{error}. Calendar review report: {report_path}") from error
