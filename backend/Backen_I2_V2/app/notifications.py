import logging
import os
import smtplib
from email.message import EmailMessage
from typing import Optional, Protocol

import httpx

logger = logging.getLogger("uvicorn.error")

# Same product From as report email (REPORT_FROM_EMAIL). Must be on the verified domain.
DEFAULT_REPORT_FROM = "NextCross <noreply@nextcross.tech>"


class NotificationGateway(Protocol):
    def send_email(self, email: str, message: str) -> Optional[str]:
        """Send one parent email. Returns a provider id when available."""


class ConsoleEmailGateway:
    """Local fallback when no email provider is configured. Does not send SMS."""

    def send_email(self, email: str, message: str) -> Optional[str]:
        # Do not place parent contact details in application logs.
        logger.info("[CONSOLE EMAIL] not delivered; logged only")
        return None


# Kept for older I2 imports / tests that still name the console gateway this way.
ConsoleNotificationGateway = ConsoleEmailGateway


def _require_real_inbox(email: str) -> None:
    if email.lower().endswith("@example.com"):
        raise RuntimeError(
            "parentEmail cannot be @example.com. Use a real inbox."
        )


class GmailSmtpGateway:
    """Sends via smtp.gmail.com. Recipients can be any real address."""

    def __init__(self, address: str, app_password: str):
        self.address = address
        self.app_password = app_password.replace(" ", "")

    def send_email(self, email: str, message: str) -> Optional[str]:
        _require_real_inbox(email)
        payload = EmailMessage()
        payload["From"] = self.address
        payload["To"] = email
        payload["Subject"] = "NextCross: trip left the permitted area"
        payload.set_content(message)
        with smtplib.SMTP("smtp.gmail.com", 587, timeout=20) as smtp:
            smtp.starttls()
            smtp.login(self.address, self.app_password)
            smtp.send_message(payload)
        logger.info("[GMAIL SMTP] delivery requested")
        return None


class ResendEmailGateway:
    def __init__(self, api_key: str, from_email: str):
        self.api_key = api_key
        self.from_email = from_email

    def send_email(self, email: str, message: str) -> Optional[str]:
        _require_real_inbox(email)
        response = httpx.post(
            "https://api.resend.com/emails",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            json={
                "from": self.from_email,
                "to": [email],
                "subject": "NextCross: trip left the permitted area",
                "text": message,
            },
            timeout=15.0,
        )
        if response.is_error:
            logger.error(
                "Resend rejected the email: %s %s",
                response.status_code,
                response.text,
            )
            raise RuntimeError(f"Resend {response.status_code}: {response.text}")
        email_id = response.json().get("id")
        logger.info("[RESEND EMAIL] delivery accepted id=%s", email_id)
        return email_id if isinstance(email_id, str) else None


def build_notification_gateway() -> NotificationGateway:
    gmail_address = (os.getenv("GMAIL_ADDRESS") or "").strip()
    gmail_app_password = (os.getenv("GMAIL_APP_PASSWORD") or "").strip()
    if gmail_address and gmail_app_password:
        logger.info("Using Gmail SMTP for breach notifications")
        return GmailSmtpGateway(gmail_address, gmail_app_password)

    api_key = (os.getenv("RESEND_API_KEY") or "").strip()
    if not api_key:
        logger.warning("No Gmail SMTP or Resend key set; breach emails will be logged only.")
        return ConsoleEmailGateway()
    # Same env as Path B report email — one From on Render is enough.
    from_email = (os.getenv("REPORT_FROM_EMAIL") or "").strip()
    if not from_email:
        from_email = DEFAULT_REPORT_FROM
        logger.warning(
            "REPORT_FROM_EMAIL unset for breach mail; using default %s",
            DEFAULT_REPORT_FROM,
        )
    lowered = from_email.lower()
    if "example.com" in lowered or "onboarding@resend.dev" in lowered:
        logger.warning(
            "REPORT_FROM_EMAIL is a placeholder (%s). Using %s instead.",
            from_email,
            DEFAULT_REPORT_FROM,
        )
        from_email = DEFAULT_REPORT_FROM
    logger.info("Using Resend for breach notifications. from=%s", from_email)
    return ResendEmailGateway(api_key, from_email)
