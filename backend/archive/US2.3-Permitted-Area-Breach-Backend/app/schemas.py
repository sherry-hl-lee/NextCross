from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


def to_camel(field_name: str) -> str:
    first, *rest = field_name.split("_")
    return first + "".join(part.capitalize() for part in rest)


class NotificationEventRequest(BaseModel):
    """Wire format produced by Android's AreaBreachEvent."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        extra="forbid",
        populate_by_name=True,
    )

    trip_id: str = Field(min_length=1, max_length=200)
    event_id: str = Field(min_length=1, max_length=200)
    event_type: Literal["PERMITTED_AREA_BREACH"]
    occurred_at_epoch_millis: int = Field(ge=0)
    parent_email: str = Field(
        pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$",
        max_length=320,
    )


class NotificationEventResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    trip_id: str
    event_id: str
    duplicate: bool
    status: Literal["pending", "sent", "failed"]
