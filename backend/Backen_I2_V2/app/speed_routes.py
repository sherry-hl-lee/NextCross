import logging
import os
from datetime import datetime
from fastapi import APIRouter, HTTPException, Query
from .speed_models import AnalysisRequest, AnalysisResponse, DetectionConfig, Sample, SpeedContext
from .speed_repository import load_speed_data
from .speed_rules import choose_point_context
from .speed_analysis import analyze_trip

router = APIRouter(tags=["speed analysis"])
logger = logging.getLogger(__name__)


def configuration():
    # A JSON environment value allows reproducible server-owned engineering parameters.
    return DetectionConfig.model_validate_json(os.getenv("SPEED_DETECTION_CONFIG", "{}"))


@router.post("/trips/{trip_id}/speed-analysis", response_model=AnalysisResponse)
def speed_analysis(trip_id: str, payload: AnalysisRequest):
    if trip_id != payload.trip_id:
        raise HTTPException(422, "Path and body tripId must match")
    try:
        config = configuration()
        data = load_speed_data(payload.samples,config) if payload.samples else ({},{},[],{})
        return analyze_trip(payload,config,data)
    except Exception as exc:
        logger.error("Speed analysis failed (%s)",type(exc).__name__)
        raise HTTPException(503,"Speed analysis service is unavailable") from exc


@router.get("/speed-limit", response_model=SpeedContext)
def speed_limit(latitude: float = Query(ge=-90,le=90,allow_inf_nan=False),
                longitude: float = Query(ge=-180,le=180,allow_inf_nan=False),
                observedAt: datetime = Query(),
                heading: float | None = Query(default=None,ge=0,lt=360,allow_inf_nan=False),
                radiusMeters: float = Query(default=30,gt=0,le=1000,allow_inf_nan=False)):
    if observedAt.utcoffset() is None or not 1970 <= observedAt.year < 2100:
        raise HTTPException(422,"observedAt requires a UTC offset and year 1970..2099")
    try:
        config = configuration().model_copy(update={"lookup_radius_meters":radiusMeters})
        sample = Sample(sample_id="lookup",observed_at_epoch_millis=int(observedAt.timestamp()*1000),
                        latitude=latitude,longitude=longitude,bearing_degrees=heading)
        rows,rules,periods,coverage = load_speed_data([sample],config)
        return choose_point_context(rows.get("lookup",[]),rules,observedAt,periods,coverage)
    except Exception as exc:
        logger.error("Speed lookup failed (%s)",type(exc).__name__)
        raise HTTPException(503,"Speed lookup service is unavailable") from exc
