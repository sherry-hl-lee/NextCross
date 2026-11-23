# iteration3_v1 — Statewide Victoria speed rules and crash hotspots

This release supports separate, reviewed updates for speed, crash and calendar data.
The code/package name is `iteration3_v1`; the existing database schema remains
**`iteration2_v2` with nine tables**. The owner confirmed the production table count
on 2026-10-06; current live rows and permissions were not re-audited by this change.

Start with the [operator runbook](docs/operations.md), [Actions and candidate
commands](deploy/README.md), and [maintenance guide](docs/maintenance.md).
The [Japanese change overview](docs/changes.ja.md) explains the before/after behaviour.
PRs run tests; monthly schedules prepare independent speed/calendar candidates,
September schedules prepare annual crash candidates starting in 2027, and weekly
checks inspect status. **Production publication is an explicit manual action on
an exact saved candidate.** These schedules become active only after this workflow
is merged to `main`; read-only monitoring also needs its configured secret.

Crash data remains the accepted 2021–2025 release until the September 2027 update
successfully publishes 2022–2026. Later September candidates move the five-year
window once per year. Other dataset updates never refresh crash data. Preserve
both original source CSVs and the prepared crash baseline before handover.

Calendar review supports recorded corrections and bounded verified coverage.
Bounded publication is disabled unless the operator explicitly confirms that the
deployed backend enforces the documented coverage bounds. Backend application
code is maintained separately. Saved historical reports retain their original
version names, dates and counts.

This pipeline prepares and loads statewide speed rules from Victoria's Speed Zones and Speed Signs. Crash data covers 2021–2025 casualty crashes involving drivers aged 18–25 and is stored as fixed clusters and individual events. Neither dataset is restricted to selected LGAs. The backend owns request-time queries and API responses against the loaded tables.

This version retains the `iteration2_v2` behavior that replaced the 500 m / 1 km crash grids in `iteration2_v1` with clustering using a 100 m neighbourhood distance. Events outside clusters remain available as individual map points.

The Git deliverable contains download, build, migration, load and disposable-database capacity measurement stages. `migrate` creates all nine tables in `iteration2_v2`: two road/rule tables, two calendar tables, three crash tables, one notification table and one report email dispatch table. `notification_event` has the same eight columns and composite primary key as the previous version and is created entirely from SQL in this folder. See [backend_handover.md](backend_handover.md) for the data contract a backend must implement and [data_model.md](data_model.md) for the relationships between the nine tables.

Each committed Python source file documents its role, inputs, outputs and side effects in English; migration SQL explains the stored schema in comments. The [code responsibility guide](code_responsibilities.md) maps the committed files to pipeline stages and describes the backend and notification boundaries.

The committed pipeline does not read another iteration's code, configuration or data at runtime. After moving the folder, recreate the Python environment and provide the dependencies below and PostgreSQL / PostGIS. If raw data is not included, use this version's download commands to obtain it. Git excludes raw data, processed data and `.venv`. Capacity measurement reads this version's `notification_event` when present; it does not require the previous notification table.

The local reference implementations `src/iteration3_v1_pipeline/lookup.py`, `src/iteration3_v1_pipeline/crash_lookup.py` and `postgresql/queries/` are ignored and are not included in a clean checkout. Their associated `tests/test_lookup.py`, `tests/test_postgis.py` and `tests/test_crash_postgis.py` are also local-only. The committed CLI does not provide `lookup` or `crash-lookup`; backend developers implement the documented request behavior in the backend.

The saved [standalone validation record](../../archive/iteration2_v2/reports/standalone_validation.json) reports CLI startup and 58 unit tests passing in an isolated temporary directory before notification storage was added. It tested the earlier, broader local package including reference queries. The later 77- and 81-test records below also describe that broader suite; they are historical evidence, not test counts or standalone validation of the current pipeline-only Git deliverable. Current test commands and coverage are listed below.

## Production baseline and historical Neon deployment

The seven tables in the 2026-09-12 version were deployed to the existing Neon `neondb` database on that date. That historical deployment record covers the original seven tables. The query schema is `iteration2_v2`. The [initial speed and crash deployment record](../../archive/iteration2_v2/reports/neon_deployment.json) and [notification table deployment record](../../archive/iteration2_v2/reports/neon_notification_migration.json) contain row counts, constraints and effective permission checks.

