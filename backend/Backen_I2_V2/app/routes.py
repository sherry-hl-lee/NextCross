from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, Request, Response, status

from .repository import breach_email_cooldown_seconds
from .schemas import (
    NotificationEventRequest,
    NotificationEventResponse,
    NotificationTripPurgeResponse,
)

router = APIRouter(prefix="/notification-events", tags=["notification events"])

COOLDOWN_SUPPRESSED_MESSAGE = "suppressed_by_cooldown"


@router.delete("/{trip_id}", response_model=NotificationTripPurgeResponse)
def purge_notification_events_for_trip(trip_id: str, request: Request) -> NotificationTripPurgeResponse:
    cleaned = trip_id.strip()
    if not cleaned or len(cleaned) > 200:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid tripId")
    deleted = request.app.state.repository.delete_for_trip(cleaned)
    return NotificationTripPurgeResponse(tripId=cleaned, deletedCount=deleted)


@router.post("", response_model=NotificationEventResponse)
def receive_notification_event(
    payload: NotificationEventRequest,
    request: Request,
    response: Response,
) -> NotificationEventResponse:
    repository = request.app.state.repository
    occurred_at = datetime.fromtimestamp(
        payload.occurred_at_epoch_millis / 1000,
        tz=timezone.utc,
    )

    is_new = repository.claim(payload.trip_id, payload.event_id, occurred_at)
    if not is_new:
        return NotificationEventResponse(
            tripId=payload.trip_id,
            eventId=payload.event_id,
            duplicate=True,
            status=repository.get_status(payload.trip_id, payload.event_id),
        )

    cooldown_seconds = breach_email_cooldown_seconds()
    if repository.has_recent_sent_for_trip(payload.trip_id, cooldown_seconds):
        # New eventId is claimed, but another sent email exists inside the cooldown window.
        # Do not call the mail provider; mark for audit and treat as duplicate for the client.
        repository.mark_failed(payload.trip_id, payload.event_id, COOLDOWN_SUPPRESSED_MESSAGE)
        return NotificationEventResponse(
            tripId=payload.trip_id,
            eventId=payload.event_id,
            duplicate=True,
            status="failed",
        )

    try:
        resend_email_id = request.app.state.notification_gateway.send_email(
            payload.parent_email,
            "The active trip has left the selected permitted area.",
        )
        repository.mark_sent(payload.trip_id, payload.event_id, resend_email_id)
    except Exception as exc:
        repository.mark_failed(payload.trip_id, payload.event_id, str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Email delivery failed: {exc}",
        ) from exc

    response.status_code = status.HTTP_201_CREATED
    return NotificationEventResponse(
        tripId=payload.trip_id,
        eventId=payload.event_id,
        duplicate=False,
        status="sent",
    )
