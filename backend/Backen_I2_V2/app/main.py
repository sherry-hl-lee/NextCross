from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.responses import JSONResponse

from .notifications import build_notification_gateway
from .repository import build_repository
from .routes import router
from .historical_routes import router as historical_crash_router
from .trip_routes import router as trip_prompt_router
from .speed_routes import router as speed_router
from .ai_reflection import router as ai_router
from .report_email import router as report_email_router
from .trip_report_email import router as trip_report_email_router, ReportUploadLimit


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.repository = build_repository()
    app.state.repository.create_tables()
    # US2.3 breach delivery: Resend / Gmail / console (same factory as standalone service).
    app.state.notification_gateway = build_notification_gateway()
    yield


app = FastAPI(
    title="NextCross API",
    version="2.0.0",
    lifespan=lifespan,
)
app.include_router(router, prefix="/api/v1")
app.include_router(historical_crash_router, prefix="/api/v1")
app.include_router(trip_prompt_router, prefix="/api/v1")
app.include_router(speed_router, prefix="/api/v1")
app.include_router(ai_router, prefix="/api/v1")
app.include_router(report_email_router, prefix="/api/v1")
app.include_router(trip_report_email_router, prefix="/api/v1")
app.add_middleware(ReportUploadLimit)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    if request.url.path in ("/api/v1/ai/reflection", "/api/v1/ai/reflection/question", "/api/v1/ai/reflection/advice"):
        field = {"/api/v1/ai/reflection/question":"question", "/api/v1/ai/reflection/advice":"advice"}.get(request.url.path,"reflection")
        return JSONResponse(status_code=422,content={"aiStatus":"INVALID_EVENT",field:None,
            "detail":[{"loc":list(e["loc"]),"type":e["type"]} for e in exc.errors()]})
    if request.url.path in ("/api/v1/reports/preview", "/api/v1/reports/email"):
        return JSONResponse(status_code=422,content={"detail":[{"loc":list(e["loc"]),"type":e["type"]} for e in exc.errors()]})
    # FastAPI's default validation response includes rejected input values.
    # Remove them for GPS-bearing requests; preserve unrelated legacy contracts.
    if request.url.path.endswith("/speed-analysis") or request.url.path == "/api/v1/speed-limit":
        return JSONResponse(status_code=422, content={"detail": [
            {"loc": list(e["loc"]), "type": e["type"], "msg": "Invalid field or trip constraint"}
            for e in exc.errors()]})
    return await request_validation_exception_handler(request, exc)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
