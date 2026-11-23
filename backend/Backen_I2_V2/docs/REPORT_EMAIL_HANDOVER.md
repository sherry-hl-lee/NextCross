> For the newer client-generated text/PDF/map contract, use TRIP_REPORT_EMAIL_CLIENT_CONTRACT.md and /api/v1/trip-reports/email. This document describes the retained legacy JSON endpoints only.

# Plain-text report generation and direct email handover

## What changed

Built on NextCross_Backend_Two_Turn_AI. New `/api/v1/reports/preview` generates text
without sending. New `/api/v1/reports/email` submits the generated text directly
from the backend. No PDF, report link, phone number, automatic trip-end sending,
login, report retrieval or full report/conversation storage was added.
The old breach notification mock is unchanged; configure the new report email
provider separately.

Android sends report contents because the backend has no complete saved trip report.
Do not submit just tripId. Plain report text is generated using templates, not a new
AI call. Caller-supplied report facts and AI content are structurally checked, not
independently authenticated or verified against a stored trip.

## Contract and Android responsibilities

`POST /api/v1/reports/preview`: body in `report_preview_request.json`.
Response: tripId, subject, reportText. Preview has no SMTP side effects.

`POST /api/v1/reports/email`: body in `report_email_request.json`.
Required requestId: UUID for this sending action; recipientEmail: one address;
confirmedByDriver: true (literal boolean); report: same body as preview.

Report fields: tripId, startedAt and endedAt (timezone-offset ISO timestamps), optional
origin, destination, distanceKm, events (default empty, up to 200), optional aiQuestion,
driverContext, aiAdvice. The latter use the existing two-turn response contracts.
Only completed AI content for events included in the report is accepted. AI turns
must identify the same event and trip. Event offsets must fit inside the report trip.
Advice requires its question and driver response. All models reject extra fields,
including raw GPS coordinates and recipient phone numbers.

Android must include all events it intends to share; the report counts the included
list rather than inventing a whole-trip count. Empty list means zero INCLUDED events,
not proof that nothing occurred. Incomplete report recording must not be presented as
complete coverage. origin/destination are optional descriptive text and can reveal
locations: include only what the driver previews and agrees to share.

Android should:
1. Build data for the selected completed trip, call preview and show the text.
2. Collect/confirm the recipient and show an explicit Send action.
3. Generate one requestId per intended send. Disable repeated submit while waiting.
4. POST the report with confirmedByDriver true; check status, not HTTP 200 alone.
5. Reuse the same requestId and identical body after an HTTP/client timeout to obtain
   the existing state; never silently create a new ID and resend.

HTTP 200 statuses: ACCEPTED (mail submission acknowledged; not proof of receipt/read),
PENDING (already claimed, not settled), FAILED (known submission failure), UNKNOWN
(submission may have happened), UNAVAILABLE (disabled/configuration/storage problem).
Responses include requestId, tripId, emailStatus, duplicate and reason; no recipient
or message body is echoed. Validation HTTP 422 returns field locations/types only;
403 recipient not enabled; 409 same requestId with different content; 429 prototype
24-hour budget exhausted.

Duplicates never send again, including failed/unknown requests. After a known FAILED,
a user may deliberately retry with a new ID once corrected. For UNKNOWN or stale
PENDING, check provider records first to avoid duplicates. SMTP cannot guarantee
exactly-once delivery across crashes/network failures. SMTP Message-ID is correlation,
not a provider deduplication guarantee. No automatic retry or delivery webhook exists.

## SMTP deployment

Set Render/local environment:

```
REPORT_EMAIL_ENABLED=true
REPORT_EMAIL_PROVIDER=smtp
REPORT_FROM_EMAIL=your-verified-sender@example.com
SMTP_HOST=provider-supplied-host
SMTP_PORT=587
SMTP_SECURITY=starttls
SMTP_USERNAME=provider-supplied-username
SMTP_PASSWORD=provider-app-password-or-SMTP-credential
REPORT_EMAIL_DATABASE_URL=your-existing-Neon-backend-connection
```

