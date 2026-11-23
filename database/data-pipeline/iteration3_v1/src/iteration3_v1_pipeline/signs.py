"""Normalize source speed-sign evidence and define the shared speed-rule record.

The offline speed build uses these pure helpers to select eligible signs,
interpret their facing direction, and parse the two published School/Shop time
windows. Missing or unsupported schedule details are not invented. These
functions consume source property dictionaries; they do not read files,
contact a database, or obtain the live state of electronic signs.

``Rule`` represents a parsed schedule before it is exported to the database.
The backend evaluates the stored schedule and calendar at a requested time.
"""

from dataclasses import dataclass, replace
from datetime import date, datetime
import math
from zoneinfo import ZoneInfo

from iteration3_v1_pipeline.settings import DIRECTIONS

DAY_NAMES = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")


@dataclass(frozen=True)
class Rule:
    """Represent one immutable signed speed window for the offline build.

    Attributes:
        speed_kmh: Signed speed in kilometres per hour.
        weekdays: ISO weekday numbers, Monday=1 through Sunday=7, applying to
            the window's start date when the window crosses midnight.
        start_minute: Inclusive start minute of the local day, 0 through 1439.
        end_minute: Exclusive end minute, up to 1440 for 24:00; a smaller end
            than start denotes a window continuing into the following day.
        school_days: Whether lookup must apply the supported school calendar.
        public_holidays: Whether the source explicitly includes public holidays.
        source_sign_ids: Evidence identifiers; excluded by ``signature`` when
            comparing schedules from different signs.

    ``sign_rules`` validates source values. The dataclass constructor itself
    does not validate records reconstructed from database rows.
    """

    speed_kmh: int
    weekdays: tuple[int, ...]
    start_minute: int
    end_minute: int
    school_days: bool = False
    public_holidays: bool = False
    source_sign_ids: tuple[str, ...] = ()

    def signature(self):
        """Return a copy without sign IDs for comparing the remaining rule fields."""
        # Different physical signs can describe the same schedule. Ignore their
        # evidence IDs during comparison while leaving the original rule intact.
        # Calendar flags remain part of equality: identical clock times alone
        # do not mean that school-day and holiday restrictions agree.
        return replace(self, source_sign_ids=())


def minutes(value):
    """Convert an HH:MM source string to minutes, accepting 24:00 as 1440.

    Raises:
        ValueError: The string is malformed or its hour/minute is out of range.
    """
    hour, minute = map(int, value.strip().split(":"))
    # Allow the source's end-of-day boundary, but reject times such as 24:30.
    if not (0 <= hour <= 24 and 0 <= minute < 60 and (hour < 24 or minute == 0)):
        raise ValueError(f"Invalid sign time: {value}")
    return hour * 60 + minute


def angular_difference(a, b):
    """Return the shortest unsigned difference between degree headings, 0..180."""
    # Wrap across north so headings such as 350 and 10 are only 20 degrees apart.
    return abs((a - b + 180) % 360 - 180)


def travel_heading(properties):
    """Infer traffic heading from the opposite direction of a sign's face.

    Args:
        properties: Source sign properties with optional numeric ``bearing``
            and compass ``facing`` fields.

    Returns:
        Degree heading after a 180-degree reversal, or None when direction is
        absent, the numeric bearing is non-finite/out of range, or the bearing
        and known compass facing disagree by more than 45 degrees.

    Raises:
        ValueError: A supplied bearing cannot be converted to a number.
        TypeError: A supplied bearing has an unsupported numeric input type.

    A numeric bearing takes precedence when consistent with a known facing.
    This is a direction check for spatial matching, not lane identification.
    """
    bearing = properties.get("bearing")
    facing = DIRECTIONS.get(properties.get("facing"))
    if bearing not in (None, ""):
        # An unusable numeric bearing makes the direction unresolved; do not
        # silently replace contradictory source evidence with the compass label.
        bearing = float(bearing)
        if not math.isfinite(bearing) or not 0 <= bearing <= 360:
            return None
        if facing is not None and angular_difference(bearing, facing) > 45:
            return None
        # The sign faces approaching drivers, opposite to their travel direction.
        return (bearing + 180) % 360
    # Use the compass direction only when the numeric bearing is absent.
    return None if facing is None else (facing + 180) % 360


def is_current(properties, *, as_of: date | None = None):
    """Check whether a source sign is eligible for the build's snapshot date.

    Args:
        properties: Source sign installation, removal, and change-status fields.
        as_of: Date against which an installation date is checked; defaults to
            today's Melbourne local date when that comparison is needed.

    Returns:
        False for a removal marker, Installation Pending status, unparseable
        installation date, or installation after ``as_of``; otherwise True.
        Missing installation dates alone do not exclude a sign.

    Any removal marker excludes the record regardless of its date. This
    snapshot eligibility check cannot reconstruct historical sign state.
    """
    # Exclude removed or pending signs before interpreting their other fields.
    if properties.get("date_sign_removed") or properties.get("speed_sign_change_request_status") == "Installation Pending":
        return False
    installed = properties.get("date_sign_installed")
    if installed:
        try:
            # Discard the source's optional parenthesized suffix before parsing
            # the installation timestamp; eligibility uses its calendar date.
            installed_on = datetime.strptime(str(installed).split(" (")[0], "%d/%m/%Y %H:%M:%S").date()
        except ValueError:
            return False
        # An explicit snapshot date keeps repeated builds independent of today.
        as_of = as_of or datetime.now(ZoneInfo("Australia/Melbourne")).date()
        if installed_on > as_of:
            return False
    # A missing installation date is accepted by the source eligibility policy.
    return True