On 2026-09-16, `004_calendar_coverage.sql` was committed to Neon and `iteration1_v2_backend` was granted `SELECT` on the new table. A fresh read-only connection verified the eight-table schema, effective read-only backend privileges on `calendar_coverage`, and preservation of the original tables and their row counts, definitions, owners and privileges. The 27 existing `calendar_period` rows were unchanged. `calendar_coverage` contained **0 rows at that September 16 check**; this is historical evidence, not the current coverage state. This deployment did not reload data or change the backend source. See the [calendar migration record](../../archive/iteration2_v2/reports/neon_calendar_migration.json).

The original seven-table row counts below were recorded on 2026-09-12 and confirmed unchanged by the 2026-09-16 migration.

| Table | Rows |
| --- | ---: |
| `road_speed` | 471,571 |
| `speed_rule` | 14,104 |
| `calendar_period` | 27 |
| `crash_build` | 1 |
| `hotspot` | 1,451 |
| `crash_event` | 19,101 |
| `notification_event` | 0 |

The 2026-09-12 permission check recorded `iteration1_v2_backend` with `USAGE` on the new schema, `SELECT` on the six then-existing speed and crash tables, and `SELECT`, `INSERT` and `UPDATE` on the notification table. The separate `calendar_coverage` grant was completed for this Neon deployment on 2026-09-16. New database installations still require an administrator to grant it; pipeline migrations do not grant runtime permissions. It has not been granted notification `DELETE` or `TRUNCATE`, or write access to speed and crash data. The new notification table occupied 16,384 bytes including indexes when added. Speed and crash row counts were unchanged by that addition.

The old `iteration1_v2.road_speed` table and its geometry simplification trigger function were removed after confirming that all 462,627 legacy IDs, speeds and geometries matched the new version. See the [legacy speed retirement record](../../archive/iteration2_v2/reports/neon_legacy_speed_retirement.json). The old `iteration1_v2.notification_event` table is retained. The historical handover recorded a pending backend redeployment; confirm the running service separately rather than treating this old note as its current state.

## Data and reproduction

This validation used copies of saved snapshots of the official datasets. Copy sources, sizes and SHA-256 hashes are recorded in `data/raw/reused-sources.json`. [source_snapshot.json](../../archive/iteration2_v2/reports/source_snapshot.json) records input, dependency and calendar hashes for traceability. Downloading the data again may produce different counts from those below.

| Input | Purpose |
| --- | --- |
| `data/raw/speed-zones.geojson` | Speed zones, posted speeds, conditions, direction and road geometry |
| `data/raw/speed-signs.geojson` | Evidence for regulatory speeds, days, times, orientation and current sign status |
| `data/raw/calendar/<snapshot_id>/` | Official DataVic CSV and `sources.json` for one year |
| `data/processed/calendar/<build_id>/` | Validated `calendar.csv`, `calendar_manifest.json` and `calendar_report.json` for calendar publication |
| [historical calendar](../../archive/iteration2_v2/reference/calendar.csv) | Historical 2026 calendar snapshot; no longer an input to normal `load` |
| `data/raw/crashes.csv` | Crash IDs, dates, times, coordinates, young-driver involvement and casualty counts |
| `data/raw/accident-location.csv` | Road and intersecting-road labels joined by crash ID |

Crash and location data comes from Transport Victoria's [Victoria Road Crash Data](https://opendata.transport.vic.gov.au/dataset/victoria-road-crash-data/resource/5df1f373-0c90-48f5-80e1-7b2a35507134) and [Accident Location](https://opendata.transport.vic.gov.au/dataset/victoria-road-crash-data/resource/e1beb92b-5836-448a-9769-e7aa6a9e7413) resources. The historical 2026 calendar sources and regional exceptions remain recorded in [calendar_sources.json](../../archive/iteration2_v2/reference/calendar_sources.json). New calendar builds retain their own source evidence in the manifest and publish verified years through `calendar_coverage`.

