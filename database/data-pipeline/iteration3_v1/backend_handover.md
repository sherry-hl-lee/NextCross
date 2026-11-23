# Backend handover — iteration3_v1

The code release and CLI package are now `iteration3_v1` and
`iteration3_v1_pipeline`. The database schema is still `iteration2_v2`:
backend SQL, connection roles and notification history require no cutover for
this rename. The deployment records below describe earlier runs.

This handover delivers the data pipeline: source downloads, prepared speed and crash datasets, verified yearly calendars, schema migrations, and loaders for `iteration2_v2`. `migrate` creates all 9 tables: 2 road/rule, 2 calendar, 3 crash, 1 notification, and 1 report email dispatch table. The backend owns request validation, query implementation, connection management, authentication, and HTTP responses. The committed pipeline does not provide speed/crash lookup commands or a runtime query library. See [README.md](README.md) for setup and pipeline commands, and [data_model.md](data_model.md) for columns and relationships.

Speed and crash data are independent of notification recording, with no foreign keys between these domains. The query guidance below explains how to interpret the stored data and suggests response conventions for backend implementation; it is not an API implemented or deployed by this pipeline.

The 7 tables in the 2026-09-12 version were deployed to Neon on that date. That historical deployment did not include the later `calendar_coverage` table. The permission check at that time recorded the existing connection role, `iteration1_v2_backend`, with `USAGE` on `iteration2_v2`, `SELECT` on the 6 speed and crash tables, and `SELECT`, `INSERT`, and `UPDATE` on the notification table. Notification `DELETE` and `TRUNCATE` privileges have not been granted. Verification results are saved in the [initial Neon deployment record](../../archive/iteration2_v2/reports/neon_deployment.json) and [notification migration record](../../archive/iteration2_v2/reports/neon_notification_migration.json). The new notification table contained 0 rows when added; speed and crash row counts and the old notification table were preserved.

On 2026-09-16, the [calendar migration](../../archive/iteration2_v2/reports/neon_calendar_migration.json) added `iteration2_v2.calendar_coverage` to Neon and granted `iteration1_v2_backend` effective `SELECT` access without write privileges on that table. Verification after commit confirmed all eight tables, preservation of the original seven tables, and the unchanged 27 `calendar_period` rows. The new coverage table has **0 rows**, so no year is yet published under the coverage contract. No dataset reload or backend source change was performed; annual publication and backend coverage handling remain separate work.

The old `iteration1_v2.road_speed` table was deleted on 2026-09-12. Speed queries must use `iteration2_v2.road_speed`. After deletion, all new-version row counts, speed and crash queries, and read privileges were verified. Details are in the [retirement record](../../archive/iteration2_v2/reports/neon_legacy_speed_retirement.json). The legacy notification implementation documented below was updated to reference the new table; this is not a claim that the current consolidated backend has switched its notification writer. The old `iteration1_v2.notification_event` table is retained as temporary storage and is intentionally outside the owner's `iteration2_v2` backup scope. The historical [notification cutover guidance](#switching-the-notification-backend) below is separate from this backup decision; no notification migration is required for this release.

## Implementing backend queries

The backend should query the published tables directly using bound parameters. Speed queries use `road_speed`, `speed_rule`, and, when needed, `calendar_period` together with `calendar_coverage`. Crash queries use `crash_build`, `crash_event`, and `hotspot`. The backend can implement these reads in its own language and service; it does not need to import or deploy the data-pipeline package.

If a request reads multiple tables through separate statements, start a new `REPEATABLE READ, READ ONLY` transaction so every read sees the same dataset generation during a reload. A crash query that reads build metadata and matching events in one SQL statement already has a consistent statement snapshot. Finish any read-only transaction before starting notification writes.

Finding a nearby road is only the first step of a speed query. The backend must also evaluate the stored schedule and calendar at the requested instant, preserve unknown outcomes, and compare equally near candidates. The conventions below capture the behavior exercised during the historical validation; the backend is responsible for implementing and testing that behavior in its own service.

## Speed limits

Suggested backend request fields:

