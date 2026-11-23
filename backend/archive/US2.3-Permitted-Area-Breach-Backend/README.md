# US2.3 Permitted-Area Breach Notification API

This FastAPI service matches the event contract implemented in the team's Android app and the
`iteration2_v2.notification_event` table defined by the data team.

**Production URL:** `https://nextcross-api.onrender.com`

## Service responsibility

The Android app already owns:

- GPS recording;
- circular permitted-area checks;
- `inside -> outside` breach detection;
- suppression of repeated events while the trip remains outside;
- creation of a new event after the trip returns inside and leaves again;
- stopping the monitor when End Trip is selected.

This API therefore owns only:

- receiving an Android `AreaBreachEvent`;
- rejecting malformed or unexpected payloads;
- using `tripId + eventId` to prevent duplicate notification delivery;
- suppressing another email for the same `tripId` within a short cooldown window
  (default **10 minutes**, env `BREACH_EMAIL_COOLDOWN_SECONDS`) using recent `sent_at`
  rows in `notification_event` — this stops GPS boundary jitter from flooding parents;
- recording `pending`, `sent`, or `failed` in `notification_event`;
- sending **email only** via Resend (`noreply@nextcross.tech`), or Gmail SMTP when configured
  locally, or a console log when no provider credentials are set.

It deliberately does not receive continuous GPS coordinates or calculate the boundary again.
SMS is not sent. The request body is email-only and matches the current Android contract
(no `parentMobileNumber` field).

## Android-compatible endpoint

```text
POST /api/v1/notification-events
```

Request:

```json
{
  "tripId": "planned-trip-001",
  "eventId": "breach-001",
  "eventType": "PERMITTED_AREA_BREACH",
  "occurredAtEpochMillis": 1788231600000,
  "parentEmail": "parent@gmail.com"
}
```

`parentEmail` must be a real inbox. Addresses ending in `@example.com` are rejected at send time.
Extra fields such as `parentMobileNumber` are rejected with HTTP `422`.

First submission returns HTTP `201`:

```json
{
  "tripId": "planned-trip-001",
  "eventId": "breach-001",
  "duplicate": false,
  "status": "sent"
}
```

Submitting the same `tripId + eventId` again returns HTTP `200` and
`"duplicate": true`. The service does not send another email.

If a **different** `eventId` arrives for the same `tripId` while another breach email was
`sent` within `BREACH_EMAIL_COOLDOWN_SECONDS` (default `600`), the service also returns
HTTP `200` with `"duplicate": true` and `"status": "failed"`, stores
`error_message = suppressed_by_cooldown`, and does **not** call Resend.

## Production deployment (Render)

The service is deployed on Render as `nextcross-api`.

| Setting | Value |
|---|---|
| Root Directory | `US2.3-Permitted-Area-Breach-Backend` |
| Runtime | Docker |
| Health Check Path | `/health` |
| `RESEND_API_KEY` | Set in Render dashboard (never commit) |
| `RESEND_FROM_EMAIL` | `NextCross <noreply@nextcross.tech>` |
| `RUNTIME_DATABASE_URL` | Neon connection string (shared DB; required for production dedup/cooldown) |
| `BREACH_EMAIL_COOLDOWN_SECONDS` | Optional; default `600` (10 minutes). Set `0` to disable trip-level cooldown. |

Verify:

```text
https://nextcross-api.onrender.com/health
https://nextcross-api.onrender.com/docs
```

## Local development on Windows

Open PowerShell in the project folder:

```powershell
py -3.10 -m venv .venv
& ".\.venv\Scripts\python.exe" -m pip install -r requirements.txt
```

### Resend (matches production)

```powershell
Remove-Item Env:GMAIL_ADDRESS, Env:GMAIL_APP_PASSWORD -ErrorAction SilentlyContinue
$env:RESEND_API_KEY = "re_..."
$env:RESEND_FROM_EMAIL = "NextCross <noreply@nextcross.tech>"
& ".\.venv\Scripts\python.exe" -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Open `http://127.0.0.1:8000/docs`.

On success the terminal logs:

```text
[RESEND EMAIL] to=parent@gmail.com id=...
```

### No provider configured

When neither Gmail nor Resend credentials are set, emails are logged only:

```text
[CONSOLE EMAIL] not delivered; logged only. to=parent@gmail.com message=...
```

The local service automatically creates `notification_events.db`.

### Gmail SMTP (optional local alternative)

Gmail takes priority over Resend when both are configured:

```powershell
$env:GMAIL_ADDRESS = "yourname@gmail.com"
$env:GMAIL_APP_PASSWORD = "sixteen-char-app-password"
& ".\.venv\Scripts\python.exe" -m uvicorn app.main:app --reload
```

Do not commit the app password.

## Automated tests

```powershell
& ".\.venv\Scripts\python.exe" -m pytest -q
```

The tests verify:

- exact compatibility with Android's camelCase event fields;
- one email for a new event (no SMS);
- no second delivery for a duplicate event ID;
- trip-level cooldown suppressing a second email for a new event ID on the same trip;
- a second delivery for a new breach event ID when cooldown is disabled;
- rejection of continuous-location fields.

## Optional team Neon integration

For the shared database, set `RUNTIME_DATABASE_URL` before starting the API:

```powershell
$env:RUNTIME_DATABASE_URL = "postgresql://..."
& ".\.venv\Scripts\python.exe" -m uvicorn app.main:app --reload
```

Use the existing `iteration1_v2_backend` role and the same Neon database URL. The role has
schema `USAGE` and `SELECT`, `INSERT`, `UPDATE` on `iteration2_v2.notification_event`;
its name does not restrict it to the old schema. No new role or password is required.
Never put this URL in Android source code or commit it to Git.

`RUNTIME_DATABASE_URL` takes precedence over `DATABASE_URL`. When neither is set, the API
uses local SQLite. PostgreSQL tables are created by the data pipeline's `migrate` command,
not by API startup. See the [pipeline handover](../../../sdg-platform/data-pipeline/iteration3_v1/backend_handover.md#switching-the-notification-backend).

### Activate the iteration2_v2 notification table

The PostgreSQL repository in this checkout now reads and writes
`iteration2_v2.notification_event`. The request and response contracts are unchanged.
An already-running service needs this code to be redeployed or restarted with the updated
files; this repository change alone does not update Render.

Before deploying, pause the old notification writers and allow in-flight requests to finish.
Preserve any old rows, including their composite keys, statuses and timestamps, in the new
table and check they match before resuming requests on the new code. Starting with an empty
table when old history exists would treat previously handled events as new notifications.
Keep the old table through the transition. The pipeline does not copy notification history
automatically. This change does not send any notification.

The read-only Neon check at `2026-09-12T05:25:41Z` found both notification tables empty,
so no history needed copying at that time. Recheck after pausing the old writers at deployment.
The [cutover record](../../../sdg-platform/archive/iteration2_v2/reports/notification_backend_cutover.json)
records the checks; deployment of the running service remains pending.

The Android app POSTs breach events when `ClientBTripRuntimeLocator` creates the runtime and a
breach occurs. Start/End must still go through `ClientBTripRuntime` in `App.kt`; otherwise no
`AreaBreachEvent` is produced.

Do not put `RESEND_API_KEY` in Android source or commit it to Git.
