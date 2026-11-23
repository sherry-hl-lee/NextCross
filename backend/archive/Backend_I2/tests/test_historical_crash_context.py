import os

from fastapi.testclient import TestClient

from app.main import app
from app import historical_routes


BASE_QUERY = {
    "latitude": -37.8136,
    "longitude": 144.9631,
    "radiusMeters": 4500,
    "timePeriod": "night",
}


def recorded_result(**kwargs):
    return {
        "status": "recorded",
        "time_period": kwargs["time_period"],
        "build_id": "test-build",
        "period_start": "2021-01-01",
        "period_end": "2025-12-31",
        "count_scope": "Counts are based on actual crash points inside the circle.",
        "area": {
            "kind": "circle",
            "latitude": kwargs["latitude"],
            "longitude": kwargs["longitude"],
            "radius_meters": kwargs["radius_m"],
        },
        "totals": {"crash_count": 4, "fatalities": 0, "serious_injuries": 2},
        "clusters": [
            {
                "hotspot_id": "cluster-1",
                "latitude": -37.81,
                "longitude": 144.96,
                "location_label": "Test location",
                "crash_count": 3,
                "fatalities": 0,
                "serious_injuries": 1,
                "display_point_source": "cluster_representative",
            }
        ],
        "individual_points": [
            {
                "accident_id": "T202300123",
                "crash_date": "2023-06-15",
                "time_period": "night",
                "latitude": -37.82,
                "longitude": 144.97,
                "location_label": "Test street",
                "crash_count": 1,
                "fatalities": 0,
                "serious_injuries": 1,
            }
        ],
    }


def configure_database(tmp_path):
    os.environ.pop("RUNTIME_DATABASE_URL", None)
    os.environ.pop("DATABASE_URL", None)
    os.environ["DATABASE_PATH"] = str(tmp_path / "events.db")


def test_android_get_contract_and_camel_case(monkeypatch, tmp_path):
    configure_database(tmp_path)
    monkeypatch.setattr(historical_routes, "call_lookup", lambda query: recorded_result(
        latitude=query.latitude,
        longitude=query.longitude,
        radius_m=query.radius_meters,
        time_period=query.time_period,
    ))
    with TestClient(app) as client:
        response = client.get("/api/v1/historical-crash-context", params=BASE_QUERY)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "recorded"
    assert body["timePeriod"] == "night"
    assert body["area"] == {
        "kind": "circle",
        "latitude": -37.8136,
        "longitude": 144.9631,
        "radiusMeters": 4500.0,
    }
    assert body["totals"]["crashCount"] == 4
    assert body["clusters"][0]["hotspotId"] == "cluster-1"
    assert body["individualPoints"][0]["accidentId"] == "T202300123"


def test_optional_dates_are_forwarded(monkeypatch, tmp_path):
    configure_database(tmp_path)
    captured = {}

    def fake(query):
        captured["start"] = query.start_date.isoformat()
        captured["end"] = query.end_date.isoformat()
        return {
            "status": "no_recorded_crashes",
            "time_period": query.time_period,
            "period_start": captured["start"],
            "period_end": captured["end"],
            "area": {
                "latitude": query.latitude,
                "longitude": query.longitude,
                "radius_meters": query.radius_meters,
            },
            "totals": {"crash_count": 0, "fatalities": 0, "serious_injuries": 0},
            "clusters": [],
            "individual_points": [],
        }

    monkeypatch.setattr(historical_routes, "call_lookup", fake)
    params = dict(BASE_QUERY, startDate="2022-01-01", endDate="2024-12-31")
    with TestClient(app) as client:
        response = client.get("/api/v1/historical-crash-context", params=params)

    assert response.status_code == 200
    assert captured == {"start": "2022-01-01", "end": "2024-12-31"}
    assert response.json()["area"]["kind"] == "circle"


def test_validation_failures_are_422(monkeypatch, tmp_path):
    configure_database(tmp_path)
    monkeypatch.setattr(historical_routes, "call_lookup", lambda query: {})
    invalid_queries = [
        {key: value for key, value in BASE_QUERY.items() if key != "timePeriod"},
        dict(BASE_QUERY, timePeriod="afternoon"),
        dict(BASE_QUERY, radiusMeters=0),
        dict(BASE_QUERY, latitude=91),
        dict(BASE_QUERY, startDate="2025-01-02", endDate="2025-01-01"),
    ]
    with TestClient(app) as client:
        for params in invalid_queries:
            assert client.get("/api/v1/historical-crash-context", params=params).status_code == 422


def test_lookup_failure_is_503_not_fake_zero(monkeypatch, tmp_path):
    configure_database(tmp_path)
    monkeypatch.setattr(
        historical_routes,
        "call_lookup",
        lambda query: (_ for _ in ()).throw(ConnectionError("secret database detail")),
    )
    with TestClient(app) as client:
        response = client.get("/api/v1/historical-crash-context", params=BASE_QUERY)
    assert response.status_code == 503
    assert response.json() == {"detail": "Historical crash data service is unavailable"}


def test_explicit_local_mock_returns_no_dataset(monkeypatch, tmp_path):
    configure_database(tmp_path)
    monkeypatch.setenv("HISTORICAL_CRASH_USE_MOCK", "true")
    # Restore the real route-level adapter if another test patched it.
    from app.historical_service import call_lookup as real_call_lookup
    monkeypatch.setattr(historical_routes, "call_lookup", real_call_lookup)
    with TestClient(app) as client:
        response = client.get("/api/v1/historical-crash-context", params=BASE_QUERY)
    assert response.status_code == 200
    assert response.json()["status"] == "no_dataset"
    assert response.json()["totals"] is None


def test_openapi_uses_android_query_names(tmp_path):
    configure_database(tmp_path)
    with TestClient(app) as client:
        schema = client.get("/openapi.json").json()
    operation = schema["paths"]["/api/v1/historical-crash-context"]["get"]
    assert {item["name"] for item in operation["parameters"]} == {
        "latitude", "longitude", "radiusMeters", "timePeriod", "startDate", "endDate"
    }
