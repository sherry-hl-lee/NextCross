"""Download the original DataVic calendar CSV, without scraping HTML pages.

The catalogue API only discovers the current source CSV. Dates come from that
CSV's day/month/year text, never the DataStore API's converted timestamps.
Only the downloaded CSV supplies calendar dates; no locally maintained
holiday supplements are read or merged into the snapshot.

This acquisition stage checks file structure and records source provenance.
calendar.py later selects and validates one year's dates; neither stage loads
PostgreSQL. A saved snapshot contains the CSV and its sources.json together.
"""

import csv
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlparse
from uuid import uuid4

import requests

from iteration3_v1_pipeline.settings import RAW_DIR

# Keep the source key and filename consistent with the calendar build's inputs.
SOURCE_FILES = {"important_dates": "important_dates.csv"}
MAX_SOURCE_BYTES = 10 * 1024 * 1024
# Pin the official dataset identity, while discovering its current resource URL.
PACKAGE_ID = "a8bc540d-181b-4fb5-8110-5fe52a04f6a7"
PACKAGE_NAME = "government-dates-api-data"
METADATA_URL = f"https://discover.data.vic.gov.au/api/3/action/package_show?id={PACKAGE_NAME}"
CSV_COLUMNS = {"arun", "dateType", "name", "important_date", "publisher", "description", "source"}
_UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


def source_urls(year):
    """Validate the requested year and return the official catalogue URL mapping.

    The catalogue URL is shared across years. This function does not select
    annual rows or make an HTTP request; invalid years raise ValueError.
    """
    if type(year) is not int or not 1900 <= year <= 9998:
        raise ValueError("Calendar year must be an integer in 1900..9998")
    # The resource contains multiple years; year selection happens during build,
    # while acquisition always resolves the same official catalogue dataset.
    return {"important_dates": METADATA_URL}


def validate_csv_url(url, resource_id=None, package_id=PACKAGE_ID):
    """Return a CSV URL only when it matches the expected official identity.

    Require the DataVic HTTPS host, dataset path and CSV filename, plus a
    matching resource ID when supplied. Raise ValueError on disagreement.
    This validates the URL, not the file's contents, and performs no request.
    """
    if not isinstance(url, str):
        raise ValueError("important_dates: missing official CSV URL")
    parsed = urlparse(url)
    # Accept the dataset's UUID or stable name, but no unrelated resource path.
    match = re.fullmatch(
        rf"/dataset/({re.escape(PACKAGE_ID)}|{PACKAGE_NAME})/resource/({_UUID})/download/([A-Za-z0-9_.-]+\.csv)",
        parsed.path,
    )
    if (parsed.scheme != "https" or parsed.netloc != "discover.data.vic.gov.au"
            or parsed.query or parsed.fragment or not match or package_id != PACKAGE_ID
            or (resource_id is not None and match.group(2) != resource_id)):
        raise ValueError("important_dates: unexpected official CSV URL or resource identity")
    return url


def _utc_timestamp(value, now, label):
    """Normalize an ISO UTC string after rejecting missing, non-UTC or future time.

    now is the acquisition start time; label identifies the invalid argument
    in a ValueError. Return an ISO string with an explicit UTC offset.
    """
    if not isinstance(value, str):
        raise ValueError(f"{label} requires an ISO timestamp in UTC")
    try:
        stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO timestamp in UTC") from error
    if stamp.tzinfo is None or stamp.utcoffset().total_seconds() != 0 or stamp > now:
        raise ValueError(f"{label} must be a UTC timestamp that is not in the future")
    return stamp.isoformat()


def _read_bytes(path, label):
    """Read a nonempty local file after checking its reported size against 10 MiB.

    Return bytes; raise ValueError for an oversized or empty source and let
    filesystem errors propagate. No source file is modified.
    """
    # Apply the source-size policy to imported manifests as well as CSV files.
    if path.stat().st_size > MAX_SOURCE_BYTES:
        raise ValueError(f"{label}: source exceeds 10 MiB")
    data = path.read_bytes()
    if not data:
        raise ValueError(f"{label}: empty source")
    return data


