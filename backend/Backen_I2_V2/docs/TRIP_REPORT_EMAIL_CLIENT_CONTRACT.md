# Trip report email: agreed backend implementation for the client handoff

Based on `client-trip-report-email-handoff-backend.md`, 2026-10-05.
This supersedes the proposed contract for Path B. Path A (Android share sheet) is unchanged.

## Flow and responsibilities

1. Android loads ONE completed report from Room; its existing retention stays unchanged.
2. Driver previews/selects report content, detailed route and reflection conversation.
3. Android builds plainText and optional PDF/map containing ONLY those selected contents.
4. Only an explicit **Send to parent** action calls the backend. End Trip never sends automatically.
5. Backend validates the envelope/attachment types/limits, claims clientRequestId, then submits mail.
6. Android displays submission acknowledgement or error; it does not delete the local report.

There is no new public report link, phone number, continuous GPS upload, server report archive,
login, automatic send, or delivery/read webhook. This endpoint does not call AI.
The client owns PDF/map rendering and conversation export, not the backend.

## Answers to the seven handoff questions

1. Path B implemented as an additional capability; Path A remains.
2. POST `/api/v1/trip-reports/email` on the existing combined backend. Intended host:
   `https://backend-8sxu.onrender.com` AFTER deploying this package (not verified live).
   A separate nextcross-api service is not required. This is a deliberate merge into the I2 service.
3. Multipart/form-data, not JSON+base64. Do not reuse notification-events.
4. PDF <=5 MiB; map <=2 MiB; entire multipart body <=8 MiB; meta <=128 KiB.
5. clientRequestId UUID idempotency supported, including attachment content hashes.
6. No user authentication in this prototype. Any format-valid parentEmail is accepted
   (no recipient allowlist; same idea as breach notifications). A shared global
   20-send/rolling-24-hour dispatch budget still applies.
7. PlainText-only supported when includeDetailedRoute=false. PDF optional.

## Request

Content-Type: multipart/form-data with boundary (let the HTTP client generate it).
Parts: `meta` REQUIRED as JSON text field or application/json file; `reportPdf` OPTIONAL
application/pdf; `routeMap` image/png or image/jpeg REQUIRED when includeDetailedRoute=true.
One of each only; no other parts. Do not include routeMap when includeDetailedRoute=false.
When false, Android must also omit detailed route from text/PDF. When
includeReflectionConversation=false, it must omit conversation from text/PDF.

meta schema/example: `trip_report_email_meta.json`. All names are camelCase.
Required: tripId, parentEmail, subject, plainText, includeDetailedRoute,
includeReflectionConversation, startedAtEpochMillis, endedAtEpochMillis,
hardAccelerationCount, hardBrakingCount, clientRequestId.
Optional: plannedTripId, startingPointLabel, destinationLabel (null accepted).
Timestamps: non-negative integer UTC Unix milliseconds, end>=start, duration<=24h.
Counts: integer 0..100000. Booleans must be actual JSON true/false.
Subject: nonblank <=200 characters, no control characters/newlines.
PlainText: nonblank <=50000 characters; line breaks preserved. No HTML mail body.
IDs/labels: nonblank <=200 characters. Extra JSON fields/duplicate keys rejected.

Backend uses the caller's parentEmail, subject and plainText; never substitutes another
recipient or generates a different report body. Sanitised fixed attachment filenames:
trip-report.pdf, trip-route.png or trip-route.jpg. Original filenames are not retained.
Attachment MIME and leading file signatures are checked, not full PDF/image structure,
malware, route authenticity or the content selection inside an exported PDF.
The backend cannot verify arbitrary text/PDF is exclusively one trip or removes excluded
conversation/route: there is no saved server-side report to compare. Android must enforce
selection and trip isolation and include AI limitations/disclaimer where AI content is shared.

## Response and errors

**200 ONLY on provider acceptance** (original or identical accepted retry):

```json
{"status":"accepted","tripId":"recording-trip-uuid","clientRequestId":"00000000-0000-4000-8000-000000000001","duplicate":false}
```

Accepted means SMTP/provider acknowledged submission, NOT delivered/read. Client toast:
“Report submitted for email delivery.” Do not claim the parent opened it.

Errors have `status`, stable `code`; when meta was valid, also tripId, clientRequestId,
duplicate. No recipient/body/provider credentials echoed.

