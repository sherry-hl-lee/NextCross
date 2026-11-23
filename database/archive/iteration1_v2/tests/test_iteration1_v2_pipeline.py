"""Focused tests for the streamlined Iteration 1 v2 pipeline.

Fixtures use temporary plain GeoJSON files and in-memory database doubles, so
this suite does not contact Transport Victoria or PostgreSQL.
"""

from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import ijson


ITERATION_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ITERATION_DIR / "src"))

from iteration1_v2_pipeline.download import (  # noqa: E402
    download_speed_zones,
    select_latest_geojson,
)
from iteration1_v2_pipeline.load import replace_road_speeds  # noqa: E402
from iteration1_v2_pipeline.lookup import lookup_speed  # noqa: E402
from iteration1_v2_pipeline.migrate import apply_migration  # noqa: E402
from iteration1_v2_pipeline.settings import MIGRATION_FILE  # noqa: E402
from iteration1_v2_pipeline.speed_zones import (  # noqa: E402
    Counts,
    iter_road_speeds,
)
from iteration1_v2_pipeline.validate import validate_raw  # noqa: E402


FIRST_ID = "11111111-1111-4111-8111-111111111111"
SECOND_ID = "22222222-2222-4222-8222-222222222222"


def feature(
    feature_id: str = FIRST_ID,
    *,
    speed: str = "60",
    conditions: object = "Normal Operation",
    geometry_type: str | None = "LineString",
) -> dict[str, object]:
    """Build one small Speed Zones feature for parser and loader tests."""

    if geometry_type == "LineString":
        geometry: object = {
            "type": "LineString",
            "coordinates": [[144.0, -37.0, 5], [144.1, -37.1, 6]],
        }
    elif geometry_type == "MultiLineString":
        geometry = {
            "type": "MultiLineString",
            "coordinates": [
                [[144.0, -37.0], [144.1, -37.1]],
                [[144.2, -37.2], [144.3, -37.3]],
            ],
        }
    elif geometry_type is None:
        geometry = None
    else:
        geometry = {"type": geometry_type, "coordinates": [144.0, -37.0]}
    return {
        "type": "Feature",
        "id": feature_id,
        "properties": {
            "speed_limit": speed,
            "zone_conditions": conditions,
        },
        "geometry": geometry,
    }


def write_geojson(directory: Path, features: list[dict[str, object]]) -> Path:
    """Write a plain FeatureCollection fixture and return its path."""

    path = directory / "speed-zones.geojson"
    path.write_text(
        json.dumps({"type": "FeatureCollection", "features": features}),
        encoding="utf-8",
    )
    return path


class FakeResponse:
    """Provide the small requests.Response surface used by the downloader."""

    def __init__(self, *, payload: object = None, chunks: tuple[bytes, ...] = ()):
        """Store optional JSON metadata and streamed byte chunks."""

        self.payload = payload
        self.chunks = list(chunks)

    def __enter__(self):
        """Return the response for a requests-style context manager."""

        return self

    def __exit__(self, *args: object):
        """Close the fake context without suppressing exceptions."""

        del args
        return None

    def raise_for_status(self) -> None:
        """Model a successful HTTP status by doing nothing."""

    def json(self) -> object:
        """Return the configured CKAN metadata payload."""

        return self.payload

    def iter_content(self, chunk_size: int):
        """Yield configured file bytes using the downloader's stream API."""

        del chunk_size
        yield from self.chunks


class FakeCopy:
    """Capture rows written through psycopg's COPY context."""

    def __init__(self, events: list[object]):
        """Attach COPY lifecycle events to a shared list."""

        self.events = events
        self.rows: list[tuple[object, ...]] = []

    def __enter__(self):
        """Record the start of COPY and return the row sink."""

        self.events.append("copy-start")
        return self

    def __exit__(self, *args: object):
        """Record COPY completion without suppressing errors."""

        del args
        self.events.append("copy-end")
        return None

    def write_row(self, row: tuple[object, ...]) -> None:
        """Capture one database row emitted by the loader."""

        self.rows.append(row)


