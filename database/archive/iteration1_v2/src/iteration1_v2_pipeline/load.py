"""Replace v2 roads in one COPY pass; PostgreSQL simplifies each geometry."""

from pathlib import Path

from iteration1_v2_pipeline.settings import RAW_FILE
from iteration1_v2_pipeline.speed_zones import Counts, iter_road_speeds


COPY_SQL = """
COPY iteration1_v2.road_speed (
    source_speed_zone_id,
    speed_kmh,
    road_geom
) FROM STDIN
"""


def _check_expected_accepted(expected_accepted: int | None) -> None:
    """Require an optional expected accepted-row count to be a positive integer."""

    if expected_accepted is None:
        return
    if (
        isinstance(expected_accepted, bool)
        or not isinstance(expected_accepted, int)
        or expected_accepted <= 0
    ):
        raise ValueError("expected_accepted must be a positive integer")


def replace_road_speeds(
    database_url: str,
    raw_file: Path = RAW_FILE,
    *,
    expected_accepted: int | None = None,
) -> Counts:
    """Transactionally replace v2 road rows from one local GeoJSON.

    The table is truncated and repopulated through streaming COPY in the same
    transaction. The table's write trigger applies the fixed one-metre EPSG:7899
    simplification to each COPY row. When ``expected_accepted`` is provided, its
    positive value is compared with the completed COPY count before commit; a
    mismatch raises and rolls the replacement back.

    Args:
        database_url: PostgreSQL connection URL with write permission.
        raw_file: Plain GeoJSON file to consume once with ijson.
        expected_accepted: Optional exact positive accepted-row count.

    Returns:
        Source, accepted, and skipped totals from the load pass.

    Raises:
        ValueError: If the expected count is invalid, no row is accepted, or the
            completed COPY count differs from the expected count.
        Exception: Propagates file, parser, driver, and database failures.

    Side effects:
        Replaces ``iteration1_v2.road_speed`` and analyzes it on success.
    """

    _check_expected_accepted(expected_accepted)

    import psycopg

    counts = Counts()
    with psycopg.connect(database_url) as connection:
        with connection.cursor() as cursor:
            cursor.execute("TRUNCATE TABLE iteration1_v2.road_speed")
            with cursor.copy(COPY_SQL) as copy:
                for road in iter_road_speeds(raw_file, counts):
                    copy.write_row(
                        (
                            road.source_speed_zone_id,
                            road.speed_kmh,
                            road.geometry_ewkt,
                        )
                    )
            if counts.accepted == 0:
                raise ValueError("Refusing to commit an empty road_speed table")
            if (
                expected_accepted is not None
                and counts.accepted != expected_accepted
            ):
                raise ValueError(
                    f"Expected {expected_accepted} accepted roads, "
                    f"COPY accepted {counts.accepted}"
                )
            cursor.execute("ANALYZE iteration1_v2.road_speed")
    return counts