Alternative TLS mode: SMTP_SECURITY=ssl with provider port (commonly 465).
Certificate validation is enabled; unencrypted SMTP is not supported. Sender must
be permitted by your provider. Credentials stay in backend environment variables;
never put them into Android or Git. This package does NOT automatically load .env.

IMPORTANT: Render free web services block outbound ports 25,465,587. For ordinary
SMTP on these ports, use a hosting instance that permits them. Merely updating
credentials will not remove a platform network block.
Reference: https://render.com/docs/free

## Optional HTTPS delivery adapter

Included because the existing deployment may remain on free Render. It preserves
the same Android contract and direct server delivery, but uses Resend HTTPS instead
of SMTP. It is optional, not enabled automatically and does not substitute a fake
success. Set REPORT_EMAIL_PROVIDER=resend, RESEND_API_KEY (secret), REPORT_FROM_EMAIL
(verified/permitted sender) and database configuration. Any format-valid recipient
address is accepted (no app-level allowlist). Create/configure the provider account
yourself; verify the sending domain before claiming real parent delivery. No
pricing/free-quota guarantee is made here. Provider acceptance is still not receipt.
References: https://resend.com/docs/api-reference/emails/send-email
https://resend.com/docs/dashboard/emails/idempotency-keys

## Database handoff

Run `report_email_dispatch.sql` ONCE with the data owner's/admin connection. The
existing backend role needs SELECT/INSERT/UPDATE on the NEW table; old notification
permissions do not grant access to it. No runtime CREATE on Neon is attempted.
REPORT_EMAIL_DATABASE_URL falls back to RUNTIME_DATABASE_URL then DATABASE_URL.
It deliberately does not reuse a possibly read-only HISTORICAL_DATABASE_URL.

The table stores hashes, state/reason and creation time only, not recipient addresses,
full report text, driver responses or raw trip IDs. Hashes/timestamps are retained
until an administrator removes them; they are not an anonymity guarantee.
Atomic claims and a fixed global budget of 20 new sends per rolling 24 hours apply
across workers using the shared Postgres table. Failed/uncertain sends count too.
Recipient addresses are not allowlisted at the app layer: the backend sends to the
format-valid address supplied by the client (aligned with breach notifications).

Local tests default to a separate SQLite dispatch database. Render requires Postgres
for this feature; ephemeral SQLite would lose duplicate history after restarts.
Admin pruning removes replay protection for pruned request IDs; use a suitable
retention period and do not replay old sends.

## Test and merge

This package includes `backend/` for the full combined code and `changed-files/`
containing ONLY report-email changes relative to the two-turn package you just
uploaded. Upload those contents INSIDE Backen_I2_V2; do not create another nested
changed-files folder. main.py retains both AI routes and adds report router plus
sanitised report validation. Merge if GitHub has newer main.py edits.

Run `python -m pytest tests -q` from the backend root. Offline adapters never send
mail. The delivery did not contact SMTP, Resend, Neon or Render. Real provider
credentials, Neon migration and Android integration still require verification.
A synthetic example and explicit real-send PowerShell script are included. Real
mail sends must go only to a recipient you control/are authorised to test.
The synthetic script makes ONE send, not a preview-only test.

Existing speed/historical endpoints and one-shot/two-turn AI contracts are retained.
Nothing was pushed to GitHub or deployed by this delivery.

## Delivery verification record

Full regression before the final three email edge-case tests: 136 passed, 5 skipped.
Final report-email suite: 27 passed (including the three additional SMTP acceptance/
no-retry cases). Across the suites, 139 distinct tests passed; 5 live DB tests remain
skipped. Python 3.12 with the existing environment packages, not a fresh requirements
installation. Actual SMTP/HTTPS provider, Neon migration, and Android have not been
validated.
