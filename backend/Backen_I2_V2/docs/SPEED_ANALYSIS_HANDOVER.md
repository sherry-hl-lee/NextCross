# Post-trip speed analysis — backend handover

## Implemented scope

This adds a read-only speed lookup and a post-trip batch analysis pipeline to
the historical-crash backend. Existing notification and discussion endpoints
retain their prior behavior. No database migration, reload, or deployment was
performed. The real AI provider is NOT integrated: the discussion endpoint
still accepts only the existing acceleration/braking format and returns templates.
Android must not send the new speed events to that old endpoint yet.

## API contract

POST `/api/v1/trips/{trip_id}/speed-analysis` after End Trip. Path and body IDs
must agree. Required JSON fields and a synthetic request:

```json
{
  "tripId": "demo",
  "startedAtEpochMillis": 1789338600000,
  "endedAtEpochMillis": 1789338602000,
  "samples": [
    {"sampleId":"s1","observedAtEpochMillis":1789338600000,"latitude":-38.31820173862135,"longitude":142.48590563075354,"speedMps":8,"accuracyMeters":4,"bearingDegrees":135},
    {"sampleId":"s2","observedAtEpochMillis":1789338601000,"latitude":-38.31820173862135,"longitude":142.48590563075354,"speedMps":8,"accuracyMeters":4,"bearingDegrees":135},
    {"sampleId":"s3","observedAtEpochMillis":1789338602000,"latitude":-38.31820173862135,"longitude":142.48590563075354,"speedMps":8,"accuracyMeters":4,"bearingDegrees":135}
  ]
}
```

This is a synthetic API fixture, not a real recorded route or proof of live output.
Speed units are m/s; comparisons convert to km/h. Accuracy and bearing may be
null; unknown accuracy prevents event detection, while absent bearing disables
the heading filter. Speed may be null and then breaks detection continuity.
Limits: 20,000 samples, trip duration at most 24 hours, years 1970 through 2099
(epoch validation allows the exact 2100 boundary). Duplicate IDs, out-of-trip
times, negative values and non-finite numbers are rejected. Timestamps are sorted;
duplicate timestamps are flagged and excluded, not silently merged.

Response keys: `tripId`, `analysisStatus`, `processedSampleCount`,
`roadMatchingStatus`, `events`, `unmatchedIntervals`, `timeline`, `normalization`,
`detectionConfig`, `matchingMethod`, `limitations`. Exact schemas are available
in `/docs` and `/openapi.json` after starting the app.

Each timeline point contains `sampleId`, `offsetSeconds`, `context`, `matchStatus`
and `reason`. Context includes `speedKmh`, `status`, `sourceSpeedZoneId`, `reason`,
and nullable `dataVersion`. Speed tables have no published version field, so the
implementation does not fabricate one. The calendar coverage build is consumed
within the same database snapshot but is not a road-data version.

