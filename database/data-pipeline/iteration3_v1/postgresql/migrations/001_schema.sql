-- Purpose: create the persisted speed-data contract for the offline pipeline.
-- Usage: `python -m iteration3_v1_pipeline migrate` applies this file before
-- 002_crash.sql and 003_notification.sql in the same transaction. Use an owner
-- or migration role; the backend's read role does not create these objects.
-- Input: no source files or bound parameters. Output: three speed tables,
-- their constraints/index, and a geometry-normalizing write trigger.
-- Reapplication preserves rows; CREATE TABLE IF NOT EXISTS does not upgrade
-- arbitrary pre-existing table definitions. No dataset load or role grants
-- occur here. The backend implements request-time selection and rule evaluation;
-- this migration does not install a lookup function.
-- Own only iteration2_v2 objects; PostGIS is the shared database extension.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS iteration2_v2;

-- One row is a source Speed Zone feature, not a whole physical road. A NULL
-- normal speed is allowed for unresolved/conditional zones. Stored status is
-- a build classification; scheduled/inactive API results are evaluated later.
CREATE TABLE IF NOT EXISTS iteration2_v2.road_speed (
    source_speed_zone_id uuid PRIMARY KEY,
    -- NULL keeps an unconfirmed base speed distinct from a valid posted speed.
    -- A CHECK alone permits NULL; legacy rows receive the stricter checks below.
    speed_kmh smallint CHECK (speed_kmh BETWEEN 1 AND 200),
    road_geom geography(MultiLineString, 4326) NOT NULL,
    is_legacy boolean NOT NULL,
    direction smallint CHECK (direction BETWEEN 0 AND 359),
    conditions text[] NOT NULL,
    status text NOT NULL CHECK (status IN ('static', 'scheduled', 'dynamic', 'unresolved')),
    CHECK (NOT is_legacy OR speed_kmh IS NOT NULL),
    CHECK (NOT is_legacy OR status = 'static'),
    CHECK (NOT ST_IsEmpty(road_geom::geometry))
);

COMMENT ON COLUMN iteration2_v2.road_speed.speed_kmh IS
    'Original v2 static speed for legacy rows; source-derived off-hours speed or NULL for conditional rows.';
COMMENT ON COLUMN iteration2_v2.road_speed.source_speed_zone_id IS
    'Transport Victoria Speed Zone feature UUID; not a physical-road ID and not a crash foreign key.';

-- This transformation is identical to iteration1_v2, including its tolerance.
-- EPSG:7899 provides metre units for a 1 m topology-preserving simplification;
-- the result returns to WGS84 geography. INSERT/COPY and geometry updates run
-- this trigger. Invalid/empty results abort the surrounding write transaction.
CREATE OR REPLACE FUNCTION iteration2_v2.simplify_road_speed_geometry()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    simplified geometry(MultiLineString, 4326);
BEGIN
    simplified := ST_Multi(
        ST_Transform(
            ST_SimplifyPreserveTopology(
                ST_Transform(NEW.road_geom::geometry, 7899),
                1.0
            ),
            4326
        )
    )::geometry(MultiLineString, 4326);

    IF ST_IsEmpty(simplified) OR NOT ST_IsValid(simplified) THEN
        RAISE EXCEPTION 'Road geometry became invalid during 1 metre simplification';
    END IF;

    NEW.road_geom := simplified::geography;
    RETURN NEW;
END;
$function$;

-- Recreate only the trigger definition; this does not rewrite existing rows.
DROP TRIGGER IF EXISTS road_speed_simplify_before_write
    ON iteration2_v2.road_speed;
CREATE TRIGGER road_speed_simplify_before_write
BEFORE INSERT OR UPDATE OF road_geom
ON iteration2_v2.road_speed
FOR EACH ROW
EXECUTE FUNCTION iteration2_v2.simplify_road_speed_geometry();

-- Geography supports indexed distance searches using metre-valued radii.
CREATE INDEX IF NOT EXISTS road_speed_geom_gix
    ON iteration2_v2.road_speed USING gist (road_geom);

-- One row is an evidenced conditional speed window for a retained zone.
-- ISO weekdays are 1..7; minutes are Melbourne local wall time, start-inclusive
-- and end-exclusive. Overnight rules use their start date for calendar checks.
-- source_sign_ids retains provenance; there is no runtime sign table to join.
CREATE TABLE IF NOT EXISTS iteration2_v2.speed_rule (
    source_speed_zone_id uuid NOT NULL
        REFERENCES iteration2_v2.road_speed (source_speed_zone_id),
    rule_no smallint NOT NULL CHECK (rule_no > 0),
    speed_kmh smallint NOT NULL CHECK (speed_kmh BETWEEN 1 AND 200),
    -- This constraint restricts day values; build-time parsing determines which
    -- combination of days and calendar flags actually represents the sign.
    weekdays smallint[] NOT NULL
        CHECK (weekdays <@ ARRAY[1, 2, 3, 4, 5, 6, 7]::smallint[]),
    start_minute smallint NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
    end_minute smallint NOT NULL CHECK (end_minute BETWEEN 0 AND 1440),
    school_days boolean NOT NULL,
    public_holidays boolean NOT NULL,
    source_sign_ids text[] NOT NULL,
    PRIMARY KEY (source_speed_zone_id, rule_no),
    CHECK (start_minute <> end_minute)
);

COMMENT ON TABLE iteration2_v2.speed_rule IS
    'Melbourne-local windows: ISO weekdays 1..7, end-exclusive minutes, 1440 means 24:00; start > end crosses midnight.';

-- The calendar loader publishes these inclusive intervals from verified bundles.
-- They are independent of the historical crash years. Regional uncertainty is
-- preserved for the query evaluator; it is not treated as a statewide holiday.
-- A term and a holiday may overlap because they describe different facts.
-- Coverage is recorded separately by 004_calendar_coverage.sql; rows alone
-- do not establish that every required date in a year has been verified.
CREATE TABLE IF NOT EXISTS iteration2_v2.calendar_period (
    kind text NOT NULL CHECK (kind IN ('SCHOOL_TERM', 'PUBLIC_HOLIDAY', 'REGIONAL_HOLIDAY_UNCERTAIN')),
    start_date date NOT NULL,
    end_date date NOT NULL,
    PRIMARY KEY (kind, start_date, end_date),
    CHECK (start_date <= end_date)
);
COMMENT ON TABLE iteration2_v2.calendar_period IS
    'Victoria reference dates. Regional uncertainty dates cannot resolve location-dependent holidays without regional boundaries.';
