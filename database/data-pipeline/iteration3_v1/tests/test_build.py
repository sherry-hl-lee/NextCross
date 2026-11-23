"""Verify offline speed preparation with small synthetic GeoJSON fixtures.

These tests cover legacy parity, sign eligibility, schedule normalization,
statewide coverage and spatial matching ambiguity. Temporary input/output files
exercise build.py and signs.py without network access or a PostgreSQL database.
Run through ``PYTHONPATH=src:tests python -m unittest test_build -v``.
"""

import copy
import csv
from datetime import date
import json
from pathlib import Path
import sys
import tempfile
import unittest
from uuid import UUID

from pyproj import Transformer
from shapely.geometry import LineString


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

import iteration3_v1_pipeline.build as builder  # noqa: E402
from iteration3_v1_pipeline.signs import (  # noqa: E402
    is_regulatory, sign_rules, travel_heading,
)


WEEKDAYS = "Monday, Tuesday, Wednesday, Thursday, Friday"
TO_METRIC = Transformer.from_crs(4326, 7899, always_xy=True)
TO_WGS84 = Transformer.from_crs(7899, 4326, always_xy=True)
ORIGIN = TO_METRIC.transform(145.13, -37.90)  # Well inside Monash.


def school_sign(**changes):
    properties = {
        "speed_sign_id": "school-1", "speed": "40",
        "sign_type": "School Zone Speed Limit (Narrow Format)",
        "variable_calendar_type": "School", "road_name": "MAIN",
        "road_type": "ROAD", "bearing": "180", "facing": "South",
        "variable_time_1_from": "8:00", "variable_time_1_to": "9:30",
        "variable_time_1_days": WEEKDAYS,
    }
    properties.update(changes)
    return properties


def coordinates(x, y):
    return list(TO_WGS84.transform(ORIGIN[0] + x, ORIGIN[1] + y))


def road_feature(number=1, *, x=0, conditions=None):
    return {
        "type": "Feature", "id": str(UUID(int=number)),
        "properties": {"speed_limit": "40", "direction": "North",
                       "zone_conditions": conditions or ["School"]},
        "geometry": {"type": "LineString", "coordinates": [coordinates(x, 0), coordinates(x, 100)]},
    }


def sign_feature(properties, x=0, y=20):
    return {"type": "Feature", "properties": properties,
            "geometry": {"type": "Point", "coordinates": coordinates(x, y)}}


def normal_road(number, start_y, end_y, *, speed=60, x=0):
    feature = road_feature(number, conditions=["Normal Operation"])
    feature["properties"]["speed_limit"] = str(speed)
    feature["geometry"]["coordinates"] = [coordinates(x, start_y), coordinates(x, end_y)]
    return feature


def base_sign(speed=60, **changes):
    properties = {"speed_sign_id": "base-1", "speed": str(speed),
                  "sign_type": "Standard Speed Limit", "road_name": "MAIN",
                  "road_type": "ROAD", "bearing": "180", "facing": "South"}
    properties.update(changes)
    return properties


def run_build(roads, signs):
    """Use the real parser, projections, statewide matching, and CSV writer."""
    with tempfile.TemporaryDirectory(prefix="iteration2-build-test-") as directory:
        directory = Path(directory)
        zones_path, signs_path = directory / "zones.geojson", directory / "signs.geojson"
        for path, features in ((zones_path, roads), (signs_path, signs)):
            path.write_text(json.dumps({"type": "FeatureCollection", "features": features}))
        output = directory / "processed"
        counts = builder.build(zones_path, signs_path, output)
        with (output / "road_speed.csv").open(newline="") as stream:
            rows = list(csv.DictReader(stream))
        with (output / "speed_rule.csv").open(newline="") as stream:
            rules = list(csv.DictReader(stream))
        report = json.loads((output / "report.json").read_text())
        return counts, rows, rules, report


