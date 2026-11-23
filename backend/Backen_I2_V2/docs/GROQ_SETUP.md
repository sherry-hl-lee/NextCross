# Groq reflection setup and Android contract

This release adds POST `/api/v1/ai/reflection/question` and `/api/v1/ai/reflection/advice`.
The old one-shot endpoint remains deprecated. See `AI_TWO_TURN_HANDOVER.md` for the new contract.
It uses Groq's HTTPS chat-completions API through the existing httpx dependency;
installing the Groq SDK is unnecessary. Default model: `openai/gpt-oss-20b`.
Strict JSON schema output is requested, then locally validated.
Reference: https://console.groq.com/docs/structured-outputs

## Windows: configure and run

Open PowerShell in the extracted project root:

```powershell
py -3 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r requirements.txt
$groqSecret = Read-Host 'Paste Groq API key' -AsSecureString
$env:GROQ_API_KEY = [System.Net.NetworkCredential]::new('', $groqSecret).Password
Remove-Variable groqSecret
$env:GROQ_MODEL = 'openai/gpt-oss-20b'
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --no-access-log
```

The key remains in this terminal's process environment and is inherited by
Uvicorn. Restarted terminals need configuration again. `.env.example` is documentation,
not an automatically loaded file. Do not add the key to Android, Git or screenshots.
The AI endpoint needs no database query; keep existing DB configuration for other APIs.

In a second PowerShell terminal in the same project directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Test-GroqReflection.ps1
```

This makes one REAL external API request using synthetic facts and may consume
quota. It is not part of automatic tests. Look for `aiStatus: COMPLETED` with
`summary`, `question`, `suggestion`. Inspect wording manually for neutral language,
no invented cause, and correct event type. Repeat with the other two event types
through `/docs`; a successful response proves connectivity, not universal factuality.

For Render, add `GROQ_API_KEY` as a secret environment value and `GROQ_MODEL`
as a normal environment value on the backend service, then deploy the updated code.
Local variables do not update Render. No deployment is performed by this package.

## Request contract

Use the exact nested `selectedEvent.metrics` examples from the addendum.
Required envelope: `tripId`, `selectedEvent` with `eventId`, `type`,
`startOffsetSeconds`, `endOffsetSeconds`, `durationSeconds`, `dataQuality`,
`detectorVersion`, `metrics`. Optional `tripSummary` contains all three nonnegative
counts. Its selected-event count must be at least one. Unknown fields are rejected,
including coordinates anywhere in the schema. Duration must match offsets.

Supported types: RAPID_ACCELERATION, RAPID_DECELERATION, SPEED_LIMIT_EXCEEDANCE.
Acceleration/deceleration require speedBeforeKmh, speedAfterKmh,
peakAccelerationMs2; signs and speed direction must agree with type.
Speed events require referenceLimitKmh, peakSpeedKmh and averageExceedanceKmh;
peakExceedanceKmh is optional but must agree if supplied. Mixed metric sets are
rejected. The deprecated one-shot route still requires GOOD. The two new routes accept
GOOD and UNKNOWN; POOR is refused without calling Groq. Validation does not authenticate or prove client-supplied events.

Android's old HardAcceleration/HardBraking events must be mapped to these types.
Do not invent speedBefore/speedAfter if the current Android model lacks them;
extend the event detector/export or agree a revised contract first. Convert absolute
event time to an offset using the actual trip start, not a guessed value.

Backend speed events currently have FLAT metrics. Before calling reflection,
Android must copy the speed metrics into `selectedEvent.metrics`, keep the common
envelope fields and omit sourceSpeedZoneId, limitStatus, candidate status and other
fields not accepted by this endpoint. Only use usable, known-limit candidate events.
Do not post the entire speed-analysis response, timeline or raw samples.

## Responses and failures

HTTP 200: `tripId`, `eventId`, `aiStatus`, nullable `reflection`, nullable `reason`,
and a discussion-only `disclaimer`.

- COMPLETED: validated JSON reflection.
- UNAVAILABLE: not_configured, rate_limited, provider_authentication, timeout,
  network_error, provider_unavailable or provider_request_rejected.
- INVALID_EVENT: insufficient data quality; no provider call.
- FAILED: invalid, refused, truncated or unexpected provider response.

Malformed input returns HTTP 422 with aiStatus INVALID_EVENT and sanitized field
locations/types, without echoing values. Android must keep recorded events visible
when reflection is null; it must not treat HTTP 200 alone as AI success. No automatic
retries are performed; users may retry later. Changing model requires a model that
supports strict structured output. Provider error bodies and credentials are never
returned or logged by this module.

## Privacy, testing and limitations

Only explicit numeric metrics, event type, relative time, quality and optional
counts go to Groq. Trip/event IDs and detectorVersion are retained for the local
response but excluded from the provider payload. No GPS, route, zone identifiers,
absolute timestamps are sent. In turn two, the sanitised question and up to 400
characters of driver-provided text ARE sent to Groq as data; they may contain
personal information. Do not claim that no free text is sent. No reflection persistence or
application request-body logging is added. Check infrastructure logging and Groq
project retention settings separately; no zero-retention claim is made here.

Prompt rules prohibit invented causes and judgmental language. JSON validation
checks structure and length, NOT semantic truth. Real output evaluation for all
three types remains required; this release does not guarantee hallucination-free
output. The public endpoint has no new authentication/rate-limit layer; configure
deployment abuse controls and provider budget limits before broad exposure.

Offline tests mock the provider and verify whitelisting, three event types,
invalid-event rejection, unavailable service, malformed output and graceful failure.
They never consume API quota. Run `python -m pytest -q`. A live Groq request has not
been made by this delivery. The previous handovers' statements that no AI adapter
exists describe earlier releases; this file is authoritative for the new endpoint.
The legacy `/trip-discussion-prompts` remains a rule-based compatibility endpoint.

## Two-turn live check

Run `powershell -ExecutionPolicy Bypass -File .\tools\Test-GroqTwoTurn.ps1`
after starting the server. Use `-BaseUrl https://YOUR-SERVICE` to test a deployment.
This makes two real provider requests and consumes quota.