def _get_bytes(url, *, kind, resource_id=None):
    """Fetch nonempty bytes after checking redirects, content type and size.

    kind is "csv" or "json". CSV redirects must retain the official resource
    identity; catalogue responses must retain the exact discovery URL. Reject
    unexpected responses or more than 10 MiB with ValueError; HTTP and transport
    errors propagate. This function performs HTTP requests but writes no files.
    """
    # Separate connection and read timeouts do not impose a total download limit.
    with requests.get(url, stream=True, timeout=(15, 60),
                      headers={"User-Agent": "iteration3-v1-calendar-pipeline/3.0"}) as response:
        response.raise_for_status()
        # Validate the final response URL too, so a redirect cannot change the source.
        if kind == "csv":
            validate_csv_url(response.url, resource_id)
            allowed = {"text/csv", "application/csv", "application/octet-stream", "text/plain"}
        else:
            if response.url != METADATA_URL:
                raise ValueError("important_dates: unexpected catalogue redirect")
            allowed = {"application/json"}
        content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if content_type not in allowed:
            raise ValueError(f"important_dates: expected a {kind.upper()} response")
        chunks, size = [], 0
        # Count received bytes rather than trusting an absent or incorrect header.
        # The accepted response is buffered for validation and hashing before saving.
        for chunk in response.iter_content(64 * 1024):
            size += len(chunk)
            if size > MAX_SOURCE_BYTES:
                raise ValueError("important_dates: source exceeds 10 MiB")
            chunks.append(chunk)
        data = b"".join(chunks)
        if not data:
            raise ValueError("important_dates: empty response")
        return data


def _select_resource(data):
    """Parse catalogue JSON bytes and return its unique newest active CSV resource.

    Validate the dataset and candidate identities before ranking parsed
    last_modified timestamps. Raise ValueError for malformed metadata, no
    eligible resource or a tie for newest; no files or network are accessed.
    """
    try:
        payload = json.loads(data)
        package = payload["result"]
        # A successful HTTP response alone does not establish the expected dataset.
        if (payload.get("success") is not True or package["id"] != PACKAGE_ID
                or package["name"] != PACKAGE_NAME or package["state"] != "active"):
            raise ValueError("important_dates: unexpected catalogue dataset")
        candidates = []
        for resource in package["resources"]:
            if resource.get("state") != "active" or resource.get("format", "").upper() != "CSV":
                continue
            identifier = resource["id"]
            if not re.fullmatch(_UUID, identifier):
                raise ValueError("important_dates: invalid resource ID")
            validate_csv_url(resource["url"], identifier, resource["package_id"])
            # CKAN uses timezone-less UTC for metadata timestamps. This field
            # ranks source revisions only; it is never interpreted as a holiday.
            modified = datetime.fromisoformat(resource["last_modified"].replace("Z", "+00:00"))
            if modified.tzinfo is None:
                modified = modified.replace(tzinfo=timezone.utc)
            candidates.append((modified, resource))
        if not candidates:
            raise ValueError("important_dates: catalogue has no active CSV resource")
        latest = max(stamp for stamp, _ in candidates)
        matches = [resource for stamp, resource in candidates if stamp == latest]
        # Refuse equal-date candidates instead of choosing an arbitrary resource ID.
        if len(matches) != 1:
            raise ValueError("important_dates: ambiguous latest CSV resource")
        return matches[0]
    except (KeyError, TypeError, AttributeError, json.JSONDecodeError) as error:
        raise ValueError("important_dates: invalid catalogue metadata") from error


def _check_csv(data):
    """Check CSV bytes for expected columns, consistent row widths and records.

    Return None on success; raise ValueError for invalid UTF-8 or CSV structure.
    This rejects an HTML response masquerading as CSV, but does not validate
    holiday dates or annual completeness; calendar.py owns those checks.
    """
    try:
        # utf-8-sig accepts a UTF-8 byte-order mark without adding it to the header.
        reader = csv.reader(io.StringIO(data.decode("utf-8-sig"), newline=""), strict=True)
        header = next(reader, [])
        # Column order may vary, but missing, repeated or extra columns are invalid.
        if len(header) != len(CSV_COLUMNS) or set(header) != CSV_COLUMNS:
            raise ValueError("important_dates: unexpected CSV header")
        count = 0
        for row in reader:
            if len(row) != len(header):
                raise ValueError("important_dates: malformed CSV row")
            count += 1
        if not count:
            raise ValueError("important_dates: CSV contains no records")
    except (UnicodeDecodeError, csv.Error) as error:
        raise ValueError("important_dates: invalid UTF-8 CSV") from error


def _import_sources(directory, year):
    """Validate a saved snapshot's provenance and return its source entries.

    directory must contain sources.json and its named CSV for the requested
    year. Check schema, filenames, hashes, byte counts and official identities.
    Raise ValueError for inconsistent metadata; file errors propagate. This
    reads local files only and does not recheck the live official source.
    """
    try:
        manifest = json.loads(_read_bytes(directory / "sources.json", "sources"))
        if manifest["schema_version"] != 3 or manifest["year"] != year:
            raise ValueError("Imported snapshot must use CSV-only schema version 3 and the requested year; download a new snapshot")
        entries = manifest["sources"]
        if set(entries) != set(SOURCE_FILES):
            raise ValueError("Imported snapshot has unexpected sources")
        for name, filename in SOURCE_FILES.items():
            entry = entries[name]
            if entry["file"] != filename:
                raise ValueError("Imported snapshot has unexpected source filenames")
            data = _read_bytes(directory / filename, name)
            # The manifest must describe these exact bytes before they can be reused.
            if entry["sha256"] != hashlib.sha256(data).hexdigest() or entry["bytes"] != len(data):
                raise ValueError(f"{name}: imported source hash or size mismatch")
        csv_entry = entries["important_dates"]
        if (csv_entry["metadata_url"] != METADATA_URL
                or not re.fullmatch(_UUID, csv_entry["resource_id"])):
            raise ValueError("important_dates: invalid imported catalogue identity")
        validate_csv_url(csv_entry["url"], csv_entry["resource_id"], csv_entry["package_id"])
        return entries
    except (KeyError, TypeError, AttributeError, json.JSONDecodeError) as error:
        raise ValueError("Invalid imported calendar snapshot manifest") from error