class SignNormalizationTests(unittest.TestCase):
    def test_advisory_supplementary_and_end_signs_are_not_speed_rules(self):
        for kind in ["Supplimentaty Signs", "Supplementary Signs",
                     "Speed Limit AHEAD", "New Speed Limit Ahead Sign",
                     "Speed Limit AHEAD on Side Road (With Operating Times)",
                     "END Speed Limit AREA", "END Shared Zone"]:
            with self.subTest(kind=kind):
                properties = school_sign(sign_type=kind)
                self.assertFalse(is_regulatory(properties))
                self.assertEqual(sign_rules(properties), ())

    def test_removed_or_pending_signs_do_not_generate_rules(self):
        for change in [{"date_sign_removed": "2026-08-01"},
                       {"speed_sign_change_request_status": "Installation Pending"}]:
            with self.subTest(change=change):
                self.assertEqual(sign_rules(school_sign(**change)), ())

    def test_future_or_unparseable_installation_date_cannot_evidence_current_limit(self):
        properties = school_sign(date_sign_installed="8/08/2027 00:00:00 (UTC+10:00)",
                                 speed_sign_change_request_status="Closed")
        self.assertEqual(sign_rules(properties, as_of=date(2026, 9, 12)), ())
        self.assertTrue(sign_rules(properties, as_of=date(2027, 8, 8)))
        self.assertEqual(sign_rules(school_sign(date_sign_installed="unknown")), ())

    def test_school_0830_is_retained_and_second_window_inherits_available_days(self):
        rules = sign_rules(school_sign(
            variable_time_1_from="8:30",
            variable_time_1_days="Friday, Monday, Tuesday, Thursday, Wednesday",
            variable_time_2_from="14:30", variable_time_2_to="16:00"))
        self.assertEqual([(r.start_minute, r.end_minute) for r in rules], [(510, 570), (870, 960)])
        self.assertTrue(all(r.weekdays == (1, 2, 3, 4, 5) and r.school_days for r in rules))

    def test_single_shop_window_uses_days_stored_in_empty_second_slot(self):
        rules = sign_rules(school_sign(
            variable_calendar_type="Shop", sign_type="Speed Limit Sign (Single Time of Operation)",
            variable_time_1_from="8:00", variable_time_1_to="20:00", variable_time_1_days=None,
            variable_time_2_days="Saturday, Sunday"))
        self.assertEqual(len(rules), 1)
        self.assertEqual((rules[0].weekdays, rules[0].start_minute, rules[0].end_minute), ((6, 7), 480, 1200))
        self.assertFalse(rules[0].school_days)

    def test_overnight_and_2400_are_preserved(self):
        for start, end, expected in [("21:00", "3:00", (1260, 180)), ("7:00", "24:00", (420, 1440))]:
            with self.subTest(start=start, end=end):
                rule = sign_rules(school_sign(variable_calendar_type="Shop",
                    variable_time_1_from=start, variable_time_1_to=end))[0]
                self.assertEqual((rule.start_minute, rule.end_minute), expected)

    def test_missing_time_or_all_days_is_not_filled_with_school_defaults(self):
        for changes in [{"variable_time_1_to": None}, {"variable_time_1_days": None}]:
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError):
                    sign_rules(school_sign(**changes))
        self.assertEqual(sign_rules(school_sign(variable_time_1_from=None, variable_time_1_to=None)), ())

    def test_sign_face_is_opposite_travel_and_conflicting_orientation_is_unknown(self):
        self.assertEqual(travel_heading({"bearing": "180", "facing": "South"}), 0)
        self.assertEqual(travel_heading({"facing": "West"}), 90)
        self.assertIsNone(travel_heading({"bearing": "188", "facing": "North"}))


