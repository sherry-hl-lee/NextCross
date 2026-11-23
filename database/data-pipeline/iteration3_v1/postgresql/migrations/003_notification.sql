-- Purpose: provide the notification table as part of this iteration's schema.
-- Usage: normal `migrate` applies this file after the two dataset migrations.
-- Input: no parameters or notification history. Output: an empty table on a
-- fresh database; existing rows remain when this file is reapplied.
-- Responsibility boundary: the data pipeline owns this table definition, but
-- the backend owns event registration, delivery calls, and status transitions.
-- No old history is copied, no backend SQL is switched, and no role is granted
-- privileges by this DDL. See backend_handover.md for those deployment steps.
-- Application-owned delivery state; dataset loaders never clear this table.
CREATE SCHEMA IF NOT EXISTS iteration2_v2;

-- Reusing (trip_id,event_id) lets an application claim with ON CONFLICT DO
-- NOTHING. The primary key prevents duplicate rows; it cannot guarantee a
-- single email delivery across a database and an external delivery provider.
CREATE TABLE IF NOT EXISTS iteration2_v2.notification_event (
    trip_id text NOT NULL,
    event_id text NOT NULL,
    occurred_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    resend_email_id text,
    error_message text,
    -- Creation is recorded by the DB; occurred_at and sent_at describe separate
    -- application events and need not equal the row's insertion time.
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at timestamptz,
    CONSTRAINT notification_event_pkey PRIMARY KEY (trip_id, event_id),
    -- Restrict stored labels only. Valid transitions and retry decisions remain
    -- the backend's responsibility; this CHECK does not implement a state machine.
    CONSTRAINT notification_event_status_ck
        CHECK (status IN ('pending', 'sent', 'failed'))
);

COMMENT ON TABLE iteration2_v2.notification_event IS
    'Notification delivery state keyed by trip and event. The application owns status transitions; this is not an exactly-once queue.';
COMMENT ON COLUMN iteration2_v2.notification_event.event_id IS
    'Stable logical notification ID within a trip; reuse the same ID for retries.';
COMMENT ON COLUMN iteration2_v2.notification_event.resend_email_id IS
    'Optional delivery identifier returned by the Resend provider.';
