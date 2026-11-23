from contextlib import asynccontextmanager

from fastapi import FastAPI

from .notifications import ConsoleNotificationGateway
from .repository import build_repository
from .routes import router
from .historical_routes import router as historical_crash_router
from .trip_routes import router as trip_prompt_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.repository = build_repository()
    app.state.repository.create_tables()
    app.state.notification_gateway = ConsoleNotificationGateway()
    yield


app = FastAPI(
    title="NextCross API",
    version="2.0.0",
    lifespan=lifespan,
)
app.include_router(router, prefix="/api/v1")
app.include_router(historical_crash_router, prefix="/api/v1")
app.include_router(trip_prompt_router, prefix="/api/v1")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
