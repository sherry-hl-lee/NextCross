# Backend handover — iteration2_v2

This handover delivers the data pipeline: source downloads, prepared speed and crash datasets, verified yearly calendars, schema migrations, and loaders for `iteration2_v2`. `migrate` creates all 8 tables: 2 road/rule, 2 calendar, 3 crash, and 1 notification table. The backend owns request validation, query implementation, connection management, authentication, and HTTP responses. The committed pipeline does not provide speed/crash lookup commands or a runtime query library. See [README.md](README.md) for setup and pipeline commands, and [data_model.md](data_model.md) for columns and relationships.

Speed and crash data are independent of notification recording, with no foreign keys between these domains. The query guidance below explains how to interpret the stored data and suggests response conventions for backend implementation; it is not an API implemented or deployed by this pipeline.

The 7 tables in the 2026-09-12 version were deployed to Neon on that date. That historical deployment did not include the later `calendar_coverage` table; this document does not claim that the new migration or calendar publication has run on Neon. The permission check at that time recorded the existing connection role, `iteration1_v2_backend`, with `USAGE` on `iteration2_v2`, `SELECT` on the 6 speed and crash tables, and `SELECT`, `INSERT`, and `UPDATE` on the notification table. Notification `DELETE` and `TRUNCATE` privileges have not been granted. Verification results are saved in the [initial Neon deployment record](reports/neon_deployment.json) and [notification migration record](reports/neon_notification_migration.json). The new notification table contained 0 rows when added; speed and crash row counts and the old notification table were preserved.

