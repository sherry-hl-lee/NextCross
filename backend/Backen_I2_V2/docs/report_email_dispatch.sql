-- Run once using the database owner's/admin connection, not the read-only backend role.
-- This adds a dispatch ledger only; it does not alter existing crash/speed tables.
CREATE TABLE IF NOT EXISTS iteration2_v2.report_email_dispatch (
    request_hash TEXT PRIMARY KEY,
    digest TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING','ACCEPTED','FAILED','UNKNOWN')),
    reason TEXT,
    created_epoch BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS report_email_dispatch_created_idx
    ON iteration2_v2.report_email_dispatch(created_epoch);
GRANT USAGE ON SCHEMA iteration2_v2 TO iteration1_v2_backend;
GRANT SELECT, INSERT, UPDATE ON iteration2_v2.report_email_dispatch TO iteration1_v2_backend;
