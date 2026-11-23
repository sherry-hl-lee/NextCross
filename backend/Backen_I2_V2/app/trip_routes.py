from fastapi import APIRouter, HTTPException, status

from .trip_schemas import EventSuggestion, TripPromptRequest, TripPromptResponse


router = APIRouter(prefix="/trip-discussion-prompts", tags=["trip discussion prompts"])


@router.post("", response_model=TripPromptResponse, response_model_by_alias=True)
def create_trip_discussion_prompts(payload: TripPromptRequest) -> TripPromptResponse:
    event_ids: set[int] = set()
    suggestions: list[EventSuggestion] = []
    for event in payload.events:
        if event.trip_id != payload.trip_id:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Every event must belong to the requested tripId",
            )
        if event.id in event_ids:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Event ids must be unique within a trip",
            )
        event_ids.add(event.id)
        prompt = (
            "What was happening around this acceleration, and what might help make it smoother next time?"
            if event.type == "HardAcceleration"
            else "What was happening around this braking, and is there anything worth discussing for next time?"
        )
        suggestions.append(
            EventSuggestion(
                eventId=event.id,
                type=event.type,
                recordedAtMillis=event.recorded_at_millis,
                prompt=prompt,
            )
        )
    return TripPromptResponse(
        tripId=payload.trip_id,
        suggestions=suggestions,
        generationMode="rule_based_prototype",
        disclaimer=(
            "Discussion prompts only; they do not prove unsafe driving or establish the cause of an event."
        ),
    )

