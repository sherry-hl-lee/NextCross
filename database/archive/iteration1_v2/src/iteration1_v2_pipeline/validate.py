"""Run one lightweight ijson pass and report v2 selection counts."""

from pathlib import Path

from iteration1_v2_pipeline.settings import RAW_FILE
from iteration1_v2_pipeline.speed_zones import Counts, iter_road_speeds


def validate_raw(raw_file: Path = RAW_FILE) -> Counts:
    """Count all source features and rows accepted by the v2 filter.

    Args:
        raw_file: Plain GeoJSON file to stream with ijson.

    Returns:
        Source, accepted, and skipped totals.

    Raises:
        ValueError: If the file produces no accepted road rows.
        Exception: Propagates file and JSON parsing errors.

    Side effects:
        Reads the complete source once without modifying it.
    """

    counts = Counts()
    for _ in iter_road_speeds(raw_file, counts):
        pass
    if counts.accepted == 0:
        raise ValueError("No usable Normal Operation road speeds were found")
    return counts