| Field | Validation and interpretation |
| --- | --- |
| `latitude`, `longitude` | Finite WGS84 coordinates. Latitude −90..90, longitude −180..180 |
| `observed_at` | A timestamp with a UTC offset, e.g. `2026-09-12T08:45:00+10:00`. Convert it to Melbourne time for evaluation |
| `heading` | Optional. North is 0°, east is 90°; `0 <= heading < 360`. Keep roads within a 67.5° heading difference and roads whose direction is unknown |
| `radius_m` | `0 < radius_m <= 1000`. Default: 30m |

A suggested response contains `speedKmh`, `status`, `sourceSpeedZoneId`, and `reason` when applicable. The dataset does not define an `inTargetArea` field. `sourceSpeedZoneId` represents an official speed-zone segment UUID, not a crash or physical-road ID. The following statuses are request-time outcomes for the backend to derive; they are not all stored values of `road_speed.status`.

| `status` | `speedKmh` and meaning |
| --- | --- |
| `static` | Static speed limit |
| `scheduled` | Speed limit from a rule active at the specified time |
| `inactive` | Confirmed normal speed limit outside the conditional time window |
| `unknown` | `null`. Insufficient rule, normal-speed, calendar, or other information |
| `dynamic_unknown` | `null`. The current state of an activated sign is unavailable |
| `ambiguous` | `null`. Conflicting candidates whose distances are within 1cm of the nearest distance |
| `no_match` | `null`. No eligible road within the search radius |

Common `reason` values are `base_speed_unconfirmed`, `calendar_out_of_coverage`, `regional_holiday_uncertain`, `conflicting_active_rules`, `live_state_unavailable`, and `conflicting_nearest_candidates`. Do not replace an unresolved speed with 0km/h or a normal speed limit.

Use metre-based PostGIS distances to select eligible roads within the radius. Retain every candidate within 1cm of the nearest distance, evaluate each candidate, and return `ambiguous` if their effective speeds or unresolved reasons disagree. A stable distance/UUID ordering can select a representative only after the answers agree. A scheduled road needs its stored rules; conflicting active rule speeds or missing required rules remain unknown. A dynamic road cannot be resolved from this dataset because live sign state is not supplied.

Coverage is statewide across Victoria. The stored `is_legacy` field indicates that a segment came from the earlier normal-speed extraction; it does not control geographic processing branches. Added conditional segments and heading filters can change the nearest candidate, so a backend using the new dataset may return a different answer from the old API at the same coordinate.

Time windows include the start and exclude the end. For windows crossing midnight, weekdays and calendars are evaluated against the start date. School rules use government school terms, weekdays, and public holidays. Shop rules with ordinary weekday conditions also match on public holidays; a `Public Holiday` condition adds the applicable dates. Calendar coverage is independent of the crash aggregation years and is explicitly recorded per year in `calendar_coverage`; the historical reference CSV alone covered 2026.

