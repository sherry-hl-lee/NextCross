import os

from fastapi.testclient import TestClient

from app.main import app


class SpyNotificationGateway:
    def __init__(self):
        self.calls: list[tuple[str, str]] = []

    def send_email(self, email: str, message: str) -> str | None:
        self.calls.append((email, message))
        return "spy-email-id"


def event(event_id: str = "breach-001") -> dict:
    return {
        "tripId": "planned-trip-001",
        "eventId": event_id,
        "eventType": "PERMITTED_AREA_BREACH",
        "occurredAtEpochMillis": 1788231600000,
        "parentEmail": "parent@example.com",
    }


def test_android_event_contract_and_duplicate_prevention(tmp_path, monkeypatch):
    monkeypatch.delenv("RUNTIME_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "events.db"))
    # Disable trip-level cooldown so this test isolates same-eventId dedup only.
    monkeypatch.setenv("BREACH_EMAIL_COOLDOWN_SECONDS", "0")

    with TestClient(app) as client:
        gateway = SpyNotificationGateway()
        app.state.notification_gateway = gateway

        first = client.post("/api/v1/notification-events", json=event())
        assert first.status_code == 201
        assert first.json() == {
            "tripId": "planned-trip-001",
            "eventId": "breach-001",
            "duplicate": False,
            "status": "sent",
        }
        assert gateway.calls == [
            (
                "parent@example.com",
                "The active trip has left the selected permitted area.",
            )
        ]

        duplicate = client.post("/api/v1/notification-events", json=event())
        assert duplicate.status_code == 200
        assert duplicate.json()["duplicate"] is True
        assert len(gateway.calls) == 1

        new_breach = client.post(
            "/api/v1/notification-events",
            json=event("breach-002"),
        )
        assert new_breach.status_code == 201
        assert new_breach.json()["duplicate"] is False
        assert len(gateway.calls) == 2


def test_trip_level_cooldown_suppresses_second_email(tmp_path, monkeypatch):
    monkeypatch.delenv("RUNTIME_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "cooldown.db"))
    monkeypatch.setenv("BREACH_EMAIL_COOLDOWN_SECONDS", "600")

    with TestClient(app) as client:
        gateway = SpyNotificationGateway()
        app.state.notification_gateway = gateway

        first = client.post("/api/v1/notification-events", json=event("breach-001"))
        assert first.status_code == 201
        assert first.json()["duplicate"] is False
        assert len(gateway.calls) == 1

        second = client.post("/api/v1/notification-events", json=event("breach-002"))
        assert second.status_code == 200
        assert second.json() == {
            "tripId": "planned-trip-001",
            "eventId": "breach-002",
            "duplicate": True,
            "status": "failed",
        }
        assert len(gateway.calls) == 1


def test_rejects_continuous_location_payload(tmp_path, monkeypatch):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "events.db"))
    with TestClient(app) as client:
        payload = event()
        payload["latitude"] = -37.8136
        assert client.post("/api/v1/notification-events", json=payload).status_code == 422


def test_removed_phone_field_is_rejected(tmp_path, monkeypatch):
    monkeypatch.delenv("RUNTIME_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "events.db"))
    with TestClient(app) as client:
        gateway = SpyNotificationGateway()
        app.state.notification_gateway = gateway
        payload = event()
        payload["parentMobileNumber"] = "+61400000000"
        response = client.post("/api/v1/notification-events", json=payload)
        assert response.status_code == 422
        assert gateway.calls == []
        schema = client.get("/openapi.json").json()["components"]["schemas"]["NotificationEventRequest"]
        assert "parentMobileNumber" not in schema["properties"]
        assert "parentMobileNumber" not in schema.get("required", [])


def test_end_trip_purges_cloud_rows_for_trip(tmp_path, monkeypatch):
    monkeypatch.delenv("RUNTIME_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "purge.db"))
    monkeypatch.setenv("BREACH_EMAIL_COOLDOWN_SECONDS", "0")

    with TestClient(app) as client:
        gateway = SpyNotificationGateway()
        app.state.notification_gateway = gateway
        assert client.post("/api/v1/notification-events", json=event("breach-001")).status_code == 201
        assert client.post("/api/v1/notification-events", json=event("breach-002")).status_code == 201
        assert len(gateway.calls) == 2

        purged = client.delete("/api/v1/notification-events/planned-trip-001")
        assert purged.status_code == 200
        assert purged.json() == {
            "tripId": "planned-trip-001",
            "deletedCount": 2,
        }

        # Same event ids can be accepted again after End Trip cleanup.
        again = client.post("/api/v1/notification-events", json=event("breach-001"))
        assert again.status_code == 201
        assert again.json()["duplicate"] is False
        assert len(gateway.calls) == 3
