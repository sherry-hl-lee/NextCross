"""Verify offline crash selection, classification and cluster preparation.

Synthetic CSV inputs exercise seasonal boundary times, duplicate/source errors,
fixed cluster membership, noise and missing-location conservation, and output
publication on invalid input. Tests use temporary files and no live database or
HTTP source. Coordinate fixtures are generated in the pipeline's metric CRS.
"""

import csv
from datetime import date
import json
from pathlib import Path
import sys
import tempfile
import unittest

from pyproj import Transformer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from iteration3_v1_pipeline.crash import (  # noqa: E402
    SOURCE_FIELDS, build_crashes, classify_time_period, sha256,
)


UNPROJECT = Transformer.from_crs(3111, 4326, always_xy=True)
LOCATION_FIELDS = ("ACCIDENT_NO", "ROAD_NAME", "ROAD_TYPE", "ROAD_NAME_INT", "ROAD_TYPE_INT")


def crash(identifier, *, x=2500250, y=2420250, **changes):
    longitude, latitude = UNPROJECT.transform(x, y)
    result = {"ACCIDENT_NO": identifier, "ACCIDENT_DATE": "2023-01-15", "ACCIDENT_TIME": "09:00:00",
              "LATITUDE": str(latitude), "LONGITUDE": str(longitude), "YOUNG_DRIVER_18_25": "1",
              "INJ_OR_FATAL": "3", "FATALITY": "1", "SERIOUSINJURY": "1", "OTHERINJURY": "1",
              "ROAD_NAME": "SOURCE ROAD", "ROAD_GEOMETRY": "T intersection", "LGA_NAME": "REGIONAL COUNCIL"}
    result.update(changes)
    return result


