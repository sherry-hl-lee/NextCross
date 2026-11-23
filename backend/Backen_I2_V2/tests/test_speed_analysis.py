from datetime import date, datetime
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.speed_models import AnalysisRequest, DetectionConfig
from app.speed_analysis import analyze_trip
from app.speed_rules import evaluate_zone, choose_point_context
from calendar_fixtures import coverage


def road(status="static", speed=50, zone="zone-a"):
    return dict(source_speed_zone_id=zone,status=status,speed_kmh=speed,distance_m=1)


def rule(**changes):
    row = dict(speed_kmh=40,weekdays=[1,2,3,4,5],start_minute=480,end_minute=570,
               school_days=True,public_holidays=False)
    return row | changes


def request(speeds, times=None):
    times = times or list(range(len(speeds)))
    return AnalysisRequest(trip_id="t", started_at_epoch_millis=0,
        ended_at_epoch_millis=max(times,default=0)*1000,
        samples=[dict(sample_id=str(i),observed_at_epoch_millis=t*1000,
        latitude=-37.8,longitude=145,speed_mps=None if v is None else v/3.6,
        accuracy_meters=4) for i,(v,t) in enumerate(zip(speeds,times))])


def analyze(req, overrides=None):
    rows = {s.sample_id:[road()] for s in req.samples}
    rows.update(overrides or {})
    return analyze_trip(req,DetectionConfig(),(rows,{},[],{}))


@pytest.mark.parametrize("speeds,expected",[
    ([45,47,48,49],0),([48,55,58,60,49],1),
    ([48,57,59,60,45,58,60,61,47],2),([55,56],0),([53,53,53],0)])
def test_event_runs(speeds,expected):
    result=analyze(request(speeds))
    assert len(result.events)==expected
    if result.events:
        assert result.events[0].duration_seconds==2


def test_gap_splits_event():
    result=analyze(request([60]*6,[0,1,2,23,24,25]))
    assert len(result.events)==2
    assert all(e.duration_seconds==2 for e in result.events)
    assert any(x.reason=="GPS_GAP" for x in result.unmatched_intervals)


def test_limit_change_splits():
    result=analyze(request([70]*6),{str(i):[road(speed=60)] for i in range(3,6)})
    assert [e.reference_limit_kmh for e in result.events]==[50,60]


@pytest.mark.parametrize("change",[None,[],[road(status="dynamic")], [road(),road(zone="b")]])
def test_invalid_middle_breaks(change):
    req=request([60]*7)
    if change is None:
        req.samples[3].speed_mps=None
        changes={}
    else:
        changes={"3":change}
    result=analyze(req,changes)
    assert len(result.events)==2
    assert all(e.duration_seconds==2 for e in result.events)


def test_sort_duplicate_time_accuracy_and_jump():
    req=request([60]*4)
    req.samples.reverse()
    assert analyze(req).normalization==["sorted_by_timestamp"]
    req=request([60]*3,[0,0,1])
    assert not analyze(req).events
    req=request([60]*3)
    req.samples[1].accuracy_meters=None
    assert not analyze(req).events
    req=request([60]*3)
    req.samples[1].latitude=-36
    assert any(x.reason=="GPS_JUMP" for x in analyze(req).unmatched_intervals)


def test_calendar_contract():
    observed=datetime.fromisoformat("2026-09-14T08:30:00+10:00")
    zone=road("scheduled",None)
    assert evaluate_zone(zone,[rule()],observed,[],{}).reason=="calendar_out_of_coverage"
    assert evaluate_zone(zone,[rule()],datetime.fromisoformat("2026-09-14T12:00:00+10:00"),[],{}).reason=="base_speed_unconfirmed"
    utc=datetime.fromisoformat("2026-09-13T22:30:00+00:00")
    assert evaluate_zone(zone,[rule()],utc,[],{})==evaluate_zone(zone,[rule()],observed,[],{})
    periods=[dict(kind="SCHOOL_TERM",start_date=date(2026,9,1),end_date=date(2026,9,30))]
    assert evaluate_zone(zone,[rule()],observed,periods,coverage(2026)).speed_kmh==40
    periods.append(dict(kind="REGIONAL_HOLIDAY_UNCERTAIN",start_date=date(2026,9,14),end_date=date(2026,9,14)))
    assert evaluate_zone(zone,[rule()],observed,periods,coverage(2026)).reason=="regional_holiday_uncertain"


def test_windows_conflicts_and_dynamic():
    zone=road("scheduled",60)
    at=lambda t:datetime.fromisoformat("2026-09-14T"+t+":00+10:00")
    plain=rule(school_days=False)
    assert evaluate_zone(zone,[plain],at("08:00"),[],{}).speed_kmh==40
    assert evaluate_zone(zone,[plain],at("09:30"),[],{}).speed_kmh==60
    assert evaluate_zone(zone,[plain,plain|{"speed_kmh":30}],at("08:30"),[],{}).reason=="conflicting_active_rules"
    assert evaluate_zone(road("dynamic"),[],at("08:30"),[],{}).speed_kmh is None
    assert choose_point_context([road(),road(speed=60,zone="b")],{},at("08:30"),[],{}).status=="ambiguous"
    assert choose_point_context([road(),road(zone="b")],{},at("08:30"),[],{}).speed_kmh==50