class MatchingTests(unittest.TestCase):
    def test_components_join_endpoint_bends_but_not_interior_crossings(self):
        def segment(number, points, heading):
            return builder.Road(
                id=str(UUID(int=number)), source_speed=40, ewkt="",
                geometry=LineString(points), direction=heading,
                conditions=("School",), legacy=False,
            )

        crossing = [segment(1, [(0, -100), (0, 100)], 0),
                    segment(2, [(-100, -100), (100, 100)], 45)]
        self.assertEqual(sorted(len(group) for group in builder.components(crossing)), [1, 1])

        bend = [segment(1, [(0, -100), (0, 0)], 0),
                segment(2, [(0, 0), (100, 100)], 45)]
        groups = list(builder.components(bend))
        self.assertEqual(len(groups), 1)
        self.assertEqual({road.id for road in groups[0]}, {road.id for road in bend})

    def test_same_road_schedule_and_endpoint_base_ignore_opposite_direction(self):
        signs = [sign_feature(school_sign()), sign_feature(base_sign(), y=100),
                 sign_feature(school_sign(speed_sign_id="opposite-school", bearing="0", facing="North",
                                         variable_time_1_from="8:30")),
                 sign_feature(base_sign(80, speed_sign_id="opposite-base", bearing="0", facing="North"), y=100)]
        _, roads, rules, _ = run_build([road_feature()], signs)
        self.assertEqual((roads[0]["status"], roads[0]["speed_kmh"]), ("scheduled", "60"))
        self.assertEqual(len(rules), 1)
        self.assertEqual(rules[0]["start_minute"], "480")
        self.assertNotIn("opposite", rules[0]["source_sign_ids"])

    def test_conflicting_or_missing_schedules_remain_unresolved(self):
        schedules = [
            [school_sign(), school_sign(speed_sign_id="school-2", variable_time_1_from="8:30")],
            [school_sign(variable_time_1_from=None, variable_time_1_to=None)],
            [school_sign(variable_time_1_to=None)],
        ]
        for properties in schedules:
            with self.subTest(properties=properties):
                signs = [sign_feature(p) for p in properties] + [sign_feature(base_sign(), y=100)]
                _, roads, rules, _ = run_build([road_feature()], signs)
                self.assertEqual(roads[0]["status"], "unresolved")
                self.assertEqual(roads[0]["speed_kmh"], "")
                self.assertEqual(rules, [])

    def test_neighboring_road_baseline_is_not_assigned_by_endpoint_distance(self):
        signs = [sign_feature(school_sign()),
                 sign_feature(base_sign(80, road_name="SERVICE"), x=20, y=100)]
        _, roads, rules, _ = run_build([road_feature()], signs)
        self.assertEqual(roads[0]["status"], "scheduled")
        self.assertTrue(rules)
        self.assertEqual(roads[0]["speed_kmh"], "")

    def test_schedule_on_parallel_road_does_not_resolve_both_roads(self):
        roads = [road_feature(1, x=0), road_feature(2, x=20)]
        signs = [sign_feature(school_sign(), x=20), sign_feature(base_sign(), x=20, y=100)]
        _, rows, _, _ = run_build(roads, signs)
        rows = {row["source_speed_zone_id"]: row for row in rows}
        self.assertEqual(rows[str(UUID(int=1))]["status"], "unresolved")
        self.assertEqual(rows[str(UUID(int=1))]["speed_kmh"], "")
        self.assertEqual(rows[str(UUID(int=2))]["status"], "scheduled")

    def test_agreeing_normal_roads_connected_to_both_ends_supply_base(self):
        roads = [road_feature(), normal_road(2, -100, 0), normal_road(3, 100, 200)]
        _, rows, rules, report = run_build(roads, [sign_feature(school_sign())])
        conditional = next(row for row in rows if row["source_speed_zone_id"] == str(UUID(int=1)))
        self.assertEqual((conditional["status"], conditional["speed_kmh"]), ("scheduled", "60"))
        self.assertEqual(len(rules), 1)
        evidence = report["conditional_roads"][0]
        self.assertEqual(set(evidence["base_adjacent_zone_ids"]), {str(UUID(int=2)), str(UUID(int=3))})

    def test_nearby_or_disagreeing_normal_roads_do_not_supply_base(self):
        cases = {
            "nearby_without_endpoint_connection": [normal_road(2, -100, 0, x=3), normal_road(3, 100, 200, x=3)],
            "connected_but_different_speeds": [normal_road(2, -100, 0), normal_road(3, 100, 200, speed=80)],
        }
        for reason, neighbors in cases.items():
            with self.subTest(reason=reason):
                _, rows, rules, _ = run_build([road_feature(), *neighbors], [sign_feature(school_sign())])
                conditional = next(row for row in rows if row["source_speed_zone_id"] == str(UUID(int=1)))
                self.assertEqual(conditional["status"], "scheduled")
                self.assertEqual(conditional["speed_kmh"], "")
                self.assertEqual(len(rules), 1)

    def test_equidistant_sign_between_parallel_roads_resolves_neither(self):
        roads = [road_feature(1, x=0), road_feature(2, x=20)]
        _, rows, rules, _ = run_build(roads, [sign_feature(school_sign(), x=10)])
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(row["status"] == "unresolved" and row["speed_kmh"] == "" for row in rows))
        self.assertEqual(rules, [])


