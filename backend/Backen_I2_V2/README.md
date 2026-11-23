> Variant: With_Email_Test_Switch. Latest offline verification: 175 passed, 5 skipped. See docs/PROMPT_ADDENDUM_HANDOVER.md.

> Report preview and email: see [REPORT_EMAIL_HANDOVER](docs/REPORT_EMAIL_HANDOVER.md). Configure mail provider and dispatch table before real sending.

> Two-turn AI update: see [handover](docs/AI_TWO_TURN_HANDOVER.md). The new routes accept UNKNOWN recordings; the deprecated route retains its old contract.

# NextCross backend — historical crashes and post-trip speed analysis

## Groq reflection added

Start with [Groq setup](docs/GROQ_SETUP.md) for the new
`POST /api/v1/ai/reflection`, secure key configuration and the live smoke-test script.
The new endpoint calls Groq; the legacy discussion endpoint remains rule-based.
Earlier statements below about missing AI integration describe the prior release.

## New speed-analysis implementation

Read [the speed analysis handover](docs/SPEED_ANALYSIS_HANDOVER.md) first for the
new APIs, exact contract, Windows setup, engineering defaults and limitations.
New routes: `POST /api/v1/trips/{trip_id}/speed-analysis` and
`GET /api/v1/speed-limit`. Matching is a conservative adjacent-zone heuristic,
not a routing-graph map matcher. Real speed database checks and Android integration
remain to be run. The real AI provider is not integrated.

Use `--no-access-log` when starting Uvicorn to avoid recording coordinate-bearing
diagnostic query URLs. The included Docker command already applies this setting.
The following crash-backend notes describe the previous delivery; its recorded
test count and live-check status are historical, not a statement about later
checks the team performed.

This revision implements US4.1/US4.2 against the supplied `iteration2_v2.zip` schema and backend handover. It replaces the previous missing `crash_lookup.py` import with backend-owned SQL. Do not copy the pipeline into the API, rebuild DBSCAN, download accident CSVs, run migrations, or reload the shared database to run this service.

## Scope and integration status

- `GET /api/v1/historical-crash-context`: implemented using the published PostGIS tables.
- `POST /api/v1/notification-events`: retained from the previously supplied notification prototype, including simulated delivery. This is not verified as a copy of the currently deployed Render source. When merging into that source, preserve its notification provider and schema settings.
- `POST /api/v1/trip-discussion-prompts`: retains the rule-based fallback; no AI provider has been integrated.
- `/health` indicates web-process liveness only; `/docs` exposes the contract.

Local verification: 24 tests passed, 1 explicit Neon test skipped. Offline tests exercise HTTP validation, mapping, repository orchestration/aggregation with test doubles, and existing notification/prompt behaviour. They do not execute PostGIS spatial functions. The live Neon connection and reference counts have **not** been verified by this delivery. Local tests ran with FastAPI 0.141.1, Pydantic 2.13.4, pytest 9.1.1 and httpx 0.28.1 from the available environment; the supplied dependency pins are retained from the previous project and should be tested after installation in your deployment environment.

## Windows setup

Extract into a new folder and open PowerShell in the directory containing this README:

```powershell
py -3 -m venv .venv
& .\.venv\Scripts\python.exe -m pip install -r requirements.txt
& .\.venv\Scripts\python.exe -m pytest -q
```

Configure the existing backend-role connection in your local environment or Render secrets. A dedicated historical-query URL may be set as `HISTORICAL_DATABASE_URL`; otherwise the historical query uses `RUNTIME_DATABASE_URL`, then `DATABASE_URL`. An already configured notification connection can remain unchanged. Never put credentials in source or send them with a ZIP.