def download_calendar(year, output=RAW_DIR / "calendar", *, import_dir=None, verified_at=None):
    """Publish the official CSV and provenance together as one atomic snapshot.

    --import-dir reads an intact prior schema-3 snapshot and requires the
    operator's actual CSV source-check time. No additional dates are accepted
    from manually maintained files. Failed attempts preserve previous snapshots.

    Args:
        year: Integer year in 1900..9998, recorded for the later build stage.
        output: Parent directory for uniquely named snapshot directories.
        import_dir: Optional saved CSV-only snapshot; otherwise fetch via HTTP.
        verified_at: Actual UTC source-check timestamp, required only for imports.

    Returns:
        A dictionary with year, snapshot_id, source_dir and acquisition method.

    Raises:
        ValueError: Invalid arguments, source identity, metadata or CSV structure.
        Exception: Propagates HTTP, transport and filesystem failures.

    Side effects:
        Reads an imported snapshot or downloads official metadata and CSV bytes.
        Creates output and publishes one new CSV/manifest directory. Temporary
        files are cleaned up on failure; existing snapshots are not overwritten.
        Does not filter dates, validate a complete calendar year or write a DB.
    """
    # 1. Validate scope before acquisition. The shared CSV can contain several years.
    urls = source_urls(year)
    # fetched_at records this attempt's start, including when reusing local bytes.
    fetched_at = datetime.now(timezone.utc)
    imported = Path(import_dir) if import_dir is not None else None
    if imported is not None:
        # Copying an old snapshot is not a fresh official-source check: require
        # the operator's actual check time rather than using the import time.
        verified = _utc_timestamp(verified_at, fetched_at, "--import-dir requires --verified-at")
        imported_entries = _import_sources(imported, year)
    else:
        if verified_at is not None:
            raise ValueError("--verified-at is only used with --import-dir")
        verified = fetched_at.isoformat()
        imported_entries = None
    # 2. Stage under the output parent so the final directory rename stays on
    # the same filesystem. A timestamp plus random suffix identifies this attempt.
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    snapshot_id = fetched_at.strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid4().hex[:8]
    temporary = Path(tempfile.mkdtemp(prefix=".calendar-", dir=output))
    manifest = {"schema_version": 3, "year": year, "snapshot_id": snapshot_id,
                "fetched_at": fetched_at.isoformat(), "verified_at": verified, "sources": {}}
    try:
        # 3. Acquire the same CSV input through either verified import metadata
        # or catalogue discovery; imports make no HTTP requests.
        if imported_entries is not None:
            original = imported_entries["important_dates"]
            csv_data = _read_bytes(imported / SOURCE_FILES["important_dates"], "important_dates")
            resource = {"url": original["url"], "id": original["resource_id"],
                        "package_id": original["package_id"], "last_modified": original["last_modified"]}
            acquisition = "local_csv"
        else:
            resource = _select_resource(_get_bytes(urls["important_dates"], kind="json"))
            csv_data = _get_bytes(resource["url"], kind="csv", resource_id=resource["id"])
            acquisition = "http"
        # Both routes must pass structural checks before any snapshot is published.
        _check_csv(csv_data)
        payloads = {"important_dates": csv_data}
        manifest["sources"] = {
            "important_dates": {"url": resource["url"], "resource_id": resource["id"],
                                "package_id": resource["package_id"], "metadata_url": METADATA_URL,
                                "last_modified": resource["last_modified"],
                                "acquisition": acquisition, "verified_at": verified},
        }
        for name, data in payloads.items():
            filename = SOURCE_FILES[name]
            (temporary / filename).write_bytes(data)
            # Hash the saved payload bytes so later stages can detect changes.
            manifest["sources"][name].update(file=filename, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
        (temporary / "sources.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        destination = output / snapshot_id
        # 4. Publish CSV and provenance as one directory, after both are complete.
        temporary.rename(destination)
        return {"year": year, "snapshot_id": snapshot_id,
                "source_dir": str(destination.resolve()), "acquisition": acquisition}
    finally:
        # Successful rename removes the temporary path; failures remove only this
        # attempt's staging directory, leaving previously published snapshots intact.
        if temporary.exists():
            shutil.rmtree(temporary)
