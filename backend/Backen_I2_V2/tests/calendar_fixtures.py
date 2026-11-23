"""Small published calendar contracts, not claims about real holiday dates."""

from app.calendar_coverage import POLICIES, REGIONAL_LIMITATION


def coverage(year, *, version=3, start=None, end=None, build_id="a" * 64, unconfirmed=None):
    start, end = start or f"{year}-01-01", end or f"{year}-12-31"
    full = start == f"{year}-01-01" and end == f"{year}-12-31"
    policy, scope = POLICIES[version]
    manifest = {
        "schema_version": version, "year": year, "build_id": build_id,
        "policy_version": policy, "coverage_scope": scope,
        "coverage_start": start, "coverage_end": end,
        "regional_holidays_complete": False, "limitations": [REGIONAL_LIMITATION],
        "status": "READY" if full else "READY_BOUNDED",
    }
    if version == 4:
        manifest.update(coverage_complete=True, full_year_complete=full,
                        requires_bounded_backend=not full, unconfirmed_events=unconfirmed or [])
    return {year: {"year": year, "build_id": build_id, "policy_version": policy,
                   "source_manifest": manifest}}
