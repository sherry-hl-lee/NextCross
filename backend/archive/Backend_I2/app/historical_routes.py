import logging
from datetime import date
from typing import Annotated

from fastapi import APIRouter, HTTPException, Query, status
from pydantic import ValidationError

from .historical_schemas import HistoricalCrashQuery, HistoricalCrashResponse, TimePeriod
from .historical_service import (
    CrashLookupUnavailable,
    call_lookup,
    normalize_response,
)


logger = logging.getLogger(__name__)
router = APIRouter(prefix="/historical-crash-context", tags=["historical crash context"])


@router.get("", response_model=HistoricalCrashResponse, response_model_by_alias=True)
def get_historical_crash_context(
    latitude: Annotated[float, Query(ge=-90, le=90, allow_inf_nan=False)],
    longitude: Annotated[float, Query(ge=-180, le=180, allow_inf_nan=False)],
    radius_meters: Annotated[
        float, Query(alias="radiusMeters", gt=0, le=50_000, allow_inf_nan=False)
    ],
    time_period: Annotated[TimePeriod, Query(alias="timePeriod")],
    start_date: Annotated[date | None, Query(alias="startDate")] = None,
    end_date: Annotated[date | None, Query(alias="endDate")] = None,
) -> HistoricalCrashResponse:
    try:
        query = HistoricalCrashQuery(
            latitude=latitude,
            longitude=longitude,
            radiusMeters=radius_meters,
            timePeriod=time_period,
            startDate=start_date,
            endDate=end_date,
        )
    except ValidationError as exc:
        detail = [
            {"location": list(error["loc"]), "message": error["msg"], "type": error["type"]}
            for error in exc.errors()
        ]
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=detail) from exc
    try:
        return normalize_response(call_lookup(query), query)
    except CrashLookupUnavailable as exc:
        logger.error("Historical crash lookup unavailable")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Historical crash data service is unavailable",
        ) from exc
    except Exception as exc:
        # Database errors can contain hostnames or connection details. Log only type.
        logger.error("Historical crash lookup failed (%s)", type(exc).__name__)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Historical crash data service is unavailable",
        ) from exc