class FakeCursor:
    """Capture loader SQL and expose one shared COPY sink."""

    def __init__(self, events: list[object]):
        """Create the cursor and its COPY recorder."""

        self.events = events
        self.copy_handle = FakeCopy(events)

    def __enter__(self):
        """Record cursor entry and return this cursor."""

        self.events.append("cursor-enter")
        return self

    def __exit__(self, *args: object):
        """Record cursor exit without suppressing errors."""

        del args
        self.events.append("cursor-exit")
        return None

    def execute(self, sql: str, parameters: object = None):
        """Capture normalized SQL executed by migration or load code."""

        self.events.append(("execute", " ".join(sql.split()), parameters))
        return self

    def copy(self, sql: str):
        """Capture the COPY statement and return its row sink."""

        self.events.append(("copy", " ".join(sql.split())))
        return self.copy_handle


class FakeConnection:
    """Model psycopg commit-or-rollback behavior at context exit."""

    def __init__(self):
        """Create a shared event list and cursor."""

        self.events: list[object] = []
        self.cursor_handle = FakeCursor(self.events)

    def __enter__(self):
        """Record transaction entry and return the connection."""

        self.events.append("connection-enter")
        return self

    def __exit__(self, exc_type: object, *args: object):
        """Record whether context exit represents commit or rollback."""

        del args
        self.events.append("rollback" if exc_type else "commit")
        return None

    def cursor(self):
        """Return the stable fake cursor used by the loader."""

        return self.cursor_handle

    def execute(self, sql: str):
        """Capture connection-level migration SQL."""

        return self.cursor_handle.execute(sql)


def fake_psycopg(connection: FakeConnection) -> types.ModuleType:
    """Build an importable psycopg module that returns one fake connection."""

    module = types.ModuleType("psycopg")

    def connect(database_url: str):
        """Record the URL placeholder and return the prepared connection."""

        connection.events.append(("connect", database_url))
        return connection

    module.connect = connect
    return module


class LookupCursor:
    """Capture a parameterized lookup and return a configured row."""

    def __init__(self, row: tuple[int] | None):
        """Store the row and initialize call history."""

        self.row = row
        self.calls: list[tuple[str, dict[str, float]]] = []

    def execute(self, sql: str, parameters: dict[str, float]) -> None:
        """Capture lookup SQL and its bound values."""

        self.calls.append((sql, parameters))

    def fetchone(self) -> tuple[int] | None:
        """Return the configured nearest-speed result."""

        return self.row


