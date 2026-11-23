-- Publish explicit calendar coverage alongside the unchanged calendar_period rows.
-- One row represents a validated year within its manifest source scope; it does
-- not imply complete regional holiday coverage. This migration creates
-- no coverage from existing dates: calendar-load must first validate a bundle.
-- No backend privileges or role memberships are granted here.
CREATE SCHEMA IF NOT EXISTS iteration2_v2;

CREATE TABLE IF NOT EXISTS iteration2_v2.calendar_coverage (
    -- The year is the replacement/locking key, not proof of full-year support.
    -- Reviewed bounded bundles retain their supported dates in source_manifest.
    year integer PRIMARY KEY CHECK (year BETWEEN 1 AND 9999),
    build_id text NOT NULL CHECK (build_id ~ '^[a-f0-9]{64}$'),
    policy_version text NOT NULL CHECK (length(policy_version) > 0),
    -- Source verification can precede publication; retain both timestamps so
    -- a recent load does not make old evidence appear freshly checked.
    verified_at timestamptz NOT NULL,
    loaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- SQL checks only the JSON container type. The calendar loader validates
    -- the manifest's schema, source evidence, coverage and matching period rows.
    source_manifest jsonb NOT NULL CHECK (jsonb_typeof(source_manifest) = 'object')
);

COMMENT ON TABLE iteration2_v2.calendar_coverage IS
    'January 1 through December 31 within source_manifest coverage_scope; regional completeness must be read from the manifest. Later source changes require refresh. Published atomically with calendar_period.';
COMMENT ON COLUMN iteration2_v2.calendar_coverage.source_manifest IS
    'Full validated publication manifest, including source provenance and the canonical calendar CSV hash used to detect period-row drift.';
COMMENT ON COLUMN iteration2_v2.calendar_coverage.year IS
    'Year of the rule evaluation date; an overnight window may need the preceding year at a January 1 observation.';
