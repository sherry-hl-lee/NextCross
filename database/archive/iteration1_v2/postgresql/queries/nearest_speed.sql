-- Purpose: return the speed of the nearest retained road segment within a GPS
-- search radius. No row means the application must return {"speedKmh": null};
-- this query never invents a default speed.
--
-- Bind all named parameters in the application layer:
--   :longitude  WGS 84 longitude in degrees (-180 through 180)
--   :latitude   WGS 84 latitude in degrees (-90 through 90)
--   :radius_m   search radius in metres, above 0 and at most 1,000
--               (30 for the MVP default)
--
-- ST_DWithin uses road_speed_geom_gix to select nearby candidates. Exact
-- distance orders those candidates, and UUID supplies a stable tie-break.
WITH gps AS (
    SELECT ST_SetSRID(
        ST_MakePoint(:longitude, :latitude),
        4326
    )::geography AS location
)
SELECT road_speed.speed_kmh
FROM iteration1_v2.road_speed AS road_speed
CROSS JOIN gps
WHERE ST_DWithin(road_speed.road_geom, gps.location, :radius_m)
ORDER BY
    ST_Distance(road_speed.road_geom, gps.location),
    road_speed.source_speed_zone_id
LIMIT 1;