The CSV-only pipeline does not collect the municipality-specific substitute or part-day holidays on the [Business Victoria regional holiday page](https://business.vic.gov.au/business-information/public-holidays/victorian-non-metropolitan-public-holidays-2026). Only the CSV-derived Melbourne Cup date is currently marked `REGIONAL_HOLIDAY_UNCERTAIN`. A stored uncertainty marker affects only results that need holiday status; results independent of it can still be resolved. Regional dates absent from this dataset are not confirmed non-holidays, and holiday-dependent or school-day results in affected areas may be incomplete. There is no regional override JSON or automatic local-boundary evaluation.

## Calendar coverage and publication

The pipeline publishes calendar data independently of road/rule data. `calendar-download` saves the official DataVic CSV and provenance, `calendar-build` validates the requested year within that source scope and creates a candidate artifact set, and `calendar-load` publishes that year. See the [calendar commands](README.md#calendar-download-build-and-publication). The backend owns request-time evaluation, API behavior and caching; it does not need to run the parser or import a local lookup module.

`calendar_coverage` has one row per publication year, with `year`, `build_id`, `policy_version`, `verified_at`, `loaded_at` and `source_manifest`. The **manifest's inclusive `coverage_start` and `coverage_end`**, not the year key alone, establish the supported interval. Existing schema-3 policy `victoria-datavic-csv-only-v3` covers January 1 through December 31 and records `coverage_scope: "datavic_school_terms_and_public_holidays"`. Reviewed schema-4 policy `victoria-reviewed-calendar-v4` records `coverage_scope: "reviewed_victoria_school_terms_and_public_holidays"` and may cover a shorter verified interval. Both record `regional_holidays_complete: false`; coverage does not establish that every local holiday was collected. Do not infer coverage from period-row minimum/maximum dates, or hardcode 2026. Migration does not create coverage for old rows automatically.

For each calendar-dependent rule, convert the request to Melbourne time and check its evaluation date against a supported manifest policy/status and the inclusive coverage bounds. Schema 3 requires `READY`; schema 4 permits `READY` for a full year or `READY_BOUNDED` for a shorter interval, with `coverage_complete: true`. Unknown policies/statuses or a date outside the bounds are unsupported; never infer completeness from a year row or `READY_BOUNDED` alone. For an overnight window, use the start date: a request early on January 1 may require the previous year's coverage for December 31. Return `calendar_out_of_coverage` when the necessary date is unsupported. Calendar-independent outcomes, such as a static road or a time outside all applicable windows, can still be resolved. A missing table, inconsistent manifest, or failed query is a service error, not a non-holiday.

Read `calendar_period` and `calendar_coverage` in the same SQL statement or one consistent read-only transaction. Cache them as one set for each `(year, build_id)`; refresh both together when that build ID changes. Combining new period rows with an old coverage manifest can give an answer that no published build supports. Monitor `verified_at` and failed refreshes: verification describes the official information available at that time, not a promise that no later holiday announcement exists. Identical source content reuses the existing immutable build, including its original verification time, so a successful refresh attempt does not necessarily advance the published timestamp. Consult the new snapshot metadata when reviewing later checks of unchanged content. For imported CSV snapshots, the operator supplies the actual CSV source-check time through `--verified-at`; `fetched_at` records the separate import time. There is no separately maintained regional input. Successful CSV refreshes do not resolve the documented regional coverage gap.

Policy `victoria-datavic-csv-only-v3` retains government term starts and the CSV-derived Melbourne Cup uncertainty marker, and removes the manually curated regional dates. The 2026 source produces 18 rows (4 school terms, 13 public holidays, 1 uncertain date). Old HTML and schema-2 supplemented bundles must be rebuilt as CSV-only schema 3. Table definitions and the load command are unchanged. Only a result affected by a stored uncertainty marker should become `regional_holiday_uncertain`; uncollected regional dates remain a known limitation.

The ordinary schema-3 path still requires the expected annual school terms and public-holiday families in DataVic. Unpublished or unconfirmed information, including an AFL holiday marked `TBC`, prevents a `READY` candidate; school terms alone do not establish the year. The reviewed path below can explicitly defer an unconfirmed AFL holiday outside its supported interval. The loader validates artifact hashes and counts, then takes a year-specific transaction lock and checks the supplied expected current build ID. A concurrent version change stops the write. It replaces only the target year's periods and coverage in one transaction, preserves other years and all other domains, and rolls back both tables on failure. Existing intervals spanning years are rejected rather than silently split.

### Reviewed corrections and bounded calendar coverage

Use `calendar-review` only after an operator checks the source against official evidence. Keep an intact `calendar-download` snapshot in `original/`, a separate corrected DataVic-format `corrected.csv`, and a `review.json`. Never replace the original CSV or edit its source digest to make a correction look official. The original, corrected file and review are copied into the immutable output bundle; the manifest records their hashes and exact added/removed rows. Revalidation reconstructs the result from these retained inputs.

The review has exactly the following fields. Values below are placeholders: **do not copy an attestation or lower bound without checking its evidence**. Use the actual SHA-256 of each file and actual UTC verification times.

```json
{
  "schema_version": 1,
  "reviewer": "Responsible operator name",
  "reviewed_at": "ACTUAL_UTC_REVIEW_TIME",
  "reason": "Explain each correction and why the stated coverage is complete.",
  "original_sha256": "SHA256_OF_ORIGINAL_CSV",
  "corrected_sha256": "SHA256_OF_CORRECTED_CSV",
  "coverage_start": "YYYY-MM-DD",
  "coverage_end": "YYYY-MM-DD",
  "coverage_complete": true,
  "evidence": [
    {
      "url": "https://www.vic.gov.au/school-term-dates-and-holidays-victoria",
      "checked_at": "ACTUAL_UTC_CHECK_TIME",
      "note": "Identify the evidence used for term dates and the interval attestation."
    },
    {
      "url": "https://business.vic.gov.au/business-information/public-holidays/victorian-public-holidays-2027",
      "checked_at": "ACTUAL_UTC_CHECK_TIME",
      "note": "Identify corrected dates, remaining uncertainties and evidence for any lower bound."
    }
  ],
  "unconfirmed_events": []
}
```

Evidence URLs must be official Victorian government HTTPS pages. The tool validates the record and its hashes; the named reviewer is responsible for checking the linked evidence. All four complete school terms and all holiday families remain mandatory, even for bounded coverage, with one narrow exception: an unconfirmed AFL holiday may be represented in `unconfirmed_events` as `{"family":"afl","not_before":"YYYY-MM-DD","reason":"Evidence supporting this lower bound; exact date remains unknown.","evidence_urls":["URL_ALREADY_LISTED_IN_EVIDENCE"]}`. The checked lower bound must be strictly after `coverage_end`. This is an attestation about where uncertainty can begin, **not an inferred holiday date**. If evidence cannot support that bound, do not defer the event. Other missing families, a pending event that may affect the interval, and unconfirmed school terms fail validation. A confirmed AFL row conflicts with a deferral and requires updating the review.

```bash
python -m iteration3_v1_pipeline calendar-review \
  --year 2027 --source-dir /path/to/review/original \
  --corrected-csv /path/to/review/corrected.csv \
  --review-file /path/to/review/review.json
python -m iteration3_v1_pipeline calendar-load --input RETURNED_BUNDLE_DIR --dry-run
```

A partial build is `READY_BOUNDED`, sets `full_year_complete: false` and `requires_bounded_backend: true`, and retains the unresolved event in its manifest. Known dates outside the interval may remain in `calendar_period` for evidence; **their presence extends no coverage**. The backend must enforce bounds before using them. Extending the interval or confirming the AFL date requires a new review and build.

**Bounded writes are blocked by default before any database connection.** The CLI's `--allow-bounded-coverage` / Python's `allow_bounded_coverage=True` explicitly asserts that every consuming backend implements the bounds/status/policy checks above. A year-only backend is incompatible. The sibling backend repository's current `backend/Backen_I2_V2` checkout contains the local bounds-handling change; it must be reviewed, merged, deployed and verified before this opt-in. The pipeline does not deploy that backend change; generating a bounded candidate does not authorize bypassing this prerequisite. Dry-run can display bounded proposals without the flag. After compatible backend behavior has been verified, publication still requires the build ID returned by dry-run. An existing bounded version also requires the flag for a subsequent bounded write or replay.

The nine-table database shape stays unchanged: interval and review metadata live in `source_manifest`. Existing schema-3 full-year bundles and the ordinary `calendar-build` path continue to validate. Review does not add regional or part-day holiday support. Store reviewed bundles with the release backup; they can restore a previous version through the same validation and expected-build workflow.

The runtime role's `SELECT` grant was completed and verified on Neon on 2026-09-16. For another database installation, the administrator must apply the following after migration; the pipeline does not execute it automatically:

```sql
GRANT SELECT ON iteration2_v2.calendar_coverage TO iteration1_v2_backend;
```

The role also needs its existing schema `USAGE` and `SELECT` on `calendar_period`. Use a separate pipeline writer connection for calendar publication. The [Neon migration record](../../archive/iteration2_v2/reports/neon_calendar_migration.json) verifies table creation and the grant only; it does not establish annual calendar publication or backend adaptation.

## Crashes and hotspots

Suggested backend request fields:

| Field | Validation and interpretation |
| --- | --- |
| `latitude`, `longitude` | The same WGS84 coordinate requirements as speed lookup |
| `time_period` | Required. `morning` / `daytime` / `night` / `late_night` |
| `radius_m` | `0 < radius_m <= 50000`. Default: 1000m |
| `start_date`, `end_date` | Optional ISO dates. Both endpoints are inclusive, and the start must not be after the end. An omitted endpoint uses the corresponding boundary of the loaded period |

Eligible crashes are records from 2021–2025 where `YOUNG_DRIVER_18_25 > 0 AND INJ_OR_FATAL > 0`. Each crash has been assigned to a [seasonal time period described in the README](README.md#crash-selection-and-aggregation). Filter on this stored `time_period`; a UTC observation timestamp is not a substitute for it. Fatality and serious-injury counts include people of all ages in each eligible crash.

Suggested response fields derived from the loaded data:

| Output | Meaning |
| --- | --- |
| `area` | `kind: circle`, query center, and radius |
| `timePeriod`, `buildId` | Selected time period and the build that generated the fixed clusters |
| `periodStart`, `periodEnd` | Period covered by the loaded crash dataset |
| `requestedStartDate`, `requestedEndDate` | Requested date filters; `null` when omitted |
| `totals` | `crashCount`, `fatalities`, and `seriousInjuries` within the circle, date range, and selected time period |
| `clusters` | Array of matching crashes aggregated by fixed cluster |
| `individualPoints` | Array of matching crashes that do not belong to a cluster |
| `dataset` | Selection criteria, DBSCAN settings, total record counts, and missing-coordinate count |
| `countScope` | A string stating that counts are based on actual crash points inside the circle |

For each `clusters` entry, include `hotspotId`, display `latitude` / `longitude` / `locationLabel`, `displayPointSource`, original `representativeLatitude` / `representativeLongitude`, and `crashCount` / `fatalities` / `seriousInjuries`. If the stored representative lies inside the query circle, use it with `displayPointSource: cluster_representative`. Otherwise use a matching in-circle member, choosing the one nearest the representative and breaking ties by accident ID, with `displayPointSource: in_area_member`.

For each `individualPoints` entry, include `accidentId`, `crashDate`, `timePeriod`, `latitude`, `longitude`, `locationLabel`, `crashCount: 1`, `fatalities`, and `seriousInjuries`. Exclude crashes with missing coordinates from this array. Report their count across the entire dataset separately as `dataset.unlocatedEventRows`; their locations within or outside the query circle are unknown.

| `status` | Display and aggregation behavior |
| --- | --- |
| `recorded` | Matching crashes exist. Returns counts and arrays |
| `no_recorded_crashes` | No matching crashes in the loaded data. All `totals` counts are 0 |
| `outside_dataset_period` | A requested date endpoint falls outside the dataset period. `totals: null` and empty arrays |
| `no_dataset` | No loaded build. `totals: null`, `dataset: null`, and empty arrays |

Handle missing tables and connection failures as service errors. Do not convert them to `no_dataset` or zero crashes. Zero means that no stored eligible crashes match the query; it does not indicate safety or the probability of future crashes.

Cluster membership is calculated once using DBSCAN `eps=100m, min_samples=3` across eligible crashes with coordinates from all years and all time periods. The 100m setting is a neighborhood distance, not the maximum radius of an entire cluster. Preserve those memberships in backend and frontend queries. Filter actual crash points by circle, dates, and time period before aggregating by hotspot; do not count an entire cluster simply because its representative point falls inside the circle. Even when the original cluster contains at least 3 crashes, filtering can leave a displayed count of 1.

The frontend can display `individualPoints` as small map points alongside `clusters`. The published dataset does not supply `Time pattern` or `Road conditions` summaries. Backend cache keys should include `buildId`, center coordinates, radius, time period, and date filters; individual-cluster keys should also include `hotspotId`. Counts and display points for the same cluster can change with the query area.

## Report email dispatch

[005_report_email_dispatch.sql](postgresql/migrations/005_report_email_dispatch.sql)
creates `iteration2_v2.report_email_dispatch` with the backend's five-column contract:

| Column | Type | Constraint |
| --- | --- | --- |
| `request_hash` | `text` | Primary key |
| `digest` | `text` | Required |
| `status` | `text` | Required; `PENDING`, `ACCEPTED`, `FAILED`, or `UNKNOWN` |
| `reason` | `text` | Nullable |
| `created_epoch` | `bigint` | Required; backend-supplied Unix timestamp in seconds |

There are no defaults or foreign keys. The backend supplies required values and
owns status transitions. Dataset loaders leave this table untouched.

To apply only this migration, run from `data-pipeline/iteration3_v1` using an
owner connection in `DATABASE_URL`:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction \
  -f postgresql/migrations/005_report_email_dispatch.sql
```

The normal `migrate` command also applies it. Reapplying preserves existing rows;
`IF NOT EXISTS` does not repair an existing table with a different definition.
This change prepares SQL only; it does not record a production deployment.

If the backend connects as the existing `iteration1_v2_backend` role, an owner
can grant its runtime access separately (substitute the actual role if different):

```sql
GRANT USAGE ON SCHEMA iteration2_v2 TO iteration1_v2_backend;
GRANT SELECT, INSERT, UPDATE ON TABLE iteration2_v2.report_email_dispatch
    TO iteration1_v2_backend;
```

## Recording notifications and updating status

`iteration2_v2.notification_event` is created by this version's [003_notification.sql](postgresql/migrations/003_notification.sql). `migrate` applies 001 through 005 in one transaction, so notification setup does not require running the old pipeline. Reapplying the migration preserves existing rows.

| Column | Contract |
| --- | --- |
| `trip_id`, `event_id` | Required strings. Together they form a unique event identifier |
| `occurred_at` | Required `timestamptz`. Supply the event timestamp with a UTC offset |
| `status` | `pending` (default), `sent`, or `failed` |
| `resend_email_id` | ID returned by the delivery service; `NULL` if unavailable |
| `error_message` | Failure details; `NULL` when unset |
| `created_at` | Required `timestamptz`. Defaults to `CURRENT_TIMESTAMP` |
| `sent_at` | Timestamp recording successful sending. Defaults to `NULL` |

Reuse the same `(trip_id,event_id)` for the same event. Finish any speed/crash read-only transaction before executing and committing notification INSERTs or UPDATEs in a separate read-write transaction. The following SQL illustrates backend operations using psycopg bound parameters. Do not concatenate IDs or error messages into SQL strings.

```sql
INSERT INTO iteration2_v2.notification_event
    (trip_id, event_id, occurred_at)
VALUES (%s, %s, %s)
ON CONFLICT (trip_id, event_id) DO NOTHING
RETURNING trip_id;
```

If one row is returned, commit the new event registration. If no row is returned, the event already exists: read its status with the following SQL and do not send it as a new notification.

```sql
SELECT status
FROM iteration2_v2.notification_event
WHERE trip_id = %s AND event_id = %s;
```

After the application's send operation succeeds, record the delivery-service ID and success status in a separate write transaction.

```sql
UPDATE iteration2_v2.notification_event
SET status = 'sent', sent_at = CURRENT_TIMESTAMP,
    resend_email_id = %s, error_message = NULL
WHERE trip_id = %s AND event_id = %s;
```

On failure, record the error details. To match the current backend behavior, limit the error message to 500 characters in the application.

```sql
UPDATE iteration2_v2.notification_event
SET status = 'failed', error_message = %s
WHERE trip_id = %s AND event_id = %s;
```

The notification runtime role needs schema `USAGE` and `SELECT`, `INSERT`, and `UPDATE` on this table. Normal sending does not require `DELETE`, `TRUNCATE`, or write access to speed/crash tables.

The primary key prevents duplicate registration, but does not guarantee exactly-once delivery across the database and email delivery service. The current implementation suppresses a new send even when an existing key has a `pending` or `failed` status. The application must handle interruptions after sending but before status recording, and define its retry policy. This pipeline creates the table; it does not send email or perform status transitions.

The historical notification validation run passed 81 tests (58 unit and 23 PostGIS integration tests), with 0 failures, errors, or skips. That run included local speed/crash query tests and their reference query implementation, which are excluded from the committed data-pipeline deliverable. It is not the size or result of the current Git test suite. The saved run covered first-time creation, reapplication, constraints, and notification preservation after speed/crash reloads; see the [notification validation record](../../archive/iteration2_v2/reports/notification_validation.json).

## Switching the notification backend

The historical legacy implementation `backend/US2.3-Permitted-Area-Breach-Backend/app/repository.py` (now archived) updated `PostgresNotificationEventRepository` to use `iteration2_v2.notification_event` for all registrations, status reads, success updates, and failure updates. API inputs, outputs, and duplicate detection remain the same. This code change alone does not update an already running service.

1. Pause new requests to the old notification backend and wait for in-flight requests to finish.
2. If the old table contains history, copy all 8 columns to the new table. Preserve existing `(trip_id,event_id)` values, statuses, and timestamps. If the same key has different values, review the discrepancy before proceeding. Retain the old table and do not resend emails.
3. Verify that every old key, status, and timestamp exists in the new table, then redeploy the updated backend. If deployment updates files in place, restart the process after updating them.
4. Continue using the existing `iteration1_v2_backend` connection URL for `RUNTIME_DATABASE_URL`. A new role is unnecessary. If this setting is absent, `DATABASE_URL` is used; if neither is set, SQLite is used.

Switching to an empty table without copying old history causes previously handled events to be treated as new. Normal `migrate` only creates tables; it does not copy history or redeploy the backend. No notifications were sent and the old table was not deleted during this work.

A read-only Neon check at `2026-09-12T05:25:41Z` found 0 rows in the old table, 0 rows in the new table, and 0 old keys missing from the new table. No history copy was needed at that time. However, the running old service may add writes, so check again after pausing it during the actual switch. The running service has not been redeployed. See the [backend cutover verification record](../../archive/iteration2_v2/reports/notification_backend_cutover.json).

## Updates and operational boundaries

The owner's backup contract includes only the nine tables in `iteration2_v2`,
including that schema's `notification_event` and `report_email_dispatch`.
`iteration1_v2.notification_event` is temporary storage and is intentionally
excluded. The backup does not cover all backend notification state and does not
require a notification cutover. See the [backup/restore runbook](docs/operations.md).

Speed `load` replaces only `road_speed` and `speed_rule`; `crash-load` replaces its 3 crash tables. `calendar-load` replaces one year in `calendar_period` and its `calendar_coverage` metadata together. Each operation has its own transaction and preserves the other domains, including `notification_event`. Normal speed loads no longer read [historical calendar](../../archive/iteration2_v2/reference/calendar.csv) or reset calendar publication. Only one `crash_build` is active at a time. Build IDs and input SHA-256 hashes identify the generation conditions. Crash CSV sizes, hashes, and row counts are checked against the manifest before loading.

The 200 MB budget excludes speed data and covers the measured storage of the 3 crash tables and this version's `notification_event`. `crash-measure` is restricted to disposable test databases: it reloads crash data and reads notification-table storage if the table exists. It does not change notification rows or reference the old schema.

Before the new notification table was added, the full-data measurement recorded 7,151,616 bytes for crash tables, or 7,184,384 bytes including one test notification row in a table created from the old DDL. These historical measurements are retained in the [capacity report](../../archive/iteration2_v2/reports/crash_capacity.json), [full-data validation](../../archive/iteration2_v2/reports/validation.json), [input hashes](../../archive/iteration2_v2/reports/source_snapshot.json), and [crash selection audit](../../archive/iteration2_v2/reports/crash_build.json). The one-row test notification measurement is not a storage guarantee for future notification growth in production.
