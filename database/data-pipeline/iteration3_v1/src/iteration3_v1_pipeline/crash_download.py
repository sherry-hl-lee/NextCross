"""Acquire official crash CSV inputs for the offline data pipeline.

The two configured Victorian transport resource IDs supply the crash snapshot
and Accident Location labels consumed by ``crash.build_crashes``. Each download
records the source metadata, retrieval time, byte count, and SHA-256 digest in
a sidecar JSON. This stage neither filters records nor accesses the database;
it is not part of backend request handling.
"""

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

# HTTP requests start inside download_crashes(), not when this module is imported.
import requests

from iteration3_v1_pipeline.settings import RAW_DIR


# Pin catalogue identities, not download URLs: CKAN can move the file behind
# a resource. Selecting the accepted five-year cohort happens during the build.
RESOURCES = {"crashes": "5df1f373-0c90-48f5-80e1-7b2a35507134",
             "accident-location": "e1beb92b-5836-448a-9769-e7aa6a9e7413"}


def download_crashes(raw_dir=RAW_DIR):
    """Download both official resources and record their provenance locally.

    Args:
        raw_dir: Directory for ``crashes.csv``, ``accident-location.csv``, and
            the corresponding ``*.source.json`` metadata files.

    Returns:
        A mapping from resource name to the metadata written to its sidecar.

    Raises:
        requests.RequestException: If resource lookup or streaming download
            fails, times out, or returns an unsuccessful HTTP status.
        Exception: Propagates metadata-decoding, missing-field, and file errors.

    Side effects:
        Sends HTTP GET requests, creates the destination directory, and replaces
        completed files through ``.part`` renames. Publication is per file:
        neither the CSV/sidecar pair nor the two-resource set is one atomic
        snapshot. A later failure may leave earlier downloads published and
        incomplete temporary files behind. Source content is not validated here;
        the build stage checks the CSV contract and hashes its actual inputs.
    """
    raw_dir = Path(raw_dir)
    raw_dir.mkdir(parents=True, exist_ok=True)
    # Fetch accident facts and location labels separately; the build joins them
    # by accident ID after both local CSVs are available.
    sources = {}
    for name, resource_id in RESOURCES.items():
        # Resolve the current download URL from the stable official resource ID.
        response = requests.get("https://opendata.transport.vic.gov.au/api/3/action/resource_show",
                                params={"id": resource_id}, timeout=30)
        # Check HTTP status before reading CKAN's resource metadata as JSON.
        response.raise_for_status()
        resource = response.json()["result"]
        target = raw_dir / f"{name}.csv"
        # Keep the previous completed CSV available while its replacement streams.
        part = target.with_suffix(".csv.part")
        # This checksum identifies the downloaded content, even if the same
        # resource ID publishes different bytes on a later run.
        digest = hashlib.sha256()
        with requests.get(resource["url"], stream=True, timeout=300) as response:
            response.raise_for_status()
            with part.open("wb") as stream:
                # Hash the same bytes written to disk without loading the full CSV.
                for block in response.iter_content(1024 * 1024):
                    stream.write(block)
                    digest.update(block)
        # Preserve both upstream metadata and facts about this particular download.
        metadata = {"resource_page": f"https://opendata.transport.vic.gov.au/dataset/victoria-road-crash-data/resource/{resource_id}",
                    "resource": {k: resource.get(k) for k in ("id", "name", "url", "last_modified", "reporting_period_start", "reporting_period_end")},
                    "downloaded_at": datetime.now(timezone.utc).isoformat(),
                    "bytes": part.stat().st_size, "sha256": digest.hexdigest()}
        source_path = raw_dir / f"{name}.source.json"
        source_part = source_path.with_suffix(".json.part")
        source_part.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        # Publish the CSV and its sidecar separately; this is not a two-file transaction.
        part.replace(target)
        source_part.replace(source_path)
        sources[name] = metadata
    # Return each dataset's provenance for the CLI's JSON output.
    return sources
