"""Download the current Speed Zones GeoJSON into one v2-owned raw file."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from pathlib import Path
from typing import Any

import requests

from iteration1_v2_pipeline.settings import CKAN_API_URL, RAW_FILE


HttpGet = Callable[..., Any]


def select_latest_geojson(
    resources: Iterable[Mapping[str, Any]],
) -> dict[str, Any]:
    """Return the newest active CKAN resource labelled as GeoJSON.

    The upstream ISO timestamp is compared as text because CKAN publishes it in
    a sortable form. The resource ID is only a deterministic tie-breaker.

    Args:
        resources: Resource objects from a CKAN ``package_show`` response.

    Returns:
        A copy of the selected resource object.

    Raises:
        ValueError: If no active GeoJSON resource with a URL is available.
    """

    candidates = [
        resource
        for resource in resources
        if str(resource.get("format", "")).strip().casefold() == "geojson"
        and str(resource.get("state", "active")).strip().casefold() == "active"
        and resource.get("url")
    ]
    if not candidates:
        raise ValueError("CKAN returned no active GeoJSON resource")
    return dict(
        max(
            candidates,
            key=lambda resource: (
                str(resource.get("last_modified") or resource.get("created") or ""),
                str(resource.get("id") or ""),
            ),
        )
    )


def download_speed_zones(
    destination: Path = RAW_FILE,
    *,
    http_get: HttpGet = requests.get,
) -> Path:
    """Discover and download the latest Speed Zones GeoJSON file.

    A temporary ``.part`` file is replaced atomically after a successful HTTP
    stream. There is intentionally no retry, resume, manifest, or checksum.

    Args:
        destination: V2-owned path that should hold the completed GeoJSON.
        http_get: Requests-compatible GET callable, injectable for tests.

    Returns:
        The completed destination path.

    Raises:
        Exception: Propagates HTTP, CKAN payload, and filesystem failures.

    Side effects:
        Performs two HTTP requests and replaces ``destination`` on success.
    """

    with http_get(CKAN_API_URL, timeout=30) as response:
        response.raise_for_status()
        resources = response.json()["result"]["resources"]
    selected = select_latest_geojson(resources)

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(f"{destination.suffix}.part")
    try:
        with http_get(selected["url"], stream=True, timeout=300) as response:
            response.raise_for_status()
            with partial.open("wb") as output:
                for chunk in response.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        output.write(chunk)
        partial.replace(destination)
    except Exception:
        partial.unlink(missing_ok=True)
        raise
    return destination