def is_regulatory(properties):
    """Apply the source-label filter used to reject advisory/end/supplementary signs.

    Returns:
        True for a nonempty ``sign_type`` that does not contain AHEAD, END plus
        a trailing space, or SUPPL (case-insensitive). This lexical source
        filter is not a complete legal classification of all possible signs.
    """
    kind = str(properties.get("sign_type") or "")
    # Includes the source's misspelled "Supplimentaty Signs" category.
    return bool(kind) and not any(word in kind.upper() for word in ("AHEAD", "END ", "SUPPL"))


def sign_rules(properties, *, as_of: date | None = None):
    """Parse a current regulatory School/Shop sign into distinct speed rules.

    Args:
        properties: Source sign fields for type, speed, identifier, and up to
            two time/day windows. Weekday labels must use the published English
            names; ``Public Holiday`` is the additional supported day label.
        as_of: Optional installation eligibility date passed to ``is_current``.

    Returns:
        A tuple of distinct Rule values, retaining the source sign identifier.
        Returns an empty tuple for out-of-scope/ineligible signs or when no
        time windows are supplied. Overnight windows remain single rules for
        the lookup layer to interpret against their start date.

    Raises:
        KeyError: A required speed or sign identifier is absent when needed.
        TypeError: A source speed has an unsupported conversion type.
        ValueError: Speed, weekdays, or time bounds are unsupported, incomplete,
            or invalid. Callers retain these failures as unresolved evidence.

    Only School/Shop schedules are in this iteration's automatic scope.

    A missing second-window weekday inherits the first window's weekdays.
    The source also has single-window records with weekdays in slot two;
    use those only when slot two has no times. No missing times are invented.
    """
    kind = properties.get("variable_calendar_type")
    # Filter the supported evidence first. Other sign categories produce no
    # automatic rules, rather than being interpreted as School/Shop schedules.
    if kind not in ("School", "Shop") or not is_current(properties, as_of=as_of) or not is_regulatory(properties):
        return ()
    speed = int(properties["speed"])
    if not 1 <= speed <= 200:
        raise ValueError("Sign speed is outside 1..200 km/h")
    first_days = properties.get("variable_time_1_days") or ""
    # Some single-window records store their weekday label in slot two. Only
    # borrow that label when slot two does not describe a separate time window.
    if not first_days and not properties.get("variable_time_2_from") and not properties.get("variable_time_2_to"):
        first_days = properties.get("variable_time_2_days") or ""
    result = []
    # Preserve source slot order because the build compares a sign's complete
    # rule tuple when looking for agreement between different physical signs.
    for slot in (1, 2):
        start = properties.get(f"variable_time_{slot}_from")
        end = properties.get(f"variable_time_{slot}_to")
        # An unused slot is harmless; a partly specified slot is unresolved
        # evidence because its missing boundary cannot be inferred safely.
        if not start and not end:
            continue
        if not start or not end:
            raise ValueError("Incomplete sign time window")
        # Slot two may inherit slot one's days. Normalize whitespace and reject
        # unknown labels instead of dropping conditions from the signed rule.
        days = (properties.get(f"variable_time_{slot}_days") or first_days).split(",")
        days = {day.strip() for day in days if day.strip()}
        if not days or days - set(DAY_NAMES) - {"Public Holiday"}:
            raise ValueError("Missing or unsupported sign weekdays")
        start, end = minutes(start), minutes(end)
        # Keep end < start for overnight schedules. Equal bounds are rejected
        # rather than guessed to mean either a full day or a midnight crossing.
        if start == 1440 or start == end:
            raise ValueError("Empty sign window")
        # School records explicitly signed 00:00–24:00 every day including
        # public holidays describe permanent limits, not term-only windows.
        permanent = start == 0 and end == 1440 and days == set(DAY_NAMES) | {"Public Holiday"}
        # Store weekdays in stable ISO order, with the holiday flag separate.
        # Public Holiday is a separate calendar condition, never weekday 8.
        # Retain the sign ID so the build can report the evidence for this rule.
        result.append(Rule(speed, tuple(i + 1 for i, day in enumerate(DAY_NAMES) if day in days),
                           start, end, kind == "School" and not permanent,
                           "Public Holiday" in days, (str(properties["speed_sign_id"]),)))
    # Identical slots on this sign collapse into one rule, preserving slot order.
    # Frozen Rule values are hashable, allowing them to serve as dictionary keys.
    return tuple(dict.fromkeys(result))
