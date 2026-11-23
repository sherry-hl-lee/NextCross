from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .schemas import to_camel


TimePeriod = Literal["morning", "daytime", "night", "late_night"]
CrashStatus = Literal[
    "recorded",
    "no_recorded_crashes",
    "outside_dataset_period",
    "no_dataset",
]


class HistoricalCrashQuery(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False)
    radius_meters: float = Field(gt=0, le=50_000, allow_inf_nan=False)
    time_period: TimePeriod
    start_date: date | None = None
    end_date: date | None = None

    @model_validator(mode="after")
    def validate_date_order(self):
        if self.start_date and self.end_date and self.start_date > self.end_date:
            raise ValueError("startDate must be on or before endDate")
        return self


class CircleArea(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    kind: Literal["circle"] = "circle"
    latitude: float
    longitude: float
    radius_meters: float


class CrashTotals(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    crash_count: int = Field(ge=0)
    fatalities: int = Field(ge=0)
    serious_injuries: int = Field(ge=0)


class CrashCluster(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    hotspot_id: str
    latitude: float
    longitude: float
    location_label: str | None = None
    crash_count: int = Field(ge=0)
    fatalities: int = Field(ge=0)
    serious_injuries: int = Field(ge=0)
    display_point_source: str | None = None
    representative_latitude: float | None = None
    representative_longitude: float | None = None


class IndividualCrashPoint(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    accident_id: str
    crash_date: date
    time_period: TimePeriod
    latitude: float
    longitude: float
    location_label: str | None = None
    crash_count: Literal[1] = 1
    fatalities: int = Field(ge=0)
    serious_injuries: int = Field(ge=0)


class HistoricalCrashResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    status: CrashStatus
    time_period: TimePeriod
    build_id: str | None = None
    period_start: date | None = None
    period_end: date | None = None
    requested_start_date: date | None = None
    requested_end_date: date | None = None
    dataset: dict | None = None
    count_scope: str | None = None
    area: CircleArea
    totals: CrashTotals | None
    clusters: list[CrashCluster] = Field(default_factory=list)
    individual_points: list[IndividualCrashPoint] = Field(default_factory=list)
