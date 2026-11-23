-- Purpose: create the three-table contract for prepared historical crashes.
-- Usage: normal `migrate` applies this with the speed and notification DDL;
-- `crash-migrate` applies this file alone. Both use a migration-capable role.
-- Input: no raw data or query parameters. Clusters and time_period values are
-- calculated offline by crash.py, then inserted by crash_storage.py. This DDL
-- neither runs DBSCAN nor performs per-request area/time aggregation.
-- Reapplication preserves rows and does not upgrade arbitrary old columns.
-- Only the new iteration's crash objects; never alter speed or notification data.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS iteration2_v2;

-- Metadata identifies the input hashes and processing policy of the one active
-- build. A UNIQUE true-only singleton prevents retaining multiple active builds.
CREATE TABLE IF NOT EXISTS iteration2_v2.crash_build (
    build_id text PRIMARY KEY,
    -- Every row must use the same true value, so UNIQUE allows at most one
    -- active build even when a replacement has a different build_id.
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    start_date date NOT NULL,
    end_date date NOT NULL CHECK (end_date >= start_date),
    region text NOT NULL CHECK (region = 'Victoria'),
    cohort_policy text NOT NULL,
    eps_m integer NOT NULL CHECK (eps_m = 100),
    min_samples integer NOT NULL CHECK (min_samples = 3),
    crs_epsg integer NOT NULL CHECK (crs_epsg = 3111),
    time_policy_version text NOT NULL,
    cluster_policy_version text NOT NULL,
    source_sha256 text NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    location_source_sha256 text NOT NULL CHECK (location_source_sha256 ~ '^[a-f0-9]{64}$'),
    event_rows integer NOT NULL CHECK (event_rows >= 0),
    hotspot_rows integer NOT NULL CHECK (hotspot_rows >= 0),
    missing_point_rows integer NOT NULL CHECK (missing_point_rows BETWEEN 0 AND event_rows),
    loaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One fixed DBSCAN cluster. The representative is an actual member point used
-- for display, not a geometric boundary or the basis for spatial crash counts.
CREATE TABLE IF NOT EXISTS iteration2_v2.hotspot (
    hotspot_id text PRIMARY KEY,
    build_id text NOT NULL REFERENCES iteration2_v2.crash_build (build_id),
    representative_point geometry(Point, 4326) NOT NULL,
    location_label text NOT NULL,
    CHECK (NOT ST_IsEmpty(representative_point) AND ST_IsValid(representative_point))
);

-- One eligible source accident. No hotspot means an unclustered or unlocated
-- event; missing coordinates must also have no hotspot. Casualty counts cover
-- all ages involved in the young-driver-involved crash, not young drivers only.
CREATE TABLE IF NOT EXISTS iteration2_v2.crash_event (
    accident_id text PRIMARY KEY,
    build_id text NOT NULL REFERENCES iteration2_v2.crash_build (build_id),
    hotspot_id text REFERENCES iteration2_v2.hotspot (hotspot_id),
    crash_date date NOT NULL,
    time_period text NOT NULL CHECK (time_period IN ('morning', 'daytime', 'night', 'late_night')),
    accident_point geometry(Point, 4326),
    location_label text NOT NULL,
    fatalities integer NOT NULL CHECK (fatalities >= 0),
    serious_injuries integer NOT NULL CHECK (serious_injuries >= 0),
    -- Located noise points may have no cluster, but an unlocated event cannot
    -- claim cluster membership because DBSCAN only receives projected points.
    CHECK (accident_point IS NOT NULL OR hotspot_id IS NULL),
    CHECK (accident_point IS NULL OR (NOT ST_IsEmpty(accident_point) AND ST_IsValid(accident_point)))
);

-- Request-time queries filter actual located crash points in metres, then
-- aggregate the matching events. These indexes support that backend workload.
CREATE INDEX IF NOT EXISTS crash_event_geography_gix
    ON iteration2_v2.crash_event USING gist ((accident_point::geography))
    WHERE accident_point IS NOT NULL;
CREATE INDEX IF NOT EXISTS crash_event_hotspot_period_idx
    ON iteration2_v2.crash_event (hotspot_id, time_period);

COMMENT ON TABLE iteration2_v2.crash_build IS
    'One active statewide young-driver-involved injury-crash cohort; crash loader replaces all three tables atomically.';
COMMENT ON TABLE iteration2_v2.hotspot IS
    'Fixed all-time DBSCAN groups; 100 metres is neighbour distance, not maximum cluster radius. Representative is an actual member point.';
COMMENT ON TABLE iteration2_v2.crash_event IS
    'One source accident per row, including unclustered and unlocated cases. Fatalities and serious injuries count all ages involved.';
