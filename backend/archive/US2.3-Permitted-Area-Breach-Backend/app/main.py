from contextlib import asynccontextmanager

from fastapi import FastAPI

from .notifications import build_notification_gateway
from .repository import build_repository
from .routes import router


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.repository = build_repository()
    app.state.repository.create_tables()
    app.state.notification_gateway = build_notification_gateway()
    yield


app = FastAPI(
    title="US2.3 Permitted-Area Breach Notification API",
    version="1.0.0",
    lifespan=lifespan,
)
app.include_router(router, prefix="/api/v1")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
