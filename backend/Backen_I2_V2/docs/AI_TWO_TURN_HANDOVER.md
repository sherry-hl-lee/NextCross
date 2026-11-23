# Two-turn AI reflection backend handover

## Scope and source

Based on the previously delivered NextCross_Groq_Backend_No_Phone package.
This is not a checkout of the current GitHub branch. Merge the changes into your
latest backend rather than overwriting unrelated newer work. No deployment has
been performed. Notification, historical crash, speed analysis and trip prompt
implementation files are unchanged.

Changed implementation: `app/ai_reflection.py`, `app/main.py`.
Added tests: `tests/test_ai_two_turn.py`. Existing AI tests remain unchanged.
Added live script: `tools/Test-GroqTwoTurn.ps1`.
Updated documentation: README and GROQ_SETUP plus this handover and JSON examples.

## API contract

Both routes use camelCase fields, reject extra properties and return the unchanged
discussion-only disclaimer. See adjacent JSON files for copyable requests.

- POST `/api/v1/ai/reflection/question`: original event envelope, optional tripSummary
  and optional roadContext. Returns tripId, eventId, aiStatus, question, reason,
  disclaimer. question contains headline, question and exactly three quickReplies;
  each reply is at most six whitespace-separated words.
- POST `/api/v1/ai/reflection/advice`: same envelope plus questionAsked (up to 500
  characters) and driverContext. Returns tripId, eventId, aiStatus, advice, reason,
  disclaimer. advice contains acknowledgement, two or three advice sentences and
  encouragement. Empty/whitespace-only driverContext is rejected. Control characters
  are replaced, whitespace collapsed and long driverContext truncated to 400
  characters BEFORE validation. Non-string values are rejected.
- POST `/api/v1/ai/reflection`: deprecated, retains its original response structure
  and GOOD-only generation gate for compatibility.

New routes accept GOOD and UNKNOWN; only POOR blocks generation. UNKNOWN prompts
require approximate, tentative wording. Optional averageAccelerationMs2 is accepted
for acceleration/deceleration; sign, derived average and peak consistency are checked
with a 0.15 m/s² rounding tolerance. It is not accepted for speed exceedance events.

## Failure behaviour

HTTP 200 aiStatus values: COMPLETED, UNAVAILABLE, INVALID_EVENT, FAILED.

UNAVAILABLE reasons: not_configured, rate_limited, provider_authentication,
provider_unavailable, provider_request_rejected, timeout, network_error.
POOR returns INVALID_EVENT / insufficient_data_quality without calling Groq.
Malformed, extra, empty, refused or truncated provider output returns FAILED /
invalid_or_unexpected_response. Malformed input returns HTTP 422 / INVALID_EVENT
with the appropriate question/advice field null and sanitised validation locations.
Input values and provider error bodies are not echoed. HTTP 200 alone is not success.

## Privacy and limitations

Numeric facts, quality, event type, relative time and optional counts go to Groq.
No trip/event IDs, detectorVersion, GPS coordinates, place names or zone IDs are
forwarded. Turn two DOES send the cleaned question and driver text to Groq; user
text can contain personal information. No application persistence or payload logging
is added, but provider/infrastructure retention must not be represented as zero.

Both routes are stateless. Android resends the same event and question and checks
returned tripId/eventId. This is correlation, not authenticated ownership or proof of
unchanged event data. Discard late responses after trip/event changes and disable
repeated submit while waiting. No server session, rate limiter or budget manager was
added. Keep Groq keys on the backend, not in APKs.

Optional roadContext: referenceLimitKmh, limitStatus and schoolZoneActive. A non-null
reference or school activation requires static/scheduled/inactive status; uncertain
limits cannot be treated as known. A speed-event reference must agree with its
metrics. The fields are caller supplied, not independently verified by the AI route.
Populate them only from applicable event-time lookup, not the location/time after
completion. Automatic roadContext enrichment is deferred.

Prompts avoid endorsing reaction timing or asserting causes. Quick replies are
hypothetical typing shortcuts, not recorded facts. JSON/length validation does not
verify truth, English wording, absence of invented causes or prompt-injection
resistance. Real-model evaluation is required before marking those checks passed.

## Testing

Run from project root: `python -m pytest tests -q`.
Delivery run: **112 passed, 5 skipped**, including **67 passing AI tests**.
Environment: Python 3.12 using the existing execution environment's installed
packages; this was not a clean reinstall of requirements.txt. Deprecation warnings
were non-fatal. Five live checks were not enabled; no real Groq or Neon call was made.
Mocks verify provider payloads, schema, quality handling and failure mappings.
Injection tests verify text stays in the data message; they do not prove real-model
resistance. No quota is consumed by these offline tests.

With server running, manually execute in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\Test-GroqTwoTurn.ps1
# After deploying, replace YOUR-SERVICE with your maintained backend URL:
powershell -ExecutionPolicy Bypass -File .\tools\Test-GroqTwoTurn.ps1 -BaseUrl 'https://YOUR-SERVICE'
```

Each run makes two REAL provider calls and consumes quota. Review responses for
correct type, tentative UNKNOWN wording, no invented causes and no endorsement of
action timing. Repeat for all three event types via /docs; evaluate normal,
distracted, unlawful, flippant, gibberish and instruction-injection descriptions.
Live structured-output support and model output have not been verified in this delivery.

## Merge and deploy

The archive contains `backend/` (complete baseline plus changes) and
`changed-files/` (only modified/added files).
If your GitHub version contains newer work, merge ai_reflection.py and the validation
handler change in main.py; do not replace all of main.py blindly. Add the new tests,
script and docs. Ensure the AI router is included at /api/v1 as in this baseline.

Render: retain database environment variables, configure GROQ_API_KEY as secret and
GROQ_MODEL as model setting, deploy the updated commit. Start command remains
`uvicorn app.main:app --host 0.0.0.0 --port $PORT --no-access-log`.
Then test /health, /docs and both routes; give Android the maintained base URL.
GitHub upload by itself does not deploy unless Render auto-deploy is enabled.