Use Python 3.12 or later and PostgreSQL / PostGIS, and run the following commands from this directory. The pinned dependencies were validated with Python 3.14.6. `.env` is not loaded automatically: provide the connection through an environment variable or `--database-url`.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.lock
export PYTHONPATH=src
export DATABASE_URL='postgresql://user:password@localhost:5432/iteration2_test_v2'
```

`requirements.lock` pins the versions used for validation. Allowed dependency ranges are in `requirements.txt`. The speed build also fixes the date used to assess sign validity.

```bash
python -m iteration3_v1_pipeline build --as-of 2026-09-12
python -m iteration3_v1_pipeline crash-build --start-date 2021-01-01 --end-date 2025-12-31
```

If saved raw files are unavailable, or you explicitly want to refresh the source data, run these commands before building:

```bash
python -m iteration3_v1_pipeline download
python -m iteration3_v1_pipeline crash-download
```

Use `--zones` / `--signs` with `build`, and `--source` / `--locations` with `crash-build`, to change input files. Both commands accept `--output` to change the output directory. The default is `data/processed/`. Speed outputs are `road_speed.csv`, `speed_rule.csv` and `report.json`; crash outputs are `crash_event.csv`, `hotspot.csv` and `crash_report.json`. Do not start a load while the output artifacts are being published.

`migrate` applies [001_schema.sql](postgresql/migrations/001_schema.sql), [002_crash.sql](postgresql/migrations/002_crash.sql), [003_notification.sql](postgresql/migrations/003_notification.sql), [004_calendar_coverage.sql](postgresql/migrations/004_calendar_coverage.sql) and [005_report_email_dispatch.sql](postgresql/migrations/005_report_email_dispatch.sql) in one transaction to create all nine tables. Migration creates no verified calendar years automatically. Reapplying it to an existing database preserves notification and report email dispatch rows. The existing `crash-migrate` command remains available for creating only the crash tables. To create only the report email dispatch table, see the [standalone SQL command and runtime grants](backend_handover.md#report-email-dispatch).

`load` replaces only `road_speed` and `speed_rule` in one transaction. `crash-load` replaces the three crash tables in its own transaction. `calendar-load` replaces one year in `calendar_period` and its `calendar_coverage` metadata together, preserving other years. These loaders preserve notification rows; the application records notifications as events occur. Neither normal speed loads nor calendar migration reads the historical reference CSV.

## GitHub Actions deployment

The [workflow](../../.github/workflows/iteration3-v1-neon.yml) offers `prepare`,
`publish` and `status`. A preparation run handles one dataset and never connects
to production. `publish` requires a successful main preparation run and artifact,
the exact candidate ID, and a review reason. It validates and archives the candidate
before loading only that dataset. It never downloads sources or runs migrations.

Monthly candidates run independently, so an incomplete next-year calendar does
not block speed updates. Weekly status requires the repository secret
`NEON_READ_ONLY_DATABASE_URL`; missing configuration is reported explicitly.
Publication retains `neon-production` and `NEON_DIRECT_DATABASE_URL`. PR tests use
only disposable PostGIS. Actual calendar corrections can be reviewed in a PR and
selected using `calendar_review_path`; no correction is inferred automatically.

Candidate archives and publication outcomes are retained in private GitHub Releases
before/after publication, with hashes and source provenance. Keep independent
handover/DB backups as described in the runbook. Actions artifacts retain 90 days
of convenient review material. Refer to [deployment instructions](deploy/README.md)
for exact inputs, UTC schedule times, rollback and backend compatibility requirements.
The former all-dataset helper is retained for historical verification and is not
the scheduled or documented production update path.

## Calendar download, build and publication

Calendar updates are a separate, year-at-a-time pipeline. `calendar-download` queries the [DataVic Important Dates catalogue](https://discover.data.vic.gov.au/dataset/government-dates-api-data) for the current official CSV download URL, then saves the CSV directly. It does not fetch or scrape holiday HTML pages, and it does not use DataStore JSON timestamps. The snapshot contains `important_dates.csv` and `sources.json`, with source URLs, retrieval/check times, resource metadata and SHA-256 hashes under `data/raw/calendar/<snapshot_id>/`.

`calendar-build` reads that snapshot and parses CSV dates explicitly as day/month/year; `9/03/2026` means 9 March. It selects the requested year's school-term boundaries and public holidays directly from the CSV. It emits `calendar.csv`, `calendar_manifest.json` and `calendar_report.json` under `data/processed/calendar/<build_id>/`. Neither stage connects to PostgreSQL or treats the historical reference CSV as expected output.

```bash
python -m iteration3_v1_pipeline calendar-download --year 2026