def write_source(path, rows, *, bom=False):
    with path.open("w", newline="", encoding="utf-8-sig" if bom else "utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=[*SOURCE_FIELDS, "ROAD_NAME", "ROAD_GEOMETRY", "LGA_NAME"])
        writer.writeheader()
        writer.writerows(rows)


def write_locations(path, rows):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=LOCATION_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def read_events(directory):
    with (directory / "crash_event.csv").open(newline="", encoding="utf-8") as stream:
        return {row["accident_id"]: row for row in csv.DictReader(stream)}


class TimePolicyTests(unittest.TestCase):
    def test_all_seasonal_starts_and_ends(self):
        for month, morning, night in ((1, 6, 20), (4, 7, 18), (7, 7, 17), (10, 6, 19)):
            cases = [("00:00:00", "late_night"), (f"{morning-1:02}:59:59", "late_night"),
                     (f"{morning:02}:00:00", "morning"), ("11:59:59", "morning"),
                     ("12:00:00", "daytime"), (f"{night-1:02}:59:59", "daytime"),
                     (f"{night:02}:00:00", "night"), ("23:59:59", "night")]
            for clock, expected in cases:
                with self.subTest(month=month, clock=clock):
                    self.assertEqual(classify_time_period(date(2025, month, 15), clock), expected)

    def test_season_changes_and_source_time_formats(self):
        for day, clock, expected in (("2025-02-28", "06:30", "morning"), ("2025-03-01", "06:30", "late_night"),
             ("2025-05-31", "17:30", "daytime"), ("2025-06-01", "17:30", "night"),
             ("2025-08-31", "18:30", "night"), ("2025-09-01", "18:30", "daytime"),
             ("2025-11-30", "19:30", "night"), ("2025-12-01", "19:30", "daytime"),
             ("15/01/2025", "6:00:00", "morning"), ("2021-04-22", "19:55:00.0000000", "night")):
            self.assertEqual(classify_time_period(day, clock), expected)
        for clock in ("", "Unknown", "24:00:00", "12:00:00+10:00"):
            with self.subTest(clock=clock), self.assertRaises(ValueError):
                classify_time_period("2025-01-15", clock)


class CrashBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.locations, self.output = self.root / "source.csv", self.root / "locations.csv", self.root / "out"
        write_locations(self.locations, [])

    def build(self, rows, *, bom=False):
        write_source(self.source, rows, bom=bom)
        return build_crashes(self.source, self.output, location_source=self.locations)

    def test_fixed_all_time_chain_noise_and_missing_location_conserve_events(self):
        # A chain can exceed 100m radius; periods must not split its membership.
        rows = [crash(str(i), x=2500250 + 90*i, ACCIDENT_TIME="09:00:00" if i % 2 else "20:00:00") for i in range(5)]
        rows += [crash("noise", x=2501500), crash("unlocated", LATITUDE="", LONGITUDE=""),
                 crash("older", ACCIDENT_DATE="2020-12-31"), crash("future", ACCIDENT_DATE="2026-01-01"),
                 crash("no-young", YOUNG_DRIVER_18_25="0"),
                 crash("no-injury", INJ_OR_FATAL="0", FATALITY="0", SERIOUSINJURY="0", OTHERINJURY="0")]
        report = self.build(rows)
        events = read_events(self.output)
        self.assertEqual(report["selected_totals"], {"crash_count": 7, "fatalities": 7, "serious_injuries": 7})
        self.assertEqual(report["hotspot_count"], 1)
        self.assertEqual(report["clustered_totals"]["crash_count"], 5)
        self.assertEqual(report["noise_totals"]["crash_count"], 1)
        self.assertEqual(report["missing_coordinate_totals"]["crash_count"], 1)
        self.assertGreater(report["maximum_representative_radius_m"], 170)
        self.assertEqual({events[str(i)]["hotspot_id"] for i in range(5)}, {"hotspot:0"})
        self.assertEqual(events["noise"]["hotspot_id"], "")
        self.assertTrue(events["noise"]["accident_point"])
        self.assertEqual(events["unlocated"]["accident_point"], "")
        self.assertEqual(events["unlocated"]["hotspot_id"], "")
        self.assertNotIn("casualties", events["noise"])
        self.assertNotIn("other_injuries", events["noise"])
        self.assertEqual(report["counts"]["zero_casualty_excluded"], 1)
        self.assertEqual(report["source_date_min"], "2020-12-31")
        self.assertEqual(report["source_date_max"], "2026-01-01")
        self.assertEqual(report, json.loads((self.output / "crash_report.json").read_text()))
        for artifact in report["files"].values():
            self.assertEqual(artifact["sha256"], sha256(self.output / artifact["file"]))
        self.assertFalse(list(self.output.glob("*.part")))

    def test_duplicate_occurrences_invalid_counts_and_times_are_reported(self):
        rows = [crash("duplicate"), crash("duplicate", ACCIDENT_DATE="2022-07-01"),
                crash("bad-count", INJ_OR_FATAL="7"), crash("bad-time", ACCIDENT_TIME="24:00:00"),
                crash("bad-point", LATITUDE="NaN"), crash("good")]
        report = self.build(rows)
        self.assertEqual(report["excluded"], {"missing_id_rows": 0, "duplicate_id_rows": 2,
                         "invalid_count_rows": 1, "invalid_time_rows": 1})
        self.assertEqual(set(read_events(self.output)), {"bad-point", "good"})
        self.assertEqual(report["missing_coordinate_totals"]["crash_count"], 1)

    def test_location_labels_use_intersection_flag_and_keep_missing_join_fallback(self):
        write_locations(self.locations, [dict(zip(LOCATION_FIELDS, ("junction", "MAIN", "ROAD", "SIDE", "STREET"))),
                                        dict(zip(LOCATION_FIELDS, ("midblock", "MAIN", "ROAD", "SIDE", "STREET")))])
        self.build([crash("junction"), crash("midblock", ROAD_GEOMETRY="Not at intersection"), crash("unmatched")])
        events = read_events(self.output)
        self.assertEqual(events["junction"]["location_label"], "MAIN ROAD × SIDE STREET")
        self.assertEqual(events["midblock"]["location_label"], "MAIN ROAD / near SIDE STREET")
        self.assertEqual(events["unmatched"]["location_label"], "SOURCE ROAD")

    def test_input_row_order_does_not_change_cluster_memberships(self):
        rows = [crash("a", x=2500250), crash("b", x=2500270), crash("c", x=2500300), crash("d", x=2502000)]
        self.build(rows)
        first = {k: v["hotspot_id"] for k, v in read_events(self.output).items()}
        self.build(list(reversed(rows)))
        self.assertEqual(first, {k: v["hotspot_id"] for k, v in read_events(self.output).items()})

    def test_bom_dmy_fractional_time_and_empty_cohort(self):
        report = self.build([crash("fraction", ACCIDENT_DATE="22/04/2021", ACCIDENT_TIME="19:55:00.0000000")], bom=True)
        self.assertEqual(read_events(self.output)["fraction"]["time_period"], "night")
        self.assertEqual(report["by_year"]["2021"]["crash_count"], 1)
        report = self.build([crash("old", ACCIDENT_DATE="2020-01-01")])
        self.assertEqual(report["selected_totals"]["crash_count"], 0)
        self.assertEqual(read_events(self.output), {})

    def test_bad_input_does_not_replace_previous_prepared_output(self):
        self.build([crash("good")])
        previous = (self.output / "crash_event.csv").read_bytes()
        self.source.write_text("ACCIDENT_NO\nbad\n")
        with self.assertRaisesRegex(ValueError, "Missing crash source columns"):
            build_crashes(self.source, self.output, location_source=self.locations)
        self.assertEqual(previous, (self.output / "crash_event.csv").read_bytes())


if __name__ == "__main__":
    unittest.main()
