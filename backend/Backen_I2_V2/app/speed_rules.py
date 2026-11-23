"""Pure evaluation of the published iteration2_v2 rule/calendar contract."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from .speed_models import SpeedContext
from .calendar_coverage import calendar_covers

MELBOURNE = ZoneInfo("Australia/Melbourne")


def evaluate_rule(rule, observed, periods, coverage):
    """Return (active, unresolved reason); avoid calendar reads when irrelevant."""
    local = observed.astimezone(MELBOURNE)
    minute = local.hour * 60 + local.minute
    start, end = rule["start_minute"], rule["end_minute"]
    day = local.date()
    if start < end:
        if not start <= minute < end:
            return False, None
    else:
        if minute >= start:
            pass
        elif minute < end:
            day -= timedelta(days=1)
        else:
            return False, None
    weekday = day.isoweekday() in rule["weekdays"]
    if rule["school_days"] and not weekday:
        return False, None
    if not rule["school_days"] and weekday:
        return True, None
    if not rule["school_days"] and not rule["public_holidays"]:
        return False, None
    if not calendar_covers(day, coverage):
        return False, "calendar_out_of_coverage"
    kinds = {p["kind"] for p in periods if p["start_date"] <= day <= p["end_date"]}
    if rule["school_days"] and "SCHOOL_TERM" not in kinds:
        return False, None
    if "PUBLIC_HOLIDAY" in kinds:
        return not rule["school_days"], None
    if "REGIONAL_HOLIDAY_UNCERTAIN" in kinds:
        return False, "regional_holiday_uncertain"
    return bool(rule["school_days"]), None


def evaluate_zone(road, rules, observed, periods, coverage):
    base = dict(source_speed_zone_id=str(road["source_speed_zone_id"]))
    def result(status, speed=None, reason=None):
        return SpeedContext(**base, status=status, speed_kmh=speed, reason=reason)
    if road["status"] == "dynamic":
        return result("dynamic_unknown", reason="live_state_unavailable")
    if road["status"] == "static" and road["speed_kmh"] is not None:
        return result("static", road["speed_kmh"])
    if road["status"] != "scheduled":
        return result("unknown", reason="unresolved_source")
    if not rules:
        return result("unknown", reason="missing_rules")
    active, reasons = set(), set()
    for rule in rules:
        matched, reason = evaluate_rule(rule, observed, periods, coverage)
        if reason:
            reasons.add(reason)
        if matched:
            active.add(rule["speed_kmh"])
    if reasons:
        return result("unknown", reason=sorted(reasons)[0])
    if len(active) > 1:
        return result("unknown", reason="conflicting_active_rules")
    if active:
        return result("scheduled", next(iter(active)))
    if road["speed_kmh"] is not None:
        return result("inactive", road["speed_kmh"])
    return result("unknown", reason="base_speed_unconfirmed")


def choose_point_context(candidates, rules, observed, periods, coverage):
    if not candidates:
        return SpeedContext(status="no_match", reason="no_eligible_road")
    closest = min(r["distance_m"] for r in candidates)
    tied = sorted((r for r in candidates if r["distance_m"] <= closest + .01),
                  key=lambda r: (r["distance_m"], str(r["source_speed_zone_id"])))
    answers = [evaluate_zone(r, rules.get(str(r["source_speed_zone_id"]), []),
                             observed, periods, coverage) for r in tied]
    if len({(a.speed_kmh, a.reason) for a in answers}) > 1:
        return SpeedContext(status="ambiguous", reason="conflicting_nearest_candidates")
    return answers[0]
