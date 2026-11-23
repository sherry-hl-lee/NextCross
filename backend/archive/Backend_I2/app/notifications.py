import logging
from typing import Protocol

logger = logging.getLogger("uvicorn.error")


class NotificationGateway(Protocol):
    def send_email_and_sms(self, email: str, mobile: str, message: str) -> None: ...


class ConsoleNotificationGateway:
    """Prototype gateway that demonstrates both deliveries without external accounts."""

    def send_email_and_sms(self, email: str, mobile: str, message: str) -> None:
        # Do not place parent contact details in application logs.
        logger.info("[PROTOTYPE EMAIL] delivery requested")
        logger.info("[PROTOTYPE SMS] delivery requested")
