import os

from fastapi.testclient import TestClient

from app.main import app


def configure_database(tmp_path):
    os.environ.pop("RUNTIME_DATABASE_URL", None)
    os.environ.pop("DATABASE_URL", None)
    os.environ["DATABASE_PATH"] = str(tmp_path / "events.db")


def event(event_id=1, trip_id="trip-1", event_type="HardAcceleration"):
    return {
        "id": event_id,
        "tripId": trip_id,
        "type": event_type,
        "recordedAtMillis": 1780000000000,
        "accelerationMagnitude": 3.2,
    }


def test_prompts_are_grounded_in_recorded_events(tmp_path):
    configure_database(tmp_path)
    payload = {
        "tripId": "trip-1",
        "events": [event(), event(2, event_type="HardBraking")],
    }
    with TestClient(app) as client:
        response = client.post("/api/v1/trip-discussion-prompts", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert [item["eventId"] for item in body["suggestions"]] == [1, 2]
    assert [item["type"] for item in body["suggestions"]] == [
        "HardAcceleration", "HardBraking"
    ]
    assert body["generationMode"] == "rule_based_prototype"


def test_no_recorded_event_means_no_suggestion(tmp_path):
    configure_database(tmp_path)
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/trip-discussion-prompts",
            json={"tripId": "trip-1", "events": []},
        )
    assert response.status_code == 200
    assert response.json()["suggestions"] == []


def test_cross_trip_unknown_and_duplicate_events_are_rejected(tmp_path):
    configure_database(tmp_path)
    payloads = [
        {"tripId": "trip-1", "events": [event(trip_id="trip-2")]},
        {"tripId": "trip-1", "events": [event(event_type="Unsupported")]},
        {"tripId": "trip-1", "events": [event(), event()]},
    ]
    with TestClient(app) as client:
        for payload in payloads:
            assert client.post("/api/v1/trip-discussion-prompts", json=payload).status_code == 422