class Iteration1V2Tests(unittest.TestCase):
    """Verify the compact v2 contract without network or PostgreSQL."""

    def test_select_latest_active_geojson(self) -> None:
        """Choose the newest active GeoJSON and ignore unrelated resources."""

        selected = select_latest_geojson(
            [
                {
                    "id": "old",
                    "format": "GeoJSON",
                    "state": "active",
                    "last_modified": "2026-01-01T00:00:00",
                    "url": "https://example.test/old",
                },
                {
                    "id": "csv",
                    "format": "CSV",
                    "state": "active",
                    "last_modified": "2026-12-01T00:00:00",
                    "url": "https://example.test/file.csv",
                },
                {
                    "id": "new",
                    "format": "GeoJSON",
                    "state": "active",
                    "last_modified": "2026-08-01T00:00:00",
                    "url": "https://example.test/new",
                },
            ]
        )
        self.assertEqual(selected["id"], "new")

    def test_download_uses_one_part_file_then_replaces_destination(self) -> None:
        """Download CKAN bytes into the fixed v2 raw path atomically."""

        metadata = {
            "result": {
                "resources": [
                    {
                        "id": "geojson",
                        "format": "GeoJSON",
                        "state": "active",
                        "url": "https://example.test/speed-zones.geojson",
                    }
                ]
            }
        }
        responses = iter(
            [FakeResponse(payload=metadata), FakeResponse(chunks=(b'{"features":[]}',))]
        )

        def http_get(*args: object, **kwargs: object) -> FakeResponse:
            """Return metadata first and raw content second."""

            del args, kwargs
            return next(responses)

        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "raw" / "speed-zones.geojson"
            result = download_speed_zones(destination, http_get=http_get)
            self.assertEqual(result, destination)
            self.assertEqual(destination.read_bytes(), b'{"features":[]}')
            self.assertFalse(destination.with_suffix(".geojson.part").exists())

    def test_ijson_streams_and_applies_the_static_filter(self) -> None:
        """Accept both condition forms and collapse rejected reasons."""

        fixtures = [
            feature(conditions=["Normal Operation"]),
            feature(
                SECOND_ID,
                conditions="Normal Operation",
                geometry_type="MultiLineString",
            ),
            feature(speed="0"),
            feature(conditions="School Times"),
            feature(geometry_type=None),
            feature("not-a-uuid"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = write_geojson(Path(directory), fixtures)
            counts = Counts()
            rows = list(iter_road_speeds(path, counts))

        self.assertEqual(counts, Counts(seen=6, accepted=2, skipped=4))
        self.assertEqual([row.speed_kmh for row in rows], [60, 60])
        self.assertTrue(
            rows[0].geometry_ewkt.startswith("SRID=4326;MULTILINESTRING")
        )
        self.assertNotIn(" 5", rows[0].geometry_ewkt)

    def test_malformed_json_is_left_to_ijson(self) -> None:
        """Propagate ijson's parser error instead of custom grammar errors."""

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "broken.geojson"
            path.write_bytes(b'{"features":[{"id":"unfinished"}')
            with self.assertRaises(ijson.JSONError):
                list(iter_road_speeds(path))

    def test_selection_matches_storage_geometry_rules(self) -> None:
        """Keep condition normalization and coordinate rules aligned."""

        accepted = feature(conditions=["Normal Operation", ""])
        string_coordinate = feature()
        string_coordinate["geometry"] = {
            "type": "LineString",
            "coordinates": [["144.0", -37.0], [144.1, -37.1]],
        }
        out_of_range = feature()
        out_of_range["geometry"] = {
            "type": "LineString",
            "coordinates": [[181.0, -37.0], [144.1, -37.1]],
        }
        repeated = feature()
        repeated["geometry"] = {
            "type": "LineString",
            "coordinates": [[144.0, -37.0], [144.0, -37.0]],
        }

        with tempfile.TemporaryDirectory() as directory:
            path = write_geojson(
                Path(directory),
                [accepted, string_coordinate, out_of_range, repeated],
            )
            counts = Counts()
            rows = list(iter_road_speeds(path, counts))

        self.assertEqual(counts, Counts(seen=4, accepted=1, skipped=3))
        self.assertEqual(len(rows), 1)

    def test_validate_rejects_a_file_with_no_accepted_rows(self) -> None:
        """Keep the minimal nonempty guard used before manual decisions."""

        with tempfile.TemporaryDirectory() as directory:
            path = write_geojson(Path(directory), [feature(speed="0")])
            with self.assertRaisesRegex(ValueError, "No usable"):
                validate_raw(path)

    def test_matching_expected_count_commits_the_load(self) -> None:
        """Commit when the one-pass accepted count matches its positive guard."""

        connection = FakeConnection()
        with tempfile.TemporaryDirectory() as directory:
            path = write_geojson(Path(directory), [feature()])
            with patch.dict(sys.modules, {"psycopg": fake_psycopg(connection)}):
                counts = replace_road_speeds(
                    "postgresql://example",
                    path,
                    expected_accepted=1,
                )

        self.assertEqual(counts, Counts(seen=1, accepted=1, skipped=0))
        self.assertEqual(connection.events.count("commit"), 1)
        self.assertNotIn("rollback", connection.events)
        self.assertEqual(len(connection.cursor_handle.copy_handle.rows), 1)
        executed = [
            event[1]
            for event in connection.events
            if isinstance(event, tuple) and event[0] == "execute"
        ]
        self.assertIn("TRUNCATE TABLE iteration1_v2.road_speed", executed)
        self.assertIn("ANALYZE iteration1_v2.road_speed", executed)

    def test_mismatched_expected_count_rolls_back_the_load(self) -> None:
        """Rollback TRUNCATE and COPY when the one-pass count is unexpected."""

        connection = FakeConnection()
        with tempfile.TemporaryDirectory() as directory:
            path = write_geojson(Path(directory), [feature()])
            with patch.dict(sys.modules, {"psycopg": fake_psycopg(connection)}):
                with self.assertRaisesRegex(ValueError, "Expected 2.*COPY accepted 1"):
                    replace_road_speeds(
                        "postgresql://example",
                        path,
                        expected_accepted=2,
                    )

        self.assertIn("rollback", connection.events)
        self.assertNotIn("commit", connection.events)
        self.assertEqual(len(connection.cursor_handle.copy_handle.rows), 1)

    def test_nonpositive_expected_count_is_rejected_before_connecting(self) -> None:
        """Reject zero and negative guards before opening PostgreSQL."""

        for expected in (0, -1):
            with self.subTest(expected=expected):
                connection = FakeConnection()
                with tempfile.TemporaryDirectory() as directory:
                    path = write_geojson(Path(directory), [feature()])
                    with patch.dict(
                        sys.modules,
                        {"psycopg": fake_psycopg(connection)},
                    ):
                        with self.assertRaisesRegex(ValueError, "positive"):
                            replace_road_speeds(
                                "postgresql://example",
                                path,
                                expected_accepted=expected,
                            )
                self.assertEqual(connection.events, [])

    def test_empty_load_rolls_back_without_an_expected_count(self) -> None:
        """Prevent committing an empty replacement when no count is supplied."""

        connection = FakeConnection()
        with tempfile.TemporaryDirectory() as directory:
            path = write_geojson(Path(directory), [feature(speed="0")])
            with patch.dict(sys.modules, {"psycopg": fake_psycopg(connection)}):
                with self.assertRaisesRegex(ValueError, "empty"):
                    replace_road_speeds("postgresql://example", path)
        self.assertIn("rollback", connection.events)

    def test_migration_executes_the_existing_v2_contract(self) -> None:
        """Apply the v2 SQL filename with simplification and two MVP tables."""

        self.assertEqual(MIGRATION_FILE.name, "001_initial_schema.sql")
        connection = FakeConnection()
        with patch.dict(sys.modules, {"psycopg": fake_psycopg(connection)}):
            apply_migration("postgresql://example")
        sql = next(
            event[1]
            for event in connection.events
            if isinstance(event, tuple) and event[0] == "execute"
        )
        self.assertIn("iteration1_v2.road_speed", sql)
        self.assertIn("iteration1_v2.notification_event", sql)
        self.assertIn("road_speed_geom_nonempty_ck", sql)
        self.assertIn("ST_SimplifyPreserveTopology", sql)
        self.assertIn("ST_Transform(NEW.road_geom::geometry, 7899)", sql)
        self.assertIn("ST_Multi", sql)
        self.assertIn("ST_IsValid", sql)
        self.assertIn("1.0", sql)
        self.assertIn("road_speed_simplify_before_write", sql)

    def test_lookup_binds_coordinates_and_returns_optional_speed(self) -> None:
        """Use bound GPS values and preserve the no-nearby-road result."""

        cursor = LookupCursor((60,))
        self.assertEqual(
            lookup_speed(
                cursor,
                latitude=-37.8136,
                longitude=144.9631,
                radius_m=30,
            ),
            60,
        )
        self.assertEqual(
            cursor.calls[0][1],
            {"latitude": -37.8136, "longitude": 144.9631, "radius_m": 30},
        )
        self.assertIn("roads.source_speed_zone_id", cursor.calls[0][0])
        self.assertIsNone(
            lookup_speed(
                LookupCursor(None),
                latitude=-37.8136,
                longitude=144.9631,
            )
        )


if __name__ == "__main__":
    unittest.main()
