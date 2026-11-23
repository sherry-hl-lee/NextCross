from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .schemas import to_camel


class RecordedDrivingEvent(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    id: int = Field(ge=0)
    trip_id: str = Field(min_length=1, max_length=200)
    type: Literal["HardAcceleration", "HardBraking"]
    recorded_at_millis: int = Field(ge=0)
    acceleration_magnitude: float = Field(ge=0, le=100, allow_inf_nan=False)


class TripPromptRequest(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    trip_id: str = Field(min_length=1, max_length=200)
    events: list[RecordedDrivingEvent] = Field(max_length=100)


class EventSuggestion(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    event_id: int
    type: Literal["HardAcceleration", "HardBraking"]
    recorded_at_millis: int
    prompt: str


class TripPromptResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    trip_id: str
    suggestions: list[EventSuggestion]
    generation_mode: Literal["rule_based_prototype"]
    disclaimer: str

