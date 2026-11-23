"""Recognize published calendar policies and their inclusive supported dates.

This checks the runtime contract, not the original-source files held by the
pipeline. A year row alone is never evidence of calendar coverage. Regional
substitute and part-day holidays remain outside both supported source policies.
"""

from datetime import date
import re


POLICIES = {
    3: ("victoria-datavic-csv-only-v3", "datavic_school_terms_and_public_holidays"),
    4: ("victoria-reviewed-calendar-v4", "reviewed_victoria_school_terms_and_public_holidays"),
}
REGIONAL_LIMITATION = (
    "Regional substitute and part-day public holidays published separately by Business Victoria are not collected."
)


def _date(value, year):
    if not isinstance(value, str):
        raise ValueError("Calendar bounds must be ISO dates")
    result = date.fromisoformat(value)
    if result.isoformat() != value or result.year != year:
        raise ValueError("Calendar bounds must belong to the publication year")
    return result


def calendar_covers(day, coverage):
    """Return False for unsupported, absent, inconsistent or out-of-range data.

    Rows and period data must come from the same database snapshot. Call this
    with the rule's start date after the overnight adjustment, not necessarily
    the observation date. Do not cache the answer independently of build_id.
    """
    if not isinstance(coverage, dict):
        return False
    row = coverage.get(day.year)
    if not isinstance(row, dict) or type(row.get("year")) is not int or row["year"] != day.year:
        return False
    manifest = row.get("source_manifest")
    if not isinstance(manifest, dict):
        return False
    version = manifest.get("schema_version")
    if type(version) is not int or version not in POLICIES:
        return False
    policy, scope = POLICIES[version]
    build_id = row.get("build_id")
    if (not isinstance(build_id, str) or not re.fullmatch(r"[a-f0-9]{64}", build_id)
            or manifest.get("build_id") != build_id
            or row.get("policy_version") != policy or manifest.get("policy_version") != policy
            or type(manifest.get("year")) is not int or manifest["year"] != day.year
            or manifest.get("coverage_scope") != scope
            or manifest.get("regional_holidays_complete") is not False
            or manifest.get("limitations") != [REGIONAL_LIMITATION]):
        return False
    try:
        start = _date(manifest.get("coverage_start"), day.year)
        end = _date(manifest.get("coverage_end"), day.year)
        if start > end or not start <= day <= end:
            return False
        full_year = start == date(day.year, 1, 1) and end == date(day.year, 12, 31)
        if version == 3:
            return full_year and manifest.get("status") == "READY"
        if (manifest.get("coverage_complete") is not True
                or manifest.get("full_year_complete") is not full_year
                or manifest.get("requires_bounded_backend") is not (not full_year)
                or manifest.get("status") != ("READY" if full_year else "READY_BOUNDED")):
            return False
        unresolved = manifest.get("unconfirmed_events")
        if not isinstance(unresolved, list) or len(unresolved) > 1:
            return False
        for event in unresolved:
            if (not isinstance(event, dict) or event.get("family") != "afl"
                    or _date(event.get("not_before"), day.year) <= end):
                return False
        return True
    except (ValueError, TypeError):
        return False