Calendar compatibility now validates the published manifest, rather than only
checking that a year exists. Schema 3 (`victoria-datavic-csv-only-v3`) supports
full-year `READY` coverage. Reviewed schema 4 (`victoria-reviewed-calendar-v4`)
supports full-year `READY` or explicitly bounded `READY_BOUNDED` coverage. Every
calendar-dependent evaluation must fall within the inclusive manifest bounds;
overnight rules use their start date, including December 31 for an early January
1 observation. Missing, pending, unknown-policy or inconsistent coverage returns
the existing `calendar_out_of_coverage` reason and never a guessed base limit.
Known period rows outside the bounds do not extend coverage. Regional omissions
and uncertainty markers retain their documented limitations. See the exact
[calendar contract](database_handover.md#calendar-coverage-and-publication).

The repository reads period rows and manifest together in a fresh repeatable-read
transaction for each request; there is no separately stale calendar cache. The
updated service must be deployed before opting into bounded pipeline publication.
This change itself performs no database write, dataset publication or deployment.

Example event (illustrative):

```json
{
 "eventId":"E_SPEED_0001","tripId":"demo","type":"SPEED_LIMIT_EXCEEDANCE",
 "status":"candidate","startSampleId":"s1","endSampleId":"s3",
 "startOffsetSeconds":0,"endOffsetSeconds":2,"durationSeconds":2,
 "referenceLimitKmh":20,"peakSpeedKmh":28.8,"peakExceedanceKmh":8.8,
 "averageExceedanceKmh":8.8,"peakExceedancePercent":44,
 "sourceSpeedZoneId":"e2e464b0-272d-4266-b3f8-780d71fa9592",
 "limitStatus":"static","dataQuality":"GOOD","detectorVersion":"speed-v1"
}
```

`averageExceedanceKmh` is a sample arithmetic mean, not time weighted. Duration
is last qualifying timestamp minus first, with no extension beyond observations.
Event IDs are deterministic sequence numbers within the same trip analysis;
use `(tripId,eventId)` together. The flat metrics contract is chosen deliberately
from the proposed handover examples. Client 2 must agree to it before integration.
`sourceSpeedZoneId` is a zone feature ID, never represented as a physical road ID.

Partial/unknown behavior: an uncertain sample has `matchStatus: UNKNOWN` or
`AMBIGUOUS`, an explanatory reason and no event assertion. If some usable samples
remain the analysis is `PARTIAL`; if fewer than two remain it is
`INSUFFICIENT_DATA`. Empty input returns `INSUFFICIENT_DATA` without a DB query.
An unknown-only school-window trip can contain timeline context such as:

```json
{"speedKmh":null,"status":"unknown","sourceSpeedZoneId":"009054e4-c898-4f5f-8ecd-a8c125995f86","reason":"calendar_out_of_coverage","dataVersion":null}
```

Missing credentials, tables, permissions, query failure and invalid server
configuration return generic HTTP 503, not zero limits or empty success.

GET `/api/v1/speed-limit` is the independent point-lookup diagnostic API.
Required query parameters: `latitude`, `longitude`, `observedAt` (offset-aware
ISO timestamp); optional `heading`, `radiusMeters` (default 30, maximum 1000).
Use URL encoding for `+` in offsets, or use a `Z` timestamp. This API follows
the nearest-distance contract; its result alone does not prove trajectory matching.

## Matching and database design

Candidate queries use geography metres, the 67.5-degree circular heading filter,
and bound parameters. All samples are processed in configurable chunks (500 by
default): one SQL round trip per chunk, not one per GPS point. Rules are fetched
once for the candidate zone IDs; calendars are read once. All reads use one
REPEATABLE READ, READ ONLY transaction and are rolled back/closed before returning.
No permanent cache or raw-route storage is introduced.

The point evaluator compares all candidates within 1 cm of the nearest distance
and preserves conflicting speeds/reasons as ambiguous. The trajectory matcher
is intentionally stricter: every usable sample must have exactly one eligible
zone within the search radius and adjacent usable same-zone support. Different
nearby zones are ambiguous even if their speeds agree. Gaps, impossible coordinate
jumps, unknown accuracy and invalid speed invalidate continuity. This suppresses
false certainty but can miss genuine events near parallel/overlapping zones.

This is a conservative sequence-aware prototype, NOT a full routing-graph map
matcher. It cannot establish topology, distinguish stacked roads reliably, or
recover unseen road transitions between samples. It does not invent calibrated
confidence. Validate with representative recorded/synthetic trajectories before
accepting the matching AC. No downsampling is used, so turns and original 1 Hz
samples are retained; configurable query chunks provide database batching.

Scheduled limits use Australia/Melbourne recorded time, start-inclusive and
end-exclusive windows and overnight start dates. School rules require published
calendar coverage. Relevant regional uncertainty remains unknown. Dynamic zones
remain `dynamic_unknown`. Missing coverage rows are valid unknown outcomes;
missing tables or queries are service errors. Schedule changes break events even
inside one source zone. No historical road-sign reconstruction is claimed.

## Detection configuration

Server environment variable `SPEED_DETECTION_CONFIG` accepts a JSON object:

```json
{"toleranceKmh":3,"minimumDurationSeconds":2,"minimumValidSamples":3,"maximumGapSeconds":5,"maximumAccuracyMeters":20,"maximumSpeedMps":70,"lookupRadiusMeters":30,"queryChunkSize":500}
```

Defaults are engineering prototype choices requiring team calibration, NOT
legally permitted speeding thresholds. Events require speed strictly above
limit plus tolerance. Return below threshold, changed zone/limit/status, missing
speed, unreliable GPS, unknown context, gaps and trip end finish a run. Gaps are
never counted toward continuous duration.

## Privacy and AI boundary

Analysis handles submitted GPS in request memory only. It performs no database
writes and creates no temporary route files. Success responses omit coordinates;
validation errors omit rejected values and request bodies. Application errors log
exception class only. Infrastructure logging still needs deployment inspection.
Run Uvicorn with `--no-access-log`: the diagnostic GET endpoint has coordinates
in its URL, which default access logs would otherwise record. Disable query/body
capture in reverse proxies, APM and database statement/parameter logging too.
Data leaves application scope after the request; Python memory is not securely
zeroed and infrastructure retention cannot be guaranteed by this source alone.

No LLM receives data in this release. Future AI input should explicitly select
event ID/type, offsets, duration and speed metrics from events; exclude full GPS,
zone IDs and absolute timestamps unless justified. A real AI provider and an
updated discussion endpoint contract remain separate work.

## Running and verification (Windows)

Install the project requirements in your virtual environment, then set
`SPEED_DATABASE_URL` securely, or reuse the existing `HISTORICAL_DATABASE_URL`,
`RUNTIME_DATABASE_URL` or `DATABASE_URL` (priority in that order). No migration
or data-pipeline installation is needed. Run:

```powershell
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --no-access-log
& .\.venv\Scripts\python.exe -m pytest -q
```

The DB role needs schema USAGE and SELECT on the four speed/calendar tables.
The live verification checks use the teammate's 17 September expected responses:
static 20, dynamic unknown, school in-window unknown without published coverage,
and school outside windows unknown without confirmed base speed. In a terminal
with the configured database connection:

```powershell
$env:NEXTCROSS_RUN_SPEED_CHECK = '1'
& .\.venv\Scripts\python.exe -m pytest tests/test_speed_analysis.py -k live_speed_reference -q
Remove-Item Env:NEXTCROSS_RUN_SPEED_CHECK
```

These four checks are read-only and skipped by default. If verified calendar
coverage is published later, update the school expectation only after confirming
the new data and rules. Offline tests exercise rules, continuity, validation and
repository orchestration; they do not execute real PostGIS functions. Live speed
queries, spatial matching accuracy, Android end-to-end integration, deployment
performance and infrastructure logging have NOT been verified by this delivery.

## Remaining team dependencies

- Database: publish verified calendar coverage when available; no default school
  speed should be substituted while coverage is absent.
- Client 1/2: agree flat event schema and trigger post-trip upload; keep Android
  acceleration/braking detectors and Room storage unchanged.
- Backend/team: test real trajectories, tune thresholds, verify latest production
  notification code before merging, and deploy after live acceptance checks.
- AI integration: choose provider/model and secret configuration; add a separate
  structured-event AI adapter with output validation and failure handling.
