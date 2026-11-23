"""Fetch the raw Speed Zones and Speed Signs inputs for the offline pipeline.

The Transport Victoria CKAN catalog supplies an active GeoJSON resource for
each dataset. This stage saves speed-zones.geojson, speed-signs.geojson and
sources.json under data/raw by default. It does not filter features, build
speed rules, or connect to PostgreSQL; build.py consumes the downloaded files.
"""

import json
from datetime import datetime, timezone

# Third-party HTTP client used to fetch the catalog and download its files.
# Importing requests does not send a request; requests.get() below does.
import requests

# Keep downloaded files under this iteration's data/raw directory by default.
from iteration3_v1_pipeline.settings import RAW_DIR


def download(raw_dir=RAW_DIR):
    """Download the latest catalog-selected GeoJSON for each speed dataset.

    Resources are ordered by their last_modified/created timestamp text, then
    resource ID as a tie-breaker. Each completed .part file replaces its own
    destination. The two snapshots and sources.json are not one atomic bundle:
    a later failure can leave one refreshed file and a partial download.

    Args:
        raw_dir: pathlib.Path directory for raw snapshots and source metadata.

    Returns:
        Selected resource metadata keyed by dataset name, plus downloaded_at
        in UTC. The same dictionary is written to sources.json on success.

    Raises:
        ValueError: If a dataset has no eligible GeoJSON resource.
        Exception: Propagates HTTP, catalog payload, and filesystem failures.

    Side effects:
        Creates raw_dir, makes HTTP requests, and replaces local snapshot and
        metadata files. Existing snapshots are not versioned automatically.
    """
    # 1. Prepare the destination directory, including any missing parents.
    # Reusing an existing directory is allowed when refreshing the snapshots.
    raw_dir.mkdir(parents=True, exist_ok=True)
    # Collect source metadata so the saved files can be traced to their URLs.
    sources = {}
    # Process both datasets: zone geometry/limits and the supporting road signs.
    for name in ("speed-zones", "speed-signs"):
        # 2. Ask the official CKAN catalog for this dataset's resource list.
        # This response describes the available files; it is not the GeoJSON.
        response = requests.get(
            "https://opendata.transport.vic.gov.au/api/3/action/package_show",
            params={"id": name}, timeout=30)
        # Stop on an HTTP error before trying to interpret the response as JSON.
        response.raise_for_status()
        # CKAN nests the downloadable resource entries under result.resources.
        resources = response.json()["result"]["resources"]
        # 3. Keep active GeoJSON entries with a URL. max() chooses the entry with
        # the latest last_modified/created timestamp text, then resource ID to
        # break ties. No eligible entry causes ValueError instead of a guess.
        resource = max((r for r in resources if r.get("format", "").lower() == "geojson"
                        and r.get("state", "active") == "active" and r.get("url")),
                       key=lambda r: (r.get("last_modified") or r.get("created") or "", r["id"]))
        # 4. Use stable filenames that the later build command expects.
        # Write to .part first so a failed transfer leaves the previous final
        # file in place; an incomplete .part file may remain after failure.
        destination = raw_dir / f"{name}.geojson"
        partial = destination.with_suffix(".geojson.part")
        # 5. Download from the URL selected above. Streaming avoids loading the
        # entire GeoJSON into memory; the context manager closes the response.
        with requests.get(resource["url"], stream=True, timeout=300) as response:
            response.raise_for_status()
            # Binary mode preserves the downloaded bytes without decoding JSON.
            with partial.open("wb") as output:
                # Request chunks of up to 1 MiB and write each as it arrives.
                for chunk in response.iter_content(1024 * 1024):
                    output.write(chunk)
        # Publish this dataset only after its response and output file close.
        # Each dataset is replaced separately; this is not a two-file rollback.
        partial.replace(destination)
        # 6. Keep the source identity, URL and published reporting period.
        # This metadata records provenance; it does not filter source features.
        sources[name] = {key: resource.get(key) for key in (
            "id", "name", "url", "reporting_period_start", "reporting_period_end")}
    # 7. Once both downloads finish, record this run's completion time in UTC
    # and save a readable source record beside the raw GeoJSON files.
    # sources.json is written separately, so it is not atomic with the datasets.
    sources["downloaded_at"] = datetime.now(timezone.utc).isoformat()
    (raw_dir / "sources.json").write_text(json.dumps(sources, indent=2) + "\n")
    # The CLI prints this same metadata dictionary as its JSON result.
    return sources
