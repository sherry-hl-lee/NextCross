-- Purpose: create the complete, minimal Iteration 1 v2 database contract.
-- Usage: apply through `python -m iteration1_v2_pipeline migrate` with a Neon
-- Direct owner connection. The Python command applies this file and verifies
-- the resulting tables, columns, constraints, indexes, trigger, and SRIDs in
-- one transaction. This file contains no credentials or environment settings.

-- PostGIS supplies the geography type and indexed distance operations.
CREATE EXTENSION IF NOT EXISTS postgis;

-- Keep this MVP isolated from retained iteration schemas and application data.
CREATE SCHEMA IF NOT EXISTS iteration1_v2;
COMMENT ON SCHEMA iteration1_v2 IS
    'Neon Free-oriented static road-speed lookup and notification delivery state.';

-- One row represents one retained Transport Victoria Normal Operation
-- speed-zone feature. Geography uses WGS 84 so GPS distance is measured in
-- metres by ST_DWithin and ST_Distance.
CREATE TABLE IF NOT EXISTS iteration1_v2.road_speed (
    source_speed_zone_id uuid PRIMARY KEY,
    speed_kmh smallint NOT NULL,
    road_geom geography(MultiLineString, 4326) NOT NULL,
    CONSTRAINT road_speed_speed_kmh_ck
        CHECK (speed_kmh BETWEEN 1 AND 200),
    CONSTRAINT road_speed_geom_nonempty_ck
        CHECK (NOT ST_IsEmpty(road_geom::geometry))
);

-- The composite key supports idempotent inserts when the application reuses
-- the same event identity and handles conflicts. It does not by itself
-- guarantee exactly-once delivery. Application code owns status transitions;
-- this table only restricts the allowed persisted states.
CREATE TABLE IF NOT EXISTS iteration1_v2.notification_event (
    trip_id text NOT NULL,
    event_id text NOT NULL,
    occurred_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    resend_email_id text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at timestamptz,
    CONSTRAINT notification_event_pkey
        PRIMARY KEY (trip_id, event_id),
    CONSTRAINT notification_event_status_ck
        CHECK (status IN ('pending', 'sent', 'failed'))
);

-- Reduce storage before every geometry write. EPSG:7899 provides metric units
-- for the one-metre tolerance, after which the result returns to WGS 84.
CREATE OR REPLACE FUNCTION iteration1_v2.simplify_road_speed_geometry()
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
COMMENT ON FUNCTION iteration1_v2.simplify_road_speed_geometry() IS
    'Simplifies road_speed geography by one metre in EPSG:7899 before storage.';

-- Recreate the trigger deterministically when this migration is reapplied.
DROP TRIGGER IF EXISTS road_speed_simplify_before_write
    ON iteration1_v2.road_speed;
CREATE TRIGGER road_speed_simplify_before_write
BEFORE INSERT OR UPDATE OF road_geom
ON iteration1_v2.road_speed
FOR EACH ROW
EXECUTE FUNCTION iteration1_v2.simplify_road_speed_geometry();
COMMENT ON TRIGGER road_speed_simplify_before_write
    ON iteration1_v2.road_speed IS
    'Applies the one-metre topology-preserving simplification before writes.';

-- This is the only non-primary-key index required by the MVP lookup query.
CREATE INDEX IF NOT EXISTS road_speed_geom_gix
    ON iteration1_v2.road_speed
    USING gist (road_geom);

COMMENT ON TABLE iteration1_v2.road_speed IS
    'Current static Normal Operation speed and 1m-simplified road geography.';
COMMENT ON COLUMN iteration1_v2.road_speed.source_speed_zone_id IS
    'Transport Victoria Speed Zone feature UUID; not a physical-road ID.';
COMMENT ON COLUMN iteration1_v2.road_speed.speed_kmh IS
    'Stored static Normal Operation speed in kilometres per hour.';
COMMENT ON COLUMN iteration1_v2.road_speed.road_geom IS
    'Non-empty WGS 84 MultiLineString geography used for proximity lookup.';

COMMENT ON TABLE iteration1_v2.notification_event IS
    'MVP delivery state keyed by trip and event; not an exactly-once queue.';
COMMENT ON COLUMN iteration1_v2.notification_event.trip_id IS
    'Application-defined trip identifier and first part of the primary key.';
COMMENT ON COLUMN iteration1_v2.notification_event.event_id IS
    'Stable logical notification ID within a trip; reuse it for retries.';
COMMENT ON COLUMN iteration1_v2.notification_event.occurred_at IS
    'Time at which the underlying application event occurred.';
COMMENT ON COLUMN iteration1_v2.notification_event.status IS
    'Current delivery state: pending, sent, or failed.';
COMMENT ON COLUMN iteration1_v2.notification_event.resend_email_id IS
    'Optional delivery identifier returned by the Resend provider.';
COMMENT ON COLUMN iteration1_v2.notification_event.error_message IS
    'Optional sanitized description of the latest delivery failure.';
COMMENT ON COLUMN iteration1_v2.notification_event.created_at IS
    'Database timestamp at which this delivery-state row was created.';
COMMENT ON COLUMN iteration1_v2.notification_event.sent_at IS
    'Successful-delivery timestamp; null until delivery succeeds.';
