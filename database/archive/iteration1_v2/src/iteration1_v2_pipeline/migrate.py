"""Apply the small Iteration 1 v2 PostGIS migration."""

from iteration1_v2_pipeline.settings import MIGRATION_FILE


def apply_migration(database_url: str) -> None:
    """Execute the v2 migration in one normal psycopg transaction.

    Args:
        database_url: Owner-capable PostgreSQL Direct connection URL.

    Raises:
        Exception: Propagates missing SQL, driver, connection, and DDL failures.

    Side effects:
        Creates or updates the ``iteration1_v2`` database objects.
    """

    import psycopg

    migration = MIGRATION_FILE.read_text(encoding="utf-8")
    with psycopg.connect(database_url) as connection:
        connection.execute(migration)