python -m iteration3_v1_pipeline calendar-build \
  --year 2026 --source-dir data/raw/calendar/SNAPSHOT_ID

python -m iteration3_v1_pipeline migrate

python -m iteration3_v1_pipeline calendar-load \
  --input data/processed/calendar/BUILD_ID --dry-run

python -m iteration3_v1_pipeline calendar-load \
  --input data/processed/calendar/BUILD_ID \
  --expected-current-build-id EXPECTED_CURRENT_BUILD_ID

python -m iteration3_v1_pipeline load
python -m iteration3_v1_pipeline crash-load
```

`calendar-download --output PATH` changes the raw snapshot root; `calendar-build --output PATH` changes the prepared-build root. `calendar-build --previous PATH` compares dates with an explicitly supplied previous local build without contacting a database. `calendar-load --dry-run` instead compares the candidate with the actual database and reports the current build ID without writing. The loader uses `DATABASE_URL` or `--database-url`, as other database commands do; `.env` is not loaded automatically. Use a pipeline writer connection for publication, not the backend runtime role.

**Known limitation: regional public holidays are not fully collected.** The normal acquisition uses the DataVic CSV; reviewed corrections additionally retain official evidence. It does not collect the municipality-specific substitute holidays or part-day holidays listed on [Business Victoria's regional public holidays page](https://business.vic.gov.au/business-information/public-holidays/victorian-non-metropolitan-public-holidays-2026). There is no manually maintained regional JSON or `--regional-overrides` option. A `READY` build and a `calendar_coverage` row do not establish complete regional holiday coverage. Calendar-dependent speed rules, including school-day rules, may therefore fail to reflect a local holiday in an affected area.

The manifest and report explicitly record `coverage_scope: "datavic_school_terms_and_public_holidays"` and `regional_holidays_complete: false`. Missing regional dates must not be described as confirmed non-holidays. Melbourne Cup remains a `REGIONAL_HOLIDAY_UNCERTAIN` marker based on the CSV's local-substitution qualification; this one marker does not identify the separate local holiday dates. The current 2026 CSV produces 18 rows: four school terms, 13 public-holiday dates and one Cup uncertainty date.

For offline reuse, `--import-dir` accepts a complete saved CSV snapshot, containing `important_dates.csv` and `sources.json`; it does not accept browser-saved HTML or regional supplements. Supply the actual source-check time in UTC, not the time files were copied:

```bash
python -m iteration3_v1_pipeline calendar-download \
  --year 2026 --import-dir /path/to/saved-csv-snapshot \
  --verified-at VERIFIED_AT_UTC