| HTTP | code | Meaning |
|---|---|---|
| 400 | INVALID_EMAIL | Invalid parentEmail |
| 400 | INVALID_META | Invalid JSON/fields/timestamps/subject |
| 400 | INVALID_REQUEST / INVALID_MULTIPART | Duplicate/unknown parts or invalid multipart |
| 400 | MISSING_ATTACHMENT | Detailed route selected without map |
| 400 | INVALID_ATTACHMENT | Empty or disallowed MIME/signature |
| 400 | ROUTE_NOT_SELECTED | Map provided although route excluded |
| 409 | IDEMPOTENCY_CONFLICT | Same UUID reused with different meta/attachment bytes |
| 409 | SEND_PENDING | Previous attempt still pending; status=pending |
| 413 | PAYLOAD_TOO_LARGE | Request, meta or attachment limit exceeded |
| 415 | UNSUPPORTED_MEDIA_TYPE | Request is not multipart/form-data |
| 429 | RATE_LIMITED | Global budget or provider limit |
| 502 | SMTP_UNAVAILABLE | Known provider rejection/auth/send failure |
| 503 | SMTP_UNAVAILABLE | Disabled/not configured |
| 503 | DISPATCH_STORE_UNAVAILABLE | Persistent dedup storage unavailable |
| 503 | DELIVERY_STATE_UNKNOWN | Submission outcome uncertain; status=unknown |

Client fallback: offer Path A, but for UNKNOWN/PENDING warn that an email may already
be submitted before another send/share. Never auto-resend with a fresh UUID.
Retry after client timeout: identical meta and file bytes with SAME clientRequestId.
Different multipart boundaries/original filenames are fine. All duplicate states skip sending.
A known FAILED can be retried deliberately with a new UUID after fixing the issue;
UNKNOWN/PENDING requires checking provider records first. No automatic background retries.
SMTP cannot guarantee exactly-once delivery across crash/network failure windows.

## Deployment

Keep Render Root Directory `Backen_I2_V2`; build `pip install -r requirements.txt`;
start `uvicorn app.main:app --host 0.0.0.0 --port $PORT --no-access-log`.
New dependency python-multipart==0.0.20 included.
The old JSON /reports/preview and /reports/email endpoints remain for compatibility;
new Android adapter should use ONLY /trip-reports/email.

Use existing `docs/report_email_dispatch.sql` migration with database admin; backend role
needs SELECT/INSERT/UPDATE on iteration2_v2.report_email_dispatch. If already migrated,
no new schema required. Set REPORT_EMAIL_DATABASE_URL to the backend Neon URL privately.
Only hashes, creation time and dispatch state are saved, not body/attachments/recipient.
Parser temporary files are closed after processing; no report retained on backend.
Logging records request hash, state and duplicate flag only, not report/route/email text.

SMTP config remains:
REPORT_EMAIL_ENABLED=true
REPORT_EMAIL_PROVIDER=smtp
REPORT_FROM_EMAIL=provider-authorised bare sender address
SMTP_HOST / SMTP_PORT / SMTP_SECURITY=starttls or ssl / SMTP_USERNAME / SMTP_PASSWORD
REPORT_EMAIL_DATABASE_URL=Neon backend connection (secret)
(REPORT_ALLOWED_RECIPIENTS is unused / ignored — removed from runtime checks.)

**Free Render blocks outbound SMTP ports 25,465,587**. For SMTP use hosting/compute that
permits the provider port. Alternatively the included Resend HTTPS adapter supports the
same text+attachments request: REPORT_EMAIL_PROVIDER=resend plus RESEND_API_KEY and a
verified/permitted REPORT_FROM_EMAIL. This alternative is server email delivery but NOT
SMTP; team should explicitly agree which provider is used. No provider credentials bundled.
Official: https://render.com/docs/free
Attachments reference: https://resend.com/docs/api-reference/emails/send-email

## Testing and handoff

`python -m pytest tests -q`: offline tests, no external mail sent.
`tools/Test-TripReportEmail.ps1`: explicit synthetic real-send script; asks for SEND first.
Use only a recipient you control/are authorised to test; supplying real credentials and
Neon migration still required. Android adapter is not included in this backend package.
Client/UI check button-only send, selected content, attachment export, trip A/B separation,
loading/error/fallback, duplicate tap/timeout reuse and keep Room report after acceptance.
Live Neon, mail receipt and deployed Android integration are NOT verified by unit tests.
