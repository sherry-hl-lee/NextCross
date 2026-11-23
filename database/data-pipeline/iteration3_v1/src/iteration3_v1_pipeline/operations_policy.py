"""One source of operational defaults; the date never changes the active DB build.

September determines when a new candidate period becomes eligible. Only an
explicit successful publication changes the active period; the backend must
read that period from crash_build instead of calculating it from today's date.
Thresholds in config/operations.json are reviewable initial operational limits.

These helpers read local configuration and dates only. They neither download
data nor touch PostgreSQL. Candidate preparation checks release eligibility
here; publication separately enforces the latest September window, prior
publication history and recovery rules in deploy/operations.py.
"""

from datetime import date, datetime
import json
from zoneinfo import ZoneInfo

from iteration3_v1_pipeline.settings import ITERATION_DIR

POLICY_PATH = ITERATION_DIR / "config/operations.json"


def read_policy():
    """Read this checkout's operational policy with its supported schema version.

    Returns:
        The parsed policy dictionary. Individual consumers validate or use their
        required fields; this helper is not a full schema validator.

    Raises:
        ValueError: Invalid JSON or unsupported policy schema_version.
        OSError: The configured policy file cannot be read.

    Side effects:
        Reads POLICY_PATH on every call so edits are not hidden by a cache.
    """
    policy = json.loads(POLICY_PATH.read_text())
    if policy.get("schema_version") != 1:
        raise ValueError("Unsupported operations policy")
    return policy


def melbourne_today():
    """Return the current date in Victoria's operational timezone.

    Returns:
        A datetime.date derived from Australia/Melbourne, including its DST
        offset, rather than the host machine's timezone or UTC date.

    Raises:
        ZoneInfoNotFoundError: System/package timezone data is unavailable.
    """
    return datetime.now(ZoneInfo("Australia/Melbourne")).date()


def baseline_range():
    """Read the fixed accepted crash baseline from policy, without date rollover.

    Returns:
        An inclusive (start_date, end_date) pair; currently 2021–2025.

    Raises:
        KeyError/ValueError: Required fields are missing or dates are invalid.
        OSError: The policy cannot be read.

    Side effects:
        Reads local policy only; it does not assert the database has this build.
    """
    policy = read_policy()["crashes"]
    return date.fromisoformat(policy["baseline_start"]), date.fromisoformat(policy["baseline_end"])


def annual_range(release_year, *, today=None):
    """Resolve a due annual candidate into its inclusive completed-year window.

    Args:
        release_year: Integer release year, at least 2027. A bool is not a year.
        today: Optional date for reproducible checks; defaults to Melbourne today.

    Returns:
        An inclusive (start_date, end_date) pair covering the configured number
        of complete years before release_year. September 2027 yields 2022–2026.

    Raises:
        ValueError: Unsupported release year, future September release, invalid
            policy/date values, or unsupported policy schema version.
        KeyError/OSError: Required configuration is missing or unreadable.

    Side effects:
        Reads local policy and, when needed, the clock. No active build changes.

    This eligibility check permits an already due older release for preparation.
    The publication gate additionally requires the latest September release for
    normal updates; recovery follows its separate reviewed-history contract.
    """
    policy = read_policy()["crashes"]
    today = today or melbourne_today()
    # Year boundaries do not imply publication. Eligibility begins on the first
    # day of the release month, and the range always ends in the preceding year.
    # Exact type checking keeps booleans out of the year contract, since Python
    # otherwise treats bool as an int subclass.
    if type(release_year) is not int or not policy["first_release_year"] <= release_year <= 9998:
        raise ValueError("Annual crash releases start in September 2027")
    if today < date(release_year, policy["release_month"], 1):
        raise ValueError("Crash candidate is not due until September of its release year")
    return date(release_year - policy["window_years"], 1, 1), date(release_year - 1, 12, 31)
