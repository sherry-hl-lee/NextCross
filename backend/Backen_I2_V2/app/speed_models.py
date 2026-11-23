"""Post-trip API contract. Coordinates are accepted only by the analysis API."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
from .schemas import to_camel


class Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True,
                              extra="forbid", allow_inf_nan=False)


class Sample(Model):
    sample_id: str = Field(min_length=1, max_length=100)
    observed_at_epoch_millis: int = Field(ge=0, le=4102444800000)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    speed_mps: float | None = Field(default=None, ge=0)
    accuracy_meters: float | None = Field(default=None, ge=0)
    bearing_degrees: float | None = Field(default=None, ge=0, lt=360)


class AnalysisRequest(Model):
    trip_id: str = Field(min_length=1, max_length=200)
    started_at_epoch_millis: int = Field(ge=0, le=4102444800000)
    ended_at_epoch_millis: int = Field(ge=0, le=4102444800000)
    samples: list[Sample] = Field(max_length=20000)

    @model_validator(mode="after")
    def valid_trip(self):
        if self.ended_at_epoch_millis < self.started_at_epoch_millis:
            raise ValueError("Trip end precedes start")
        if self.ended_at_epoch_millis - self.started_at_epoch_millis > 86400000:
            raise ValueError("Maximum supported trip duration is 24 hours")
        if len({s.sample_id for s in self.samples}) != len(self.samples):
            raise ValueError("Duplicate sample IDs")
        if any(not self.started_at_epoch_millis <= s.observed_at_epoch_millis <= self.ended_at_epoch_millis
               for s in self.samples):
            raise ValueError("Samples must be inside the trip time range")
        return self


class DetectionConfig(Model):
    # Engineering defaults, not legal tolerance. Team should calibrate with GPS fixtures.
    tolerance_kmh: float = Field(default=3, ge=0, le=30)
    minimum_duration_seconds: float = Field(default=2, gt=0, le=60)
    minimum_valid_samples: int = Field(default=3, ge=2, le=100)
    maximum_gap_seconds: float = Field(default=5, gt=0, le=60)
    maximum_accuracy_meters: float = Field(default=20, gt=0, le=100)
    maximum_speed_mps: float = Field(default=70, gt=0, le=150)
    lookup_radius_meters: float = Field(default=30, gt=0, le=1000)
    query_chunk_size: int = Field(default=500, ge=1, le=1000)


class SpeedContext(Model):
    speed_kmh: int | None = Field(default=None, ge=1, le=200)
    status: Literal["static", "scheduled", "inactive", "unknown", "dynamic_unknown", "ambiguous", "no_match"]
    source_speed_zone_id: str | None = None
    reason: str | None = None
    data_version: str | None = None


class SpeedEvent(Model):
    event_id: str
    trip_id: str
    type: Literal["SPEED_LIMIT_EXCEEDANCE"] = "SPEED_LIMIT_EXCEEDANCE"
    status: Literal["candidate"] = "candidate"
    start_sample_id: str
    end_sample_id: str
    start_offset_seconds: float
    end_offset_seconds: float
    duration_seconds: float
    reference_limit_kmh: int
    peak_speed_kmh: float
    peak_exceedance_kmh: float
    average_exceedance_kmh: float
    peak_exceedance_percent: float
    source_speed_zone_id: str
    limit_status: str
    data_quality: str = "GOOD"
    detector_version: str = "speed-v1"


class Interval(Model):
    start_offset_seconds: float
    end_offset_seconds: float
    reason: str


class TimelinePoint(Model):
    sample_id: str
    offset_seconds: float
    context: SpeedContext
    match_status: Literal["MATCHED", "UNKNOWN", "AMBIGUOUS"]
    reason: str | None = None


class AnalysisResponse(Model):
    trip_id: str
    analysis_status: Literal["COMPLETED", "PARTIAL", "INSUFFICIENT_DATA"]
    processed_sample_count: int
    road_matching_status: Literal["MATCHED", "PARTIAL", "UNKNOWN"]
    events: list[SpeedEvent]
    unmatched_intervals: list[Interval]
    timeline: list[TimelinePoint]
    normalization: list[str]
    detection_config: DetectionConfig
    matching_method: str = "conservative-adjacent-zone-v1"
    limitations: list[str] = Field(default_factory=lambda: [
        "Heuristic speed-zone matching, not routing-graph map matching or proof of the road travelled.",
        "Limits use the loaded snapshot and recorded-time schedules, not historical road-sign versions.",
        "Candidate events support reflection only; they do not establish illegal or dangerous driving.",
    ])
