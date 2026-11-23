import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Iterator, Protocol


class NotificationEventRepository(Protocol):
    def create_tables(self) -> None: ...
    def claim(self, trip_id: str, event_id: str, occurred_at: datetime) -> bool: ...
    def get_status(self, trip_id: str, event_id: str) -> str: ...
    def mark_sent(self, trip_id: str, event_id: str) -> None: ...
    def mark_failed(self, trip_id: str, event_id: str, error: str) -> None: ...


class SQLiteNotificationEventRepository:
    """Local prototype store mirroring the team's notification_event columns."""

    def __init__(self, database_path: str):
        self.database_path = database_path

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.database_path)
        try:
            yield connection
            connection.commit()
        finally:
            connection.close()

    def create_tables(self) -> None:
        with self.connect() as db:
            db.execute(
                """
                CREATE TABLE IF NOT EXISTS notification_event (
                    trip_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    occurred_at TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'sent', 'failed')),
                    resend_email_id TEXT,
                    error_message TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    sent_at TEXT,
                    PRIMARY KEY (trip_id, event_id)
                )
                """
            )

    def claim(self, trip_id: str, event_id: str, occurred_at: datetime) -> bool:
        with self.connect() as db:
            cursor = db.execute(
                """
                INSERT OR IGNORE INTO notification_event
                    (trip_id, event_id, occurred_at, status)
                VALUES (?, ?, ?, 'pending')
                """,
                (trip_id, event_id, occurred_at.isoformat()),
            )
            return cursor.rowcount == 1

    def get_status(self, trip_id: str, event_id: str) -> str:
        with self.connect() as db:
            row = db.execute(
                "SELECT status FROM notification_event WHERE trip_id = ? AND event_id = ?",
                (trip_id, event_id),
            ).fetchone()
        return row[0] if row else "failed"

    def mark_sent(self, trip_id: str, event_id: str) -> None:
        with self.connect() as db:
            db.execute(
                """
                UPDATE notification_event
                SET status = 'sent', sent_at = ?, error_message = NULL
                WHERE trip_id = ? AND event_id = ?
                """,
                (datetime.now(timezone.utc).isoformat(), trip_id, event_id),
            )

    def mark_failed(self, trip_id: str, event_id: str, error: str) -> None:
        with self.connect() as db:
            db.execute(
                """
                UPDATE notification_event SET status = 'failed', error_message = ?
                WHERE trip_id = ? AND event_id = ?
                """,
                (error[:500], trip_id, event_id),
            )


class PostgresNotificationEventRepository:
    """Adapter for iteration1_v2.notification_event in the team's Neon database."""

    def __init__(self, database_url: str):
        self.database_url = database_url

    @contextmanager
    def connect(self):
        import psycopg

        with psycopg.connect(self.database_url) as connection:
            yield connection

    def create_tables(self) -> None:
        # The database teammate owns and deploys 001_initial_schema.sql.
        pass

    def claim(self, trip_id: str, event_id: str, occurred_at: datetime) -> bool:
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO iteration1_v2.notification_event
                    (trip_id, event_id, occurred_at, status)
                VALUES (%s, %s, %s, 'pending')
                ON CONFLICT (trip_id, event_id) DO NOTHING
                RETURNING trip_id
                """,
                (trip_id, event_id, occurred_at),
            )
            return cursor.fetchone() is not None

    def get_status(self, trip_id: str, event_id: str) -> str:
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute(
                """
                SELECT status FROM iteration1_v2.notification_event
                WHERE trip_id = %s AND event_id = %s
                """,
                (trip_id, event_id),
            )
            row = cursor.fetchone()
            return row[0] if row else "failed"

    def mark_sent(self, trip_id: str, event_id: str) -> None:
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute(
                """
                UPDATE iteration1_v2.notification_event
                SET status = 'sent', sent_at = CURRENT_TIMESTAMP, error_message = NULL
                WHERE trip_id = %s AND event_id = %s
                """,
                (trip_id, event_id),
            )

    def mark_failed(self, trip_id: str, event_id: str, error: str) -> None:
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute(
                """
                UPDATE iteration1_v2.notification_event
                SET status = 'failed', error_message = %s
                WHERE trip_id = %s AND event_id = %s
                """,
                (error[:500], trip_id, event_id),
            )


def build_repository() -> NotificationEventRepository:
    database_url = os.getenv("RUNTIME_DATABASE_URL") or os.getenv("DATABASE_URL")
    if database_url:
        return PostgresNotificationEventRepository(database_url)
    return SQLiteNotificationEventRepository(os.getenv("DATABASE_PATH", "notification_events.db"))