```

Import validates source hashes and metadata. It records the operator-supplied CSV check time separately from the import time. Normal downloads and builds require no manually maintained input file. `calendar-review` is the separate reviewed-correction path documented in [backend handover](backend_handover.md#reviewed-corrections-and-bounded-calendar-coverage).

Publication requires the expected school terms and holiday families for the requested year within the DataVic source scope, plus a `READY` manifest with matching CSV columns, row counts and hashes. Missing terms or holiday families stay pending; regional information outside that source scope is not a publication prerequisite. Duplicate or malformed rows and inconsistent Easter dates fail validation. The parser does not repair or guess source dates. In the August 2026 source CSV, 2027 contains duplicate holidays and an incorrect Easter Monday, and lacks the AFL holiday; it must not be published as a complete year. A year with school terms alone is not covered.

Original source snapshots and unreviewed annual bundles use schema version 3 and policy `victoria-datavic-csv-only-v3`. Old HTML snapshots and schema-2 bundles containing regional supplements are not accepted; download and build a new CSV-only snapshot. Reviewed bundles use schema 4; their bounds and review evidence are retained in the existing coverage manifest. `calendar-load --allow-bounded-coverage` is an explicit backend compatibility acknowledgement, never a shortcut around validation. Loading a CSV-only bundle replaces the target year, so any previously loaded regional supplement dates for that year will be removed; review the dry-run difference before publication.

The three calendar kinds remain `SCHOOL_TERM`, `PUBLIC_HOLIDAY` and `REGIONAL_HOLIDAY_UNCERTAIN`. School terms use the government term start, including a staff-only first day when the source distinguishes it. The CSV-derived Melbourne Cup uncertainty marker applies statewide for the whole date because the current schema has no local boundaries. Separately published local dates and part-day exceptions are omitted. The new policy version identifies this reduced source scope.

Before writing, `calendar-load` validates the artifact set. In one transaction it locks the target year, verifies `--expected-current-build-id`, replaces only that year's dates, and publishes the coverage row with its manifest. If another load has changed the current version, the write is rejected: repeat dry-run and review the new difference. Cross-year existing intervals are rejected rather than split. Failure rolls back both calendar tables. Saved candidates can be used to restore an earlier version through the same checked workflow.

A coverage row describes January 1 through December 31 within the DataVic source scope at `verified_at`; it does not establish complete regional holiday coverage or guarantee that the source will never change. Rebuilding identical source content reuses its existing immutable bundle and retains that bundle's original `verified_at`; another download/import/build run does not automatically advance the published verification timestamp. New snapshot metadata separately records the later acquisition or operator verification. Later DataVic changes require another download/build; separately announced regional holidays remain outside this pipeline. Migration alone does not register the old 2026 CSV as verified. See the [backend calendar contract](backend_handover.md#calendar-coverage-and-publication) for year checks, overnight rules, permissions and cache consistency.

## Notification table

`iteration2_v2.notification_event` has eight columns: `trip_id`, `event_id`, `occurred_at`, `status`, `resend_email_id`, `error_message`, `created_at` and `sent_at`. Its composite primary key is `(trip_id,event_id)`. `status` is restricted to `pending` (the default), `sent` or `failed`. There are no foreign keys to the crash or speed tables.

See [backend_handover.md](backend_handover.md#recording-notifications-and-updating-status) for notification SQL and status update contracts. The notification backend's `PostgresNotificationEventRepository` has been updated to reference this version's table. Keep using the existing `iteration1_v2_backend` role and connection URL. To switch the running service, follow the [handover procedure](backend_handover.md#switching-the-notification-backend): stop old writers, check and preserve history, then redeploy. The pipeline does not automatically copy old history or send notifications.

The read-only Neon check at `2026-09-12T05:25:41Z` found both notification tables empty, so there were no rows to copy at that time. The running service has not been redeployed. The [backend cutover record](../../archive/iteration2_v2/reports/notification_backend_cutover.json) records the preparation status.

The historical [notification validation record](../../archive/iteration2_v2/reports/notification_validation.json) reports 81 tests passing after the notification table was added: 58 unit tests and 23 PostGIS integration tests, with no failures, errors or skips. That broader local suite included the reference queries and tests now excluded from Git. Initial creation, migration reapplication, primary key and status constraints, and notification preservation through speed and crash reloads were verified on PostgreSQL 17.5 / PostGIS 3.5.2. This is not a result for the current committed test selection.

## Speed limit coverage

| Condition | Handling |
| --- | --- |
| Normal speeds | Preserves the original `iteration1_v2` extraction and makes these speeds available statewide as static limits |
| School / Shop | Builds day and time rules from matching current signs; School rules also use terms and public holidays |
| Mixed Normal and School / Shop operation | Matches conditional signs; the normal speed remains `NULL` if unconfirmed |
| Explicit Shared Zone | Accepts a static limit only in supported cases where `Other` notes identify a Shared Zone and matching unconditional Shared Zone signs confirm the same speed |
| Activation-based or electronic signs | Stores `dynamic` because public data does not provide the current activation state |
| Seasonal conditions, uninterpreted conditions or unresolved sign matching | Stores `unresolved`; a speed with source conditions is not assumed to be the normal speed |

The normal speed is derived from a matching normal-speed sign, or agreement between normal-speed sections directly connected to both ends of the zone. The latter is a data-derived inference restricted to matching endpoints, directions and speeds, not a record of a manual site inspection. Sign proximity alone cannot establish which regulated section it belongs to. Direction, connectivity and sign ownership are also checked; unresolved cases remain unknown.

The `speed-zones` fields `zone_conditions` and `speed_limit` describe a section's condition category and posted speed. Structured weekdays and time windows come from `speed-signs`. The input snapshots used by this version do not provide a shared reference ID from each sign to its applicable speed-zone section, so the build uses the following matching steps. The internal `conditional` list contains sections with usable IDs/geometries that did not qualify for the retained normal-speed selection, including conditions beyond School / Shop.

| Stage | Current matching conditions and purpose |
| --- | --- |
| `read_signs()` | Reads signs eligible on the snapshot date within 30 m of a section requiring additional resolution; retains headings, schedules and parsing errors |
| `attach_signs()` | Selects roads whose headings differ from the sign's target travel heading by at most 45 degrees, retaining candidates within 1 m of the shortest distance. `road_ids` is the inferred result, not an input foreign key |
| `components()` | Traverses adjacent sections with GeoJSON-derived endpoints (Shapely `boundary`) within 0.2 m, equal condition tuples and source speeds, and known headings differing by at most 45 degrees |
| `resolve_group()` | Compares candidate sign schedules across the whole group before assigning shared rules; retains each original section ID and export geometry |

The 45-degree condition in `components()` compares published road `direction` attributes, not intersection angles calculated from line geometry. It is this implementation's heuristic for limiting schedule sharing across changes in direction. A bend greater than 45 degrees can still belong to the same real regulated zone; the threshold is neither a legal boundary nor proof of a shared regulation. Existing bend/crossing tests verify the implemented behavior, not the optimality of 45 degrees. Relaxing the condition can recover continuous sections while also joining adjacent sections with the same School / Shop category and speed but different schedules. Road names and free-text annotations are not compared during grouping.

Heading compatibility is pairwise: a 0 → 45 → 90 degree chain can form one group when the other connection conditions hold. Connected endpoints and equal conditions/speeds alone do not establish a shared regulated area or schedule. The 45-degree sign-to-road check in `aligned()` is separate from the road-to-road check in `components()`.

The build reads signs and establishes candidate road assignments through `read_signs()` → `attach_signs()` before resolving any group. For an A–B–C group, it does not finalize the schedule as soon as it encounters A's sign.

| Signs accepted as candidates for the same group | Schedule handling |
| --- | --- |
| Only A supplies a parsed schedule | The schedule can be shared by A, B and C; the off-hours speed still needs separate evidence |
| A and C supply identical schedules | Shares that schedule with both sign IDs as evidence |
| A and C supply different schedules | Leaves the entire group `unresolved` with reason `missing_or_conflicting_schedule`; does not prefer A or split the group at a sign location |
| A candidate sign has a schedule parsing error | Leaves the group's schedule unresolved |

This comparison only covers signs passing the distance, heading, candidate ownership, regulatory type, School / Shop category and matching source-speed filters. For example, a sign at C with a different speed from the source section is excluded before schedule comparison, so it does not trigger a schedule conflict. An empty rule tuple without a parsing error does not itself block another candidate's consistent schedule. The build therefore does not detect every disagreement among nearby signs. A sign added after the input snapshot requires rebuilding from updated inputs and reloading the dataset.

Removed signs, signs awaiting installation and signs installed after `--as-of` are excluded. `AHEAD`, supplementary and `END` signs do not become independent speed rules. The saved reference snapshot covers **2026 only**; current published coverage must be read from `calendar_coverage`, not assumed from this snapshot or the first/last period row. Applying stored rules to a requested time is the backend's responsibility.

No boundary dataset has been added for regional alternatives to the Melbourne Cup holiday. The historical 2026 reference snapshot has ten uncertain dates, but the current CSV-only build has only the Cup marker. The other local holiday dates are not carried forward from that historical snapshot. For a stored uncertainty marker, a result depending on holiday status remains `unknown`; other results may still be determined. The omitted regional dates are a known coverage limitation, as described in the calendar section above.

## Crash selection and aggregation

The cohort is crashes in `2021-01-01..2025-12-31` with `YOUNG_DRIVER_18_25 > 0 AND INJ_OR_FATAL > 0`. This identifies young-driver involvement, not fault. Fatalities and serious injuries count **people of all ages** involved in the selected crashes.

All selected crashes with coordinates are projected to EPSG:3111 and clustered once using DBSCAN with `eps=100m, min_samples=3`. The 100 m setting is a neighbourhood distance, not a maximum radius for an entire cluster. Membership does not change when filtering by year, month or time period. Events outside clusters are retained as individual points, and records without coordinates are retained with `NULL` coordinates.

Each crash's local time is assigned to the following fixed periods during the build. The stored classification lets the backend aggregate matching records across years and seasons. Start times are inclusive and end times exclusive. These boundaries do not use actual sunrise or sunset times.

| Season | Months | `morning` | `daytime` | `night` | `late_night` |
| --- | --- | --- | --- | --- | --- |
| Summer | Dec–Feb | 06:00–12:00 | 12:00–20:00 | 20:00–24:00 | 00:00–06:00 |
| Autumn | Mar–May | 07:00–12:00 | 12:00–18:00 | 18:00–24:00 | 00:00–07:00 |
| Winter | Jun–Aug | 07:00–12:00 | 12:00–17:00 | 17:00–24:00 | 00:00–07:00 |
| Spring | Sep–Nov | 06:00–12:00 | 12:00–19:00 | 19:00–24:00 | 00:00–06:00 |

Backend circular-area queries must **filter actual crash points by distance, time period and date before counting by cluster**. A cluster's full count must not be included merely because its representative point lies inside the circle. The pipeline stores representative points at real crash locations, with labels from official location records. When a representative is outside the requested circle, the backend should use an in-area member for display. Events without coordinates cannot be assigned to the circle; their whole-dataset count must remain distinct from area totals. The backend implements these queries and response fields; no request-time query implementation is supplied in this Git deliverable.

## Validation and capacity

Results from the saved speed data, using 2026-09-12 as the validity date:

| Item | Count |
| --- | ---: |
| Input / retained sections | 471,572 / 471,571 |
| Legacy normal-speed / additional sections | 462,627 / 8,944 |
| Static speed sections | 462,628 |
| Scheduled sections | 7,280 |
| Scheduled sections with confirmed / unconfirmed normal speed | 5,108 / 2,172 |
| Speed rules | 14,104 |
| Activation-based / unresolved sections | 209 / 1,454 |
| Signs accepted as current / all signs | 106,790 / 106,828 |

One section with invalid geometry was excluded. Not every retained section has a determinable speed. Before the notification table was added, the historical broader local validation passed 77 tests with no skips, including 19 integration tests against a real PostGIS database. That suite included the now-excluded reference-query and mixed storage/query tests. The separate historical record above covers the notification addition.

There are 19,101 retained crashes. Of the 19,100 with coordinates, 6,899 belong to 1,451 clusters and 12,201 remain individual points. The remaining record lacks coordinates. The selected crashes contain 270 fatalities and 6,901 serious injuries in total. Selection and exclusion details are in [crash_build.json](../../archive/iteration2_v2/reports/crash_build.json).

The historical [validation.json](../../archive/iteration2_v2/reports/validation.json) records that UUIDs, speeds and input EWKT for all 462,627 legacy normal-speed rows matched the actual `iteration1_v2` extraction, and all 19,101 crash database rows matched the generated CSV. It also records 12 local reference-query checks across Melbourne, Geelong and Ballarat: their aggregates matched the actual in-circle crash and casualty counts in all four time periods. Those query implementations are now excluded from Git, so these measurements do not validate a current backend implementation. The values in one existing notification test row were also confirmed unchanged during that validation.

The capacity budget is **200,000,000 bytes (200 MB, approximately 190.73 MiB), excluding speed tables**. The following [measurements](../../archive/iteration2_v2/reports/crash_capacity.json) came from loading all rows into a disposable PostgreSQL 17.5 / PostGIS 3.5.2 database before the new notification table was added. The notification component in this record is one test row using the previous version's DDL.

| Scope | Bytes | MB (decimal) |
| --- | ---: | ---: |
| Combined generated crash and hotspot CSVs | 3,763,233 | 3.76 |
| Three crash tables, indexes and TOAST | 7,151,616 | 7.15 |
| Total including one existing notification test row | 7,184,384 | 7.18 |
| Remaining capacity within the 200 MB budget | 192,815,616 | 192.82 |

These measurements are within budget. The notification table represents one test row using the same DDL as the existing table (32,768 bytes), not production volume. Future notification growth must be estimated separately. The entire database occupied 267,406,483 bytes, or 26,995,859 bytes after subtracting this version's speed tables. The latter is also a diagnostic figure that includes shared catalogs and other overhead; it is not the budget scope in the table above.

Remeasurement is restricted to disposable databases whose names start with `iteration2_test` or `iteration2_v2_test`. It reloads the three crash tables and adds the measured size of the current `iteration2_v2.notification_event` table when present. Notification rows are not changed.

```bash
python -m iteration3_v1_pipeline crash-measure --output reports/crash_capacity.json
```

The [DataVic CSV validation record](../../archive/iteration2_v2/reports/calendar_csv_validation.json) records the CSV-only working-tree check on 2026-09-17: all 80 tests passed, including 12 disposable PostGIS integration tests. A real DataVic CSV download produced 18 rows for 2026 without a regional supplement, and both its manifest and report explicitly mark regional coverage incomplete. A CLI smoke test previewed and published that bundle only to the disposable database, removed an old synthetic regional row and returned `unchanged` on repeat. Source errors, pending years and old snapshot versions are covered by the automated tests. The historical reference CSV was not used as expected output. No Neon writes, commits or pushes were performed.

The [deployment validation record](../../archive/iteration2_v2/reports/deployment_validation.json) adds the Actions helper checks: all 87 selected tests passed with no skips in a clean temporary copy of the Git deliverable, including 12 disposable PostGIS tests. A synthetic complete deployment and repeat both succeeded, preserving a notification and another calendar year's row. The workflow passed actionlint 1.7.12. That record preceded GitHub-hosted execution. The later [September 17 production run](https://github.com/FIT5120-2026S2-TP10/sdg-platform/actions/runs/35229825111) and [October 6 PR tests](https://github.com/FIT5120-2026S2-TP10/sdg-platform/actions/runs/37447294187) are separate historical evidence; the new operational workflow still requires its first GitHub run after merge.

The earlier [HTML pipeline validation record](../../archive/iteration2_v2/reports/calendar_validation.json), dated 2026-09-16, is retained as history. Its 74-test result and Business Victoria HTTP 403 limitation describe the replaced HTML acquisition method, not the current CSV download.

Run the committed test modules from this directory:

```bash
PYTHONPATH=src:tests python -m unittest \
  test_build test_crash test_calendar test_calendar_download test_calendar_cli \
  test_calendar_postgis test_notification_postgis test_deployment test_operations \
  test_calendar_review test_backup test_workflow_support -v