class LegacyParityTests(unittest.TestCase):
    def test_statewide_keeps_legacy_geometry_and_retains_unknown_nonlegacy_rows(self):
        normal = road_feature(1, conditions=["Normal Operation"])
        normal["properties"]["speed_limit"] = "60"
        normal["geometry"]["coordinates"] = [[144.90, -37.80, 12], [144.901, -37.80, 13]]
        normalized = copy.deepcopy(normal)
        normalized["id"] = str(UUID(int=2))
        normalized["properties"]["zone_conditions"] = " Normal Operation "
        normalized["properties"]["speed_limit"] = "80"
        normalized["geometry"]["coordinates"][1] = [144.901, -37.80]
        normal_zero = copy.deepcopy(normal)
        normal_zero["id"] = str(UUID(int=3))
        normal_zero["properties"]["speed_limit"] = "0"
        conditional = copy.deepcopy(normal)
        conditional["id"] = str(UUID(int=4))
        conditional["properties"]["zone_conditions"] = ["School"]
        mixed = copy.deepcopy(normal)
        mixed["id"] = str(UUID(int=5))
        mixed["properties"]["zone_conditions"] = ["Normal Operation", "School"]
        missing_geometry = copy.deepcopy(normal)
        missing_geometry["id"] = str(UUID(int=6))
        missing_geometry["geometry"] = None
        malformed_geometry = copy.deepcopy(normal)
        malformed_geometry["id"] = str(UUID(int=7))
        malformed_geometry["geometry"]["coordinates"] = [[144.90], [144.901, -37.80]]
        features = [normal, normalized, normal_zero, conditional, mixed,
                    missing_geometry, malformed_geometry]
        # Frozen baseline expectations keep this regression test independent
        # of the previous iteration's source code and this builder's helpers.
        ewkt = "SRID=4326;MULTILINESTRING((144.9 -37.8,144.901 -37.8))"
        expected = {str(UUID(int=1)): ("60", ewkt), str(UUID(int=2)): ("80", ewkt)}
        counts, rows, rules, _ = run_build(features, [])
        self.assertEqual({row["source_speed_zone_id"]: (row["speed_kmh"], row["road_geom"])
                          for row in rows if row["is_legacy"] == "True"}, expected)
        self.assertEqual(counts["legacy_rows"], 2)
        self.assertEqual(len(rows), 5)
        self.assertEqual(counts["unresolved_rows"], 3)
        self.assertEqual(counts["skipped_rows"], 2)
        self.assertTrue(all(row["speed_kmh"] == "" for row in rows if row["is_legacy"] == "False"))
        self.assertEqual(rules, [])


