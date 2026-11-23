from datetime import date, datetime

import pytest

from app.calendar_coverage import calendar_covers
from app.speed_rules import evaluate_zone
from calendar_fixtures import coverage
from test_speed_analysis import road, rule


@pytest.mark.parametrize("version", [3, 4])
def test_supported_full_year_policies_include_both_boundaries(version):
    data = coverage(2027, version=version)
    assert calendar_covers(date(2027, 1, 1), data)
    assert calendar_covers(date(2027, 12, 31), data)
    assert not calendar_covers(date(2026, 12, 31), data)
    assert not calendar_covers(date(2028, 1, 1), data)


def test_bounded_coverage_includes_end_but_never_later_known_periods():
    data = coverage(2027, version=4, start="2027-01-02", end="2027-04-30")
    assert not calendar_covers(date(2027, 1, 1), data)
    assert calendar_covers(date(2027, 1, 2), data)
    assert calendar_covers(date(2027, 4, 30), data)
    assert not calendar_covers(date(2027, 5, 1), data)
    # A retained known school period cannot extend the verified interval.
    periods = [dict(kind="SCHOOL_TERM", start_date=date(2027, 1, 1), end_date=date(2027, 12, 31))]
    every_day = rule(weekdays=list(range(1, 8)))
    inside = datetime.fromisoformat("2027-04-30T08:30:00+10:00")
    outside = datetime.fromisoformat("2027-05-01T08:30:00+10:00")
    assert evaluate_zone(road("scheduled", 60), [every_day], inside, periods, data).speed_kmh == 40
    rejected = evaluate_zone(road("scheduled", 60), [every_day], outside, periods, data)
    assert rejected.speed_kmh is None and rejected.reason == "calendar_out_of_coverage"


@pytest.mark.parametrize("change", [
    {"schema_version": 2}, {"schema_version": 4.0}, {"policy_version": "future-policy"},
    {"coverage_scope": "all_holidays"}, {"status": "PENDING"}, {"status": "ERROR"},
    {"status": "READY"}, {"year": 2026}, {"year": "2027"}, {"build_id": "b" * 64},
    {"coverage_complete": False}, {"coverage_complete": 1}, {"full_year_complete": True},
    {"requires_bounded_backend": False}, {"coverage_start": "2027-05-01"},
    {"coverage_end": "2027-02-30"}, {"coverage_end": "2028-04-30"},
    {"regional_holidays_complete": True}, {"regional_holidays_complete": 0},
    {"limitations": []}, {"unconfirmed_events": None},
])
def test_inconsistent_or_unsupported_manifest_fails_closed(change):
    data = coverage(2027, version=4, end="2027-04-30")
    data[2027]["source_manifest"].update(change)
    assert not calendar_covers(date(2027, 3, 1), data)


@pytest.mark.parametrize("row", [None, {}, {"year": 2027}, {"year": 2027, "source_manifest": "{}"}])
def test_year_or_empty_manifest_does_not_imply_coverage(row):
    assert not calendar_covers(date(2027, 3, 1), {2027: row})


def test_row_policy_and_manifest_policy_must_agree():
    data = coverage(2027)
    data[2027]["policy_version"] = "future-policy"
    assert not calendar_covers(date(2027, 3, 1), data)
    data = coverage(2027)
    data[2027]["build_id"] = "invalid"
    assert not calendar_covers(date(2027, 3, 1), data)


def test_schema_three_cannot_claim_partial_or_pending_coverage():
    data = coverage(2027, end="2027-04-30")
    data[2027]["source_manifest"]["status"] = "READY"
    assert not calendar_covers(date(2027, 3, 1), data)


def test_unknown_afl_date_can_only_lie_strictly_after_covered_interval():
    data = coverage(2027, version=4, end="2027-04-30",
                    unconfirmed=[{"family": "afl", "not_before": "2027-09-01"}])
    assert calendar_covers(date(2027, 4, 30), data)
    data[2027]["source_manifest"]["unconfirmed_events"][0]["not_before"] = "2027-04-30"
    assert not calendar_covers(date(2027, 3, 1), data)
    data[2027]["source_manifest"]["unconfirmed_events"] = [{"family": "new_year", "not_before": "2027-09-01"}]
    assert not calendar_covers(date(2027, 3, 1), data)


def test_overnight_january_window_uses_previous_year_and_its_bounds():
    observed = datetime.fromisoformat("2027-01-01T01:00:00+11:00")
    night = rule(start_minute=1380, end_minute=120, weekdays=[4])
    periods = [dict(kind="SCHOOL_TERM", start_date=date(2026, 12, 1), end_date=date(2026, 12, 31))]
    previous = coverage(2026, version=4, start="2026-12-01", end="2026-12-31")
    assert evaluate_zone(road("scheduled", 60), [night], observed, periods, previous).speed_kmh == 40
    # Current-year coverage does not repair a gap for the start date.
    gap = coverage(2026, version=4, end="2026-12-30") | coverage(2027)
    result = evaluate_zone(road("scheduled", 60), [night], observed, periods, gap)
    assert result.reason == "calendar_out_of_coverage" and result.speed_kmh is None
    utc = datetime.fromisoformat("2026-12-31T14:00:00+00:00")
    assert evaluate_zone(road("scheduled", 60), [night], utc, periods, previous).speed_kmh == 40


def test_static_irrelevant_and_ordinary_weekday_rules_need_no_calendar():
    at = datetime.fromisoformat("2027-04-30T08:30:00+10:00")
    assert evaluate_zone(road("static", 60), [], at, [], {}).speed_kmh == 60
    assert evaluate_zone(road("scheduled", 60), [rule(school_days=False)], at, [], {}).speed_kmh == 40
    later = datetime.fromisoformat("2027-04-30T12:00:00+10:00")
    assert evaluate_zone(road("scheduled", 60), [rule()], later, [], {}).speed_kmh == 60


def test_regional_marker_stays_unknown_under_reviewed_policy():
    at = datetime.fromisoformat("2027-04-30T08:30:00+10:00")
    periods = [dict(kind="SCHOOL_TERM", start_date=date(2027, 4, 1), end_date=date(2027, 5, 1)),
               dict(kind="REGIONAL_HOLIDAY_UNCERTAIN", start_date=date(2027, 4, 30), end_date=date(2027, 4, 30))]
    result = evaluate_zone(road("scheduled", 60), [rule()], at, periods, coverage(2027, version=4, end="2027-04-30"))
    assert result.speed_kmh is None and result.reason == "regional_holiday_uncertain"