```

`test_build`, `test_crash` and `test_calendar` check offline transformations and calendar validation using local fixtures. `test_calendar_download` and `test_calendar_cli` cover snapshot acquisition and command dispatch. `test_deployment` checks artifact validation and deployment orchestration without production credentials. `test_calendar_postgis` checks calendar publication and preservation, while `test_notification_postgis` checks migration and notification preservation. Database tests require `ITERATION2_TEST_DATABASE_URL` pointing to a disposable database whose name starts with `iteration2_test`; otherwise they are skipped. These suites recreate the `iteration2_v2` schema, so do not point them at a shared or deployed database.

Before the calendar pipeline was added, the pipeline-only selection was checked in a temporary snapshot filtered by Git's file selection, with all six local-only paths absent. Test discovery found 36 tests: 32 passed and four notification database tests were skipped because no test database URL was supplied. All nine pipeline command help screens opened, and the two removed query commands were rejected. This historical check performed no database operations; its test and command counts do not describe the expanded calendar suite or verify the new database behavior.

Select these modules explicitly: wildcard discovery also finds ignored local test files if they remain in the working directory. The excluded speed and crash PostGIS suites cover storage constraints, loader rollback and integrity as well as reference queries. The current notification storage suite now covers speed/crash replacement rollback and both email-history tables, while the calendar suite verifies publication and bounded coverage; the historical 58/77/81-test reports must not be presented as its results.
