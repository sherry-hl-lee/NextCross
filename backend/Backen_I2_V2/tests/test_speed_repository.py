import sys
from types import SimpleNamespace
from app.speed_repository import load_speed_data
from app.speed_models import DetectionConfig
from test_speed_analysis import request
from calendar_fixtures import coverage


def test_batched_readonly_queries_and_rollback(monkeypatch):
    calls=[]
    class Connection:
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def execute(self,sql,params=None):
            calls.append((sql,params))
            if "jsonb_to_recordset" in sql:
                import json
                return SimpleNamespace(fetchall=lambda:[dict(sample_id=s["sample_id"],source_speed_zone_id="00000000-0000-0000-0000-000000000001",speed_kmh=50,status="static",distance_m=1) for s in json.loads(params[0])])
            return SimpleNamespace(fetchall=lambda:[])
    monkeypatch.setenv("SPEED_DATABASE_URL","test-only")
    monkeypatch.setitem(sys.modules,"psycopg",SimpleNamespace(connect=lambda *a,**k:Connection()))
    monkeypatch.setitem(sys.modules,"psycopg.rows",SimpleNamespace(dict_row=None))
    data=load_speed_data(request([50]*5).samples,DetectionConfig(query_chunk_size=2))
    assert len(data[0])==5
    assert sum("jsonb_to_recordset" in sql for sql,p in calls)==3
    assert calls[0][0]=="BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
    assert calls[-1][0]=="ROLLBACK"
    assert all("INSERT" not in sql and "UPDATE" not in sql for sql,p in calls)


def test_failure_rolls_back(monkeypatch):
    calls=[]
    class Connection:
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def execute(self,sql,params=None):
            calls.append(sql)
            if "jsonb_to_recordset" in sql: raise RuntimeError("query failed")
    monkeypatch.setenv("SPEED_DATABASE_URL","test-only")
    monkeypatch.setitem(sys.modules,"psycopg",SimpleNamespace(connect=lambda *a,**k:Connection()))
    monkeypatch.setitem(sys.modules,"psycopg.rows",SimpleNamespace(dict_row=None))
    import pytest
    with pytest.raises(RuntimeError): load_speed_data(request([50]*3).samples,DetectionConfig())
    assert calls[-1]=="ROLLBACK"


def test_every_request_reads_manifest_with_periods_from_a_fresh_snapshot(monkeypatch):
    from datetime import date
    from app.calendar_coverage import calendar_covers

    publications = [coverage(2027), coverage(2027, version=4, end="2027-04-29", build_id="b" * 64)]
    connections = []
    class Connection:
        def __init__(self):
            self.publication = publications[len(connections)]
            self.calls = []
            connections.append(self)
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def execute(self, sql, params=None):
            self.calls.append(sql)
            rows = []
            if "FROM iteration2_v2.calendar_coverage" in sql:
                assert "policy_version" in sql and "source_manifest" in sql
                rows = list(self.publication.values())
            elif "FROM iteration2_v2.calendar_period" in sql:
                rows = [dict(kind="SCHOOL_TERM", start_date=date(2027, 4, 1), end_date=date(2027, 5, 1))]
            return SimpleNamespace(fetchall=lambda: rows)
    monkeypatch.setenv("SPEED_DATABASE_URL", "test-only")
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda *args, **kwargs: Connection()))
    monkeypatch.setitem(sys.modules, "psycopg.rows", SimpleNamespace(dict_row=None))
    first = load_speed_data([], DetectionConfig())
    second = load_speed_data([], DetectionConfig())
    assert len(connections) == 2
    assert calendar_covers(date(2027, 4, 30), first[3])
    assert not calendar_covers(date(2027, 4, 30), second[3])
    assert first[3][2027]["build_id"] != second[3][2027]["build_id"]
    assert first[2] == second[2]  # Existing period rows cannot override changed bounds.
    for connection in connections:
        assert connection.calls[0] == "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY"
        assert connection.calls[-1] == "ROLLBACK"