The old `iteration1_v2.road_speed` table was deleted on the same day. Speed queries must use `iteration2_v2.road_speed`. After deletion, all new-version row counts, speed and crash queries, and read privileges were verified. Details are in the [retirement record](reports/neon_legacy_speed_retirement.json). The local notification backend code has also been updated to reference the new table. The old `iteration1_v2.notification_event` table is retained; see [Switching the notification backend](#switching-the-notification-backend) below for deployment to the running service.

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

Regional substitute and part-day holidays are not fully collected by the supported CSV-only and reviewed calendar policies. The historical 2026 reference had 10 uncertain dates; current CSV-only output retains only the Melbourne Cup uncertainty marker. An absent regional date is not proof that no local holiday applies. Return `regional_holiday_uncertain` when a stored marker can affect the result. Calendar-independent cases, including a time outside the rule's window or a Shop weekday condition that already matches, remain resolvable. A potentially active rule with unsupported calendar coverage or unresolved holiday status makes the answer unknown even if another rule has a known match. Precise local holiday support requires additional source and boundary work.

## Calendar coverage and publication

The pipeline publishes calendar data independently of road/rule data. Its `calendar-download` saves the official DataVic CSV and provenance. `calendar-build` validates a full year; `calendar-review` additionally supports recorded manual corrections and a bounded verified interval. `calendar-load` publishes one year's rows and coverage together. The backend reads the database contract directly and does not import the pipeline package.

`calendar_coverage` has one row per publication year, with `year`, `build_id`, `policy_version`, `verified_at`, `loaded_at` and `source_manifest`. A year key alone does not establish coverage. `app/calendar_coverage.py` recognizes exactly these contracts:

| Schema / policy | Required status and scope |
| --- | --- |
| 3 / `victoria-datavic-csv-only-v3` | `READY`, January 1–December 31, scope `datavic_school_terms_and_public_holidays` |
| 4 / `victoria-reviewed-calendar-v4` | `READY` for a full year or `READY_BOUNDED` for a shorter inclusive interval; scope `reviewed_victoria_school_terms_and_public_holidays`; `coverage_complete: true`; matching `full_year_complete` / `requires_bounded_backend` flags |

Both require matching row/manifest year, build ID and policy, explicit ISO `coverage_start` / `coverage_end`, and the declared incomplete regional coverage limitation. Schema 4 retains `unconfirmed_events`; an unconfirmed AFL lower bound must be strictly after the covered interval. Unsupported policies, missing/malformed metadata, contradictory completeness claims, pending status and out-of-range dates fail closed. Do not infer coverage from period-row minimum/maximum dates or from a retained future holiday row.

For each calendar-dependent rule, convert the request to Melbourne time and check its evaluation date against the recognized manifest and inclusive coverage bounds. For an overnight window, use the start date: a request early on January 1 may require the previous year's coverage for December 31. Return the existing `calendar_out_of_coverage` reason with an unknown speed when the necessary manifest/date is unsupported. Calendar-independent outcomes, such as a static road or a time outside all applicable windows, can still be resolved. A missing table or failed query remains HTTP 503, not a non-holiday or empty successful result.

`speed_repository.load_speed_data` reads periods plus year/build ID/policy/manifest in one `REPEATABLE READ READ ONLY` transaction. There is currently no calendar cache: each request reads a fresh snapshot, so a changed build and its new bounds are visible together on the next request. If caching is added later, cache period rows and manifest together by `(year, build_id)` and invalidate both on a version change. Verification describes source information checked then, not a promise that no later announcement exists; the pipeline separately records later checks of unchanged source content.

School terms retain the government term-start interpretation. The three-column period table does not encode regional boundaries or exact holiday hours. A reviewed correction does not add regional/part-day coverage. Only a result affected by a stored uncertainty marker becomes `regional_holiday_uncertain`.

The original full-year build rejects missing or unconfirmed required dates. Reviewed publication preserves the original source and correction evidence; it may defer only an explicitly reviewed future AFL uncertainty outside the supported interval, without inventing its date. Partial coverage must be published as `READY_BOUNDED`; the pipeline blocks it unless the operator explicitly confirms a compatible backend. This code implements that compatibility, but a commit or passing test does not establish that the running service has been redeployed. Deploy the updated `Backen_I2_V2` service before opting into bounded publication. No new database columns or API response fields are required. The existing year lock, expected-build check and transactional publication remain pipeline responsibilities.

The database administrator must grant the runtime role access to the new table after migration; the pipeline does not execute this grant automatically:

```sql
GRANT SELECT ON iteration2_v2.calendar_coverage TO iteration1_v2_backend;
```

The role also needs its existing schema `USAGE` and `SELECT` on `calendar_period`. Use a separate pipeline writer connection for calendar publication. Neither this permission example nor the new source code is evidence that the migration, grant, publication or backend adaptation has been deployed.

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

## Recording notifications and updating status

`iteration2_v2.notification_event` is created by this version's [003_notification.sql](postgresql/migrations/003_notification.sql). `migrate` applies 001, 002, 003 and 004 in one transaction, so notification setup does not require running the old pipeline. Reapplying the migration preserves existing rows.

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

The historical notification validation run passed 81 tests (58 unit and 23 PostGIS integration tests), with 0 failures, errors, or skips. That run included local speed/crash query tests and their reference query implementation, which are excluded from the committed data-pipeline deliverable. It is not the size or result of the current Git test suite. The saved run covered first-time creation, reapplication, constraints, and notification preservation after speed/crash reloads; see the [notification validation record](reports/notification_validation.json).

## Switching the notification backend

In the workspace file `backend/US2.3-Permitted-Area-Breach-Backend/app/repository.py`, `PostgresNotificationEventRepository` has been updated to use `iteration2_v2.notification_event` for all registrations, status reads, success updates, and failure updates. API inputs, outputs, and duplicate detection remain the same. This code change alone does not update an already running service.

1. Pause new requests to the old notification backend and wait for in-flight requests to finish.
2. If the old table contains history, copy all 8 columns to the new table. Preserve existing `(trip_id,event_id)` values, statuses, and timestamps. If the same key has different values, review the discrepancy before proceeding. Retain the old table and do not resend emails.
3. Verify that every old key, status, and timestamp exists in the new table, then redeploy the updated backend. If deployment updates files in place, restart the process after updating them.
4. Continue using the existing `iteration1_v2_backend` connection URL for `RUNTIME_DATABASE_URL`. A new role is unnecessary. If this setting is absent, `DATABASE_URL` is used; if neither is set, SQLite is used.

Switching to an empty table without copying old history causes previously handled events to be treated as new. Normal `migrate` only creates tables; it does not copy history or redeploy the backend. No notifications were sent and the old table was not deleted during this work.

A read-only Neon check at `2026-09-12T05:25:41Z` found 0 rows in the old table, 0 rows in the new table, and 0 old keys missing from the new table. No history copy was needed at that time. However, the running old service may add writes, so check again after pausing it during the actual switch. The running service has not been redeployed. See the [backend cutover verification record](reports/notification_backend_cutover.json).

## Updates and operational boundaries

Speed `load` replaces only `road_speed` and `speed_rule`; `crash-load` replaces its 3 crash tables. `calendar-load` replaces one year in `calendar_period` and its `calendar_coverage` metadata together. Each operation has its own transaction and preserves the other domains, including `notification_event`. Normal speed loads no longer read `data/reference/calendar.csv` or reset calendar publication. Only one `crash_build` is active at a time. Build IDs and input SHA-256 hashes identify the generation conditions. Crash CSV sizes, hashes, and row counts are checked against the manifest before loading.

The 200 MB budget excludes speed data and covers the measured storage of the 3 crash tables and this version's `notification_event`. `crash-measure` is restricted to disposable test databases: it reloads crash data and reads notification-table storage if the table exists. It does not change notification rows or reference the old schema.

Before the new notification table was added, the full-data measurement recorded 7,151,616 bytes for crash tables, or 7,184,384 bytes including one test notification row in a table created from the old DDL. These historical measurements are retained in the [capacity report](reports/crash_capacity.json), [full-data validation](reports/validation.json), [input hashes](reports/source_snapshot.json), and [crash selection audit](reports/crash_build.json). The one-row test notification measurement is not a storage guarantee for future notification growth in production.