```powershell
# Paste the connection when prompted; it is not displayed or typed into command history.
$dbSecure = Read-Host 'Backend database connection URL' -AsSecureString
$env:HISTORICAL_DATABASE_URL = [System.Net.NetworkCredential]::new('', $dbSecure).Password
$env:HISTORICAL_CRASH_USE_MOCK = 'false'
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

The API does not automatically read `.env` or `.env.example`. With no configured URL it returns 503 for historical queries. `HISTORICAL_CRASH_USE_MOCK=true` is an explicit offline mode returning `no_dataset`, not real data.

In another PowerShell window:

```powershell
$uri = 'http://127.0.0.1:8000/api/v1/historical-crash-context?latitude=-37.8136&longitude=144.9631&radiusMeters=1000&timePeriod=night'
Invoke-RestMethod -Uri $uri | ConvertTo-Json -Depth 10
```

## Historical contract

The route retains required Android query fields `latitude`, `longitude`, `radiusMeters`, `timePeriod`, and optional `startDate`/`endDate`. The database handover suggests a default radius, but the Android HTTP handoff requires radius explicitly; this API preserves that requirement. All coordinates/radii must be finite; radius is greater than 0 and at most 50,000 metres.

The loaded build defines the period, currently 2021-01-01 through 2025-12-31. An omitted date endpoint uses its corresponding build boundary. `periodStart` and `periodEnd` always describe dataset coverage; `requestedStartDate` and `requestedEndDate` preserve the caller's filters. Any explicitly supplied endpoint outside coverage returns `outside_dataset_period`, including partial overlaps. A reversed explicit date range returns 422.

The exact stored `time_period` enum (`morning`, `daytime`, `night`, `late_night`) is used. No UTC or seasonal conversion occurs in this API. Casualty totals include all ages in eligible young-driver-involved injury crashes, not only young drivers.

Each query uses its own connection with `REPEATABLE READ READ ONLY`, a 10-second statement timeout, and bound parameters. Build metadata and events therefore come from the same snapshot during atomic dataset replacement. The schema enforces one active build; queries additionally restrict events and hotspots to that build ID. This connection is never used for notification writes.

Only actual located accident points inside the requested circle/date/period contribute to counts. Fixed clusters retain their ID even with one or two matches. They are not reclustered or moved into `individualPoints`. Only matching records with null `hotspot_id` appear as individual points. Missing-coordinate totals are reported separately as dataset-wide `dataset.unlocatedEventRows`.

If a cluster representative is inside the query circle, it is displayed. Otherwise the nearest matching member to that representative is selected, with accident ID as the tie-breaker. The original representative coordinates remain in the response.

Successful states: `recorded`, `no_recorded_crashes` (zero totals), `outside_dataset_period` (null totals), and `no_dataset` (null totals and metadata). Missing tables, connection problems, or invalid database responses return 503. They are not reported as zero crashes or client validation failures.

## Live read-only acceptance check

Run only after configuring a backend-role URL in the same terminal:

```powershell
$env:NEXTCROSS_RUN_NEON_CHECK = '1'
& .\.venv\Scripts\python.exe -m pytest tests/test_crash_repository.py::test_live_neon_reference -q
Remove-Item Env:NEXTCROSS_RUN_NEON_CHECK -ErrorAction SilentlyContinue
```

The data teammate's 16 September reference for Melbourne CBD / 1000m / night expects 51 crashes, 0 fatalities, 11 serious injuries, 17 clusters and 17 individual points. This test queries the actual service layer, performs no database writes, and checks those values. A later dataset build can legitimately change the expected values; confirm the build before updating the test. Do not hardcode these counts in the API.

## Notification cutover is separate

The supplied database handover describes a migration to `iteration2_v2.notification_event`, but warns against switching to an empty table before copying old idempotency history. This package retains the previous prototype's `iteration1_v2.notification_event` target. It does not migrate history, change permissions, redeploy Render, or prove which table the running backend uses. Use the handover cutover procedure if a table switch is required, or preserve the latest deployed notification implementation while adding only the historical modules.

## Merge and deploy

Merge `app/historical_service.py`, `app/historical_schemas.py`, `app/historical_routes.py`, their router registration, and tests into the current backend branch. `psycopg[binary]` is the runtime database dependency; the pipeline's pandas/geospatial build dependencies are unnecessary. The included Dockerfile supports Render's `PORT` setting. Configure the URL in Render, disable mock mode, run the real query, then ask Client 1 to disable the Android mock. Do not replace an updated production notification implementation with this older prototype snapshot.

`docs/database_handover.md` and `docs/crash_schema_reference.sql` are supplied references, not commands to run on Neon. Speed/calendar APIs are outside this change. US5.3 still needs separate AI-provider and Client 2 integration work.

## Client multipart report email (2026-10-05)

New endpoint: POST /api/v1/trip-reports/email, using meta JSON plus optional reportPdf/routeMap attachments. See docs/TRIP_REPORT_EMAIL_CLIENT_CONTRACT.md for the agreed contract, limits, deployment, and client responsibilities. Old JSON report endpoints remain. New multipart dependency is included in requirements.txt.

## Turn-2 prompt addendum

See docs/PROMPT_ADDENDUM_HANDOVER.md for wording changes, offline-test limits and the optional four-case live check.
