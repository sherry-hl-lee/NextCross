from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, Request, Response, status

from .schemas import NotificationEventRequest, NotificationEventResponse

router = APIRouter(prefix="/notification-events", tags=["notification events"])


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

    try:
        request.app.state.notification_gateway.send_email_and_sms(
            payload.parent_email,
            payload.parent_mobile_number,
            "The active trip has left the selected permitted area.",
        )
        repository.mark_sent(payload.trip_id, payload.event_id)
    except Exception as exc:
        repository.mark_failed(payload.trip_id, payload.event_id, str(exc))
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Prototype notification delivery failed",
        ) from exc

    response.status_code = status.HTTP_201_CREATED
    return NotificationEventResponse(
        tripId=payload.trip_id,
        eventId=payload.event_id,
        duplicate=False,
        status="sent",
    )