def test_overnight_previous_year():
    observed=datetime.fromisoformat("2027-01-01T01:00:00+11:00")
    night=rule(start_minute=1380,end_minute=120,weekdays=[4])
    periods=[dict(kind="SCHOOL_TERM",start_date=date(2026,12,1),end_date=date(2026,12,31))]
    assert evaluate_zone(road("scheduled",None),[night],observed,periods,coverage(2026)).speed_kmh==40
    assert evaluate_zone(road("scheduled",None),[night],observed,periods,coverage(2027)).reason=="calendar_out_of_coverage"


def test_api(monkeypatch):
    import app.speed_routes as routes
    monkeypatch.setattr(routes,"load_speed_data",lambda samples,c:({s.sample_id:[road()] for s in samples},{},[],{}))
    with TestClient(app) as client:
        payload=request([55,56,57]).model_dump(by_alias=True)
        url="/api/v1/trips/t/speed-analysis"
        result=client.post(url,json=payload)
        assert result.status_code==200
        assert result.json()["events"][0]["type"]=="SPEED_LIMIT_EXCEEDANCE"
        assert "latitude" not in result.text
        assert client.post("/api/v1/trips/other/speed-analysis",json=payload).status_code==422
        empty=request([]).model_dump(by_alias=True)
        assert client.post(url,json=empty).json()["analysisStatus"]=="INSUFFICIENT_DATA"
        payload["samples"][1]["sampleId"]="0"
        assert client.post(url,json=payload).status_code==422
        payload=request([55,56,57]).model_dump(by_alias=True)
        payload["samples"][0]["latitude"]=100
        invalid=client.post(url,json=payload)
        assert invalid.status_code==422
        assert "input" not in invalid.text and "longitude" not in invalid.text
        def fail(*args): raise RuntimeError("private database information")
        monkeypatch.setattr(routes,"load_speed_data",fail)
        result=client.post(url,json=request([55,56,57]).model_dump(by_alias=True))
        assert result.status_code==503 and "private" not in result.text


def test_shop_holiday_and_school_exclusion():
    observed=datetime.fromisoformat("2026-09-14T08:30:00+10:00")
    holiday=[dict(kind="PUBLIC_HOLIDAY",start_date=date(2026,9,14),end_date=date(2026,9,14)),
             dict(kind="SCHOOL_TERM",start_date=date(2026,9,1),end_date=date(2026,9,30))]
    zone=road("scheduled",60)
    assert evaluate_zone(zone,[rule()],observed,holiday,coverage(2026)).speed_kmh==60
    # A weekday shop rule needs no calendar even on a holiday.
    assert evaluate_zone(zone,[rule(school_days=False)],observed,[],{}).speed_kmh==40
    holidays_only=rule(school_days=False,weekdays=[],public_holidays=True)
    assert evaluate_zone(zone,[holidays_only],observed,holiday,coverage(2026)).speed_kmh==40
    assert evaluate_zone(zone,[holidays_only],observed,holiday,{}).reason=="calendar_out_of_coverage"


def test_dst_equivalent_instants():
    zone=road("scheduled",60)
    ordinary=rule(school_days=False)
    local=datetime.fromisoformat("2026-12-14T08:30:00+11:00")
    utc=datetime.fromisoformat("2026-12-13T21:30:00+00:00")
    assert evaluate_zone(zone,[ordinary],local,[],{})==evaluate_zone(zone,[ordinary],utc,[],{})


@pytest.mark.parametrize("lat,lon,heading,time,status,speed,reason",[
 (-38.31820173862135,142.48590563075354,135,"2026-09-14T08:30:00+10:00","static",20,None),
 (-37.482715574504994,143.77839082010337,270,"2026-09-14T08:30:00+10:00","dynamic_unknown",None,"live_state_unavailable"),
 (-36.463237559830674,142.5826135717465,45,"2026-09-14T08:30:00+10:00","unknown",None,"calendar_out_of_coverage"),
 (-36.463237559830674,142.5826135717465,45,"2026-09-14T12:00:00+10:00","unknown",None,"base_speed_unconfirmed")])
def test_live_speed_reference(lat,lon,heading,time,status,speed,reason):
    import os
    if os.getenv("NEXTCROSS_RUN_SPEED_CHECK")!="1": pytest.skip("Explicit live database opt-in required")
    from app.speed_routes import speed_limit
    result=speed_limit(lat,lon,datetime.fromisoformat(time),heading,30)
    assert (result.status,result.speed_kmh,result.reason)==(status,speed,reason)
