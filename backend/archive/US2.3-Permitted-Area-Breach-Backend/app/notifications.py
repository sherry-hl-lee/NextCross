import logging
import os
import smtplib
from email.message import EmailMessage
from typing import Optional, Protocol

import httpx

logger = logging.getLogger("uvicorn.error")

RESEND_TEST_FROM = "onboarding@resend.dev"


class NotificationGateway(Protocol):
    def send_email(self, email: str, message: str) -> Optional[str]:
        """Send one parent email. Returns a provider id when available."""


class ConsoleEmailGateway:
    """Local fallback when no email provider is configured. Does not send SMS."""

    def send_email(self, email: str, message: str) -> Optional[str]:
        logger.info(
            "[CONSOLE EMAIL] not delivered; logged only. to=%s message=%s",
            email,
            message,
        )
        return None


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
        logger.info("[GMAIL SMTP] from=%s to=%s", self.address, email)
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
            logger.error("Resend rejected the email: %s %s", response.status_code, response.text)
            raise RuntimeError(f"Resend {response.status_code}: {response.text}")
        email_id = response.json().get("id")
        logger.info("[RESEND EMAIL] to=%s id=%s", email, email_id)
        return email_id if isinstance(email_id, str) else None


def build_notification_gateway() -> NotificationGateway:
    gmail_address = (os.getenv("GMAIL_ADDRESS") or "").strip()
    gmail_app_password = (os.getenv("GMAIL_APP_PASSWORD") or "").strip()
    if gmail_address and gmail_app_password:
        logger.info("Using Gmail SMTP. from=%s", gmail_address)
        return GmailSmtpGateway(gmail_address, gmail_app_password)

    api_key = (os.getenv("RESEND_API_KEY") or "").strip()
    if not api_key:
        logger.warning("No Gmail SMTP or Resend key set; emails will be logged only.")
        return ConsoleEmailGateway()
    from_email = (os.getenv("RESEND_FROM_EMAIL") or RESEND_TEST_FROM).strip()
    if "example.com" in from_email.lower():
        logger.warning(
            "RESEND_FROM_EMAIL uses example.com. Using %s instead.",
            RESEND_TEST_FROM,
        )
        from_email = RESEND_TEST_FROM
    logger.info("Using Resend. from=%s", from_email)
    return ResendEmailGateway(api_key, from_email)
