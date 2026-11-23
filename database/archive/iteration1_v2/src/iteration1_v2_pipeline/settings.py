"""Paths and fixed source settings owned by Iteration 1 v2."""

from pathlib import Path


ITERATION_DIR = Path(__file__).resolve().parents[2]
RAW_FILE = ITERATION_DIR / "data" / "raw" / "speed-zones.geojson"
MIGRATION_FILE = (
    ITERATION_DIR / "postgresql" / "migrations" / "001_initial_schema.sql"
)

CKAN_API_URL = (
    "https://opendata.transport.vic.gov.au/api/3/action/"
    "package_show?id=speed-zones"
)
NORMAL_OPERATION = "Normal Operation"
