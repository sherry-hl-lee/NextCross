"""Repository orchestration tests; these do not simulate PostGIS itself."""
from datetime import date
import os
import pytest
from fastapi.testclient import TestClient
from app import historical_routes
from app.main import app
from app.historical_schemas import HistoricalCrashQuery
from app.historical_service import (
    BUILD_SQL, EVENTS_SQL, lookup_with_connection, normalize_response, call_lookup,
)


BUILD = dict(build_id="build-test", start_date=date(2021, 1, 1),
    end_date=date(2025, 12, 31), region="Victoria", cohort_policy="verified cohort",
    eps_m=100, min_samples=3, crs_epsg=3111, time_policy_version="v1",
    cluster_policy_version="v1", event_rows=100, hotspot_rows=10, missing_point_rows=2)


def query(**kwargs):
    return HistoricalCrashQuery.model_validate(dict(latitude=-37.8136,
        longitude=144.9631, radiusMeters=1000, timePeriod="night") | kwargs)


class Connection:
    def __init__(self, builds=None, events=None):
        self.builds = [BUILD] if builds is None else builds
        self.events = events or []
        self.calls = []
    def cursor(self): return self
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def execute(self, sql, params=None): self.calls.append((sql, params))
    def fetchall(self):
        return self.builds if self.calls[-1][0] == BUILD_SQL else self.events


def event(identity="e1", hotspot="h1", rank=1, inside=True):
    return dict(accident_id=identity, hotspot_id=hotspot, joined_hotspot_id=hotspot,
        crash_date=date(2023, 6, 15), time_period="night", location_label="Member",
        latitude=-37.8135, longitude=144.9632, fatalities=0, serious_injuries=1,
        representative_label="Representative", representative_latitude=-37.82,
        representative_longitude=144.96, representative_inside=inside, member_rank=rank)


def test_default_dates_and_read_snapshot():
    conn = Connection()
    result = lookup_with_connection(conn, query())
    assert "REPEATABLE READ READ ONLY" in conn.calls[0][0]
    assert conn.calls[-1][1]["start_date"] == date(2021, 1, 1)
    assert conn.calls[-1][1]["end_date"] == date(2025, 12, 31)
    assert conn.calls[-1][1]["build_id"] == "build-test"
    assert result["status"] == "no_recorded_crashes"
    assert result["totals"] == dict(crash_count=0, fatalities=0, serious_injuries=0)
    assert result["dataset"]["unlocatedEventRows"] == 2


@pytest.mark.parametrize("kwargs,start,end", [
    ({"startDate":"2022-01-01"}, date(2022,1,1), date(2025,12,31)),
    ({"endDate":"2024-12-31"}, date(2021,1,1), date(2024,12,31)),
    ({"startDate":"2023-01-01", "endDate":"2023-01-01"}, date(2023,1,1), date(2023,1,1)),
])
def test_omitted_endpoints_and_inclusive_same_day(kwargs, start, end):
    conn = Connection()
    result = lookup_with_connection(conn, query(**kwargs))
    assert conn.calls[-1][1]["start_date"] == start
    assert conn.calls[-1][1]["end_date"] == end
    # periodStart/End remain dataset boundaries, not requested filter boundaries.
    assert result["period_start"] == date(2021,1,1)


@pytest.mark.parametrize("kwargs", [
    {"startDate":"2020-12-31"}, {"endDate":"2026-01-01"},
    {"startDate":"2026-01-01"}, {"endDate":"2020-12-31"},
])
def test_any_outside_endpoint_has_no_counts_or_event_query(kwargs):
    conn = Connection()
    result = lookup_with_connection(conn, query(**kwargs))
    assert result["status"] == "outside_dataset_period"
    assert result["totals"] is None
    assert not any(sql == EVENTS_SQL for sql, _ in conn.calls)


def test_absent_build_is_distinct_from_zero_matches():
    result = lookup_with_connection(Connection(builds=[]), query())
    assert result["status"] == "no_dataset"
    assert result["dataset"] is None and result["totals"] is None


def test_one_and_two_member_clusters_retained_and_totals_partition():
    rows = [event("e1","h1"), event("e2","h2"), event("e3","h2",2), event("e4",None)]
    result = lookup_with_connection(Connection(events=rows), query())
    assert [c["crash_count"] for c in result["clusters"]] == [1,2]
    assert result["totals"]["crash_count"] == 4
    assert len(result["individual_points"]) == 1
    assert result["totals"]["serious_injuries"] == 4
    wire = normalize_response(result, query()).model_dump(by_alias=True)
    assert wire["clusters"][0]["hotspotId"] == "h1"
    assert wire["clusters"][0]["representativeLatitude"] == -37.82


def test_display_point_uses_ranked_member_when_representative_outside():
    far = event("a", rank=2, inside=False)
    nearest = event("z", rank=1, inside=False)
    nearest["latitude"] = -37.8134
    result = lookup_with_connection(Connection(events=[far,nearest]), query())
    cluster = result["clusters"][0]
    assert cluster["display_point_source"] == "in_area_member"
    assert cluster["latitude"] == nearest["latitude"]
    assert cluster["location_label"] == "Member"


def test_invalid_database_response_is_server_error(monkeypatch, tmp_path):
    monkeypatch.delenv("RUNTIME_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "notifications.db"))
    monkeypatch.setattr(historical_routes, "call_lookup", lambda q: {"status":"recorded"})
    with TestClient(app) as client:
        response = client.get("/api/v1/historical-crash-context", params={
            "latitude":-37.8,"longitude":144.9,"radiusMeters":1000,"timePeriod":"night"})
    assert response.status_code == 503


def test_unconfigured_database_fails_instead_of_mock(monkeypatch):
    for name in ("HISTORICAL_DATABASE_URL", "RUNTIME_DATABASE_URL", "DATABASE_URL", "HISTORICAL_CRASH_USE_MOCK"):
        monkeypatch.delenv(name, raising=False)
    with pytest.raises(RuntimeError, match="No database"):
        call_lookup(query())


@pytest.mark.skipif(os.getenv("NEXTCROSS_RUN_NEON_CHECK") != "1", reason="Explicit live read-only check not enabled")
def test_live_neon_reference(monkeypatch):
    # Uses a configured backend-role URL. No migrations, inserts, or admin login.
    monkeypatch.setenv("HISTORICAL_CRASH_USE_MOCK", "false")
    result = normalize_response(call_lookup(query()), query())
    assert result.totals.crash_count == 51
    assert result.totals.fatalities == 0
    assert result.totals.serious_injuries == 11
    assert len(result.clusters) == 17
    assert len(result.individual_points) == 17
    assert sum(c.crash_count for c in result.clusters) + len(result.individual_points) == 51