class StatewideTests(unittest.TestCase):
    def test_explicit_shared_zone_requires_owned_permanent_matching_signs(self):
        road = road_feature(conditions=["Other"])
        road["properties"].update(speed_limit="20", zone_conditions_other="Shared Zone")
        sign = sign_feature(base_sign(20, sign_type="Shared Zone"))
        _, rows, rules, report = run_build([road], [sign])
        self.assertEqual((rows[0]["status"], rows[0]["speed_kmh"]), ("static", "20"))
        self.assertEqual(rules, [])
        self.assertEqual(report["conditional_roads"][0]["reason"], "explicit_shared_zone_and_current_signs")
        for changes in [{"comments": "Seasonal during summer"}, {"variable_calendar_type": "Seasonal"}, {"speed": "40"}]:
            with self.subTest(changes=changes):
                bad = copy.deepcopy(sign)
                bad["properties"].update(changes)
                _, rows, _, _ = run_build([road], [bad])
                self.assertEqual((rows[0]["status"], rows[0]["speed_kmh"]), ("unresolved", ""))
        road["properties"].pop("zone_conditions_other")
        _, rows, _, _ = run_build([road], [sign])
        self.assertEqual(rows[0]["status"], "unresolved")

    def test_mixed_normal_school_uses_signed_window_but_does_not_guess_base(self):
        road = road_feature(conditions=["Normal Operation", "School"])
        _, rows, rules, report = run_build([road], [sign_feature(school_sign()), sign_feature(base_sign(), y=100)])
        self.assertEqual((rows[0]["status"], rows[0]["speed_kmh"]), ("scheduled", ""))
        self.assertEqual(rules[0]["speed_kmh"], "40")
        self.assertEqual(report["conditional_roads"][0]["reason"], "mixed_normal_annotation_base_unconfirmed")

    def test_geelong_school_schedule_is_resolved_without_any_lga_reference(self):
        road = road_feature()
        road["geometry"]["coordinates"] = [[144.36, -38.15], [144.36, -38.149]]
        scheduled = sign_feature(school_sign())
        scheduled["geometry"]["coordinates"] = [144.36, -38.1498]
        normal = sign_feature(base_sign())
        normal["geometry"]["coordinates"] = [144.36, -38.149]
        counts, roads, rules, _ = run_build([road], [scheduled, normal])
        self.assertEqual((roads[0]["status"], roads[0]["speed_kmh"]), ("scheduled", "60"))
        self.assertEqual(len(rules), 1)
        self.assertEqual(counts["road_rows"], 1)
        self.assertNotIn("in_scope", roads[0])

    def test_dynamic_and_unsupported_zones_are_retained_without_asserted_speeds(self):
        features = [road_feature(1, conditions=["Variable"]),
                    road_feature(2, x=200, conditions=["Activated"]),
                    road_feature(3, x=400, conditions=["Road Works"])]
        counts, roads, rules, _ = run_build(features, [])
        self.assertEqual(counts["road_rows"], 3)
        self.assertEqual([r["status"] for r in roads], ["dynamic", "dynamic", "unresolved"])
        self.assertTrue(all(r["speed_kmh"] == "" for r in roads))
        self.assertEqual(rules, [])

    def test_normal_road_closer_to_sign_prevents_false_conditional_assignment(self):
        roads = [road_feature(), normal_road(2, 0, 100, x=20)]
        counts, rows, rules, _ = run_build(roads, [sign_feature(school_sign(), x=20)])
        self.assertEqual(counts["normal_roads_retained_for_matching"], 1)
        conditional = next(r for r in rows if r["is_legacy"] == "False")
        self.assertEqual(conditional["status"], "unresolved")
        self.assertEqual(rules, [])

    def test_static_only_snapshot_can_have_zero_rules(self):
        counts, rows, rules, _ = run_build([normal_road(1, 0, 100)], [])
        self.assertEqual(counts["road_rows"], 1)
        self.assertEqual(rows[0]["status"], "static")
        self.assertEqual(rules, [])

    def test_invalid_source_id_is_reported_and_duplicate_id_rejected(self):
        invalid = road_feature()
        invalid["id"] = "not-a-uuid"
        counts, rows, _, _ = run_build([invalid, normal_road(2, 0, 100)], [])
        self.assertEqual(counts["skipped_invalid_id"], 1)
        self.assertEqual(len(rows), 1)
        with self.assertRaisesRegex(ValueError, "Duplicate source"):
            run_build([road_feature(), road_feature()], [])


if __name__ == "__main__":
    unittest.main()
