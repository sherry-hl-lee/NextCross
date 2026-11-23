-- Report email delivery state owned by the backend.
-- Applied by the normal migrate command, or run this file on its own.
-- Reapplying preserves existing rows; it does not alter an existing table's
-- definition. Runtime-role privileges are granted separately at deployment.
CREATE SCHEMA IF NOT EXISTS iteration2_v2;

CREATE TABLE IF NOT EXISTS iteration2_v2.report_email_dispatch (
    -- The backend supplies the deduplication key and digest. The migration
    -- neither computes them nor couples dispatch history to a dataset version.
    request_hash text PRIMARY KEY,
    digest text NOT NULL,
    status text NOT NULL,
    reason text,
    -- Preserve the backend contract's integer seconds instead of converting
    -- this column to the timestamptz type used by notification_event.
    created_epoch bigint NOT NULL,
    CONSTRAINT report_email_dispatch_status_ck
        CHECK (status IN ('PENDING', 'ACCEPTED', 'FAILED', 'UNKNOWN'))
);

COMMENT ON TABLE iteration2_v2.report_email_dispatch IS
    'Report email dispatch state keyed by request_hash; the backend owns inserts and status transitions.';
COMMENT ON COLUMN iteration2_v2.report_email_dispatch.created_epoch IS
    'Creation time supplied by the backend as a Unix timestamp in seconds, not milliseconds.';
