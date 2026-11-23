"""Keep offline tests away from configured live databases."""
import pytest


@pytest.fixture(autouse=True)
def isolate_database_environment(request, monkeypatch, tmp_path):
    if request.node.name == "test_live_neon_reference" or request.node.name.startswith("test_live_speed_reference"):
        yield
        return
    for name in ("RUNTIME_DATABASE_URL", "DATABASE_URL", "HISTORICAL_DATABASE_URL", "SPEED_DATABASE_URL", "SPEED_DETECTION_CONFIG"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "isolated_notifications.db"))
    monkeypatch.setenv("HISTORICAL_CRASH_USE_MOCK", "false")
    yield
