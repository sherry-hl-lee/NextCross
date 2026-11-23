# Pipeline maintenance

This guide is for developers fixing or extending the accepted pipeline. Day-to-day
review, backup and recovery are described in [operations](operations.md). The
handover is a semi-manual, reviewed publication process with saved source inputs;
do not replace it with automatic production refreshes without an agreed change.

## Find the implementation

| Concern | Starting point |
| --- | --- |
| Data relationships and nine-table contract | [Data model](../data_model.md) and `postgresql/migrations/001` through `005` |
| Source/build/load responsibilities | [Code guide](../code_responsibilities.md) |
| Candidate review and publication | [Deployment guide](../deploy/README.md), `deploy/operations.py` and `deploy/workflow_support.py` |
| Calendar evidence and validation | `calendar_download.py`, `calendar.py`, `calendar_review.py`, `calendar_storage.py` |
| Speed interpretation | `build.py`, `signs.py`, `legacy.py` |
| Crash cohort and aggregation | `crash.py`, `crash_storage.py` and the operating policy |
| Backup and disposable recovery rehearsal | `deploy/backup.py` |
| Backend behaviour, roles and both email tables | [Backend handover](../backend_handover.md) |

The package is `iteration3_v1_pipeline`, while SQL continues to use
`iteration2_v2`. Keep that compatibility unless a separately reviewed backend
cutover requires a schema change. The pipeline owns schema definitions and
reference-data loading; the backend owns API behaviour, caches, email status
transitions and duplicate handling. No pipeline refresh should clear either
`iteration2_v2.notification_event` or `iteration2_v2.report_email_dispatch`.
The backup helper also covers only this schema. The owner intentionally excludes
`iteration1_v2.notification_event` because it is temporary storage; keeping that
exclusion does not require a notification cutover or an additional backup.

## Make a change

1. Record the problem, affected feature, current evidence and acceptance criteria
   in the team's issue tracker. Link source corrections and known limitations
   rather than silently editing a published artifact.
2. Branch from the accepted code, reproduce the issue with a small fixture and
   change only the affected responsibility. Use the pinned Python environment.
3. Update relevant policy/version metadata if interpretation or the dataset scope
   changes. Existing candidates are immutable; produce a new candidate rather
   than changing the old manifest to make validation pass.
4. Run the relevant regression tests and a disposable PostGIS check for storage
   changes. Review the diff and test evidence in a pull request.
5. Update the runbook, data contract and release record. The operator then reviews
   and publishes the exact candidate using the normal process.

Use descriptive Python names and existing module documentation conventions.
Document inputs, outputs and side effects. Keep SQL values parameterised and
table identifiers fixed or allowlisted. Keep credentials out of arguments,
fixtures, reports and exception text. Dependency updates need a clean install
and the applicable regression checks; retain the previous lockfile with its release.

The intended first annual crash update is September 2027 for 2022–2026, then
advance one year each September after checking published completeness. The
existing 2021–2025 version remains valid as a labelled historical baseline until
replacement succeeds. Calendar corrections must preserve original bytes and
official evidence. Bounded coverage also requires backend integration; parser
support alone does not enable the product to use those dates safely.

Use the latest due September release, not the current calendar year. If September
2027 was missed, status still warns in January 2028 and publication of release
2027 remains a valid catch-up through August 2028. September 2028 starts the 2028
window. Keep date-boundary tests for both January and September when changing
this policy.

Preserve the existing crash build with `prepare --dataset crashes --frozen-input
PROCESSED_DIRECTORY --frozen-source-dir ORIGINAL_DIRECTORY`; supply both directories
and omit `--release-year`. This verifies and packages the saved baseline rather
than downloading or recomputing it. Adoption is recovery preparation, not a new
annual publication. See [the baseline procedure](operations.md#preserve-the-existing-crash-baseline)
for exact files and commands.

The saved baseline's original flat Accident Location metadata lacks a confirmed
reporting-period end. The narrow baseline-adoption exception preserves those
bytes and emits `LEGACY_BASELINE_LOCATION_PERIOD_UNCONFIRMED` for operator review.
Do not synthesize metadata to remove the warning or extend this exception to new
annual sources. Offline adoption does not confirm the current production build.

Maintain the separation between immutable candidates and outcomes. Candidate
releases use `pipeline-candidate-<candidate_id>`; per-attempt publication releases
use `pipeline-publication-<run_id>-<attempt>`. Each starts with a verified draft
`intent.json` **before DB access**, then retains `publication.json` and any reviewed
`reconciliation.json`. A candidate archive says nothing about whether its DB write succeeded. Annual
publication reads durable history across all retained evidence releases, not
only the active DB cohort; a rollback must not erase the fact that an annual
period was already released. Local annual publication requires
`--publication-history` from the authenticated history helper; crash restores
require it too. Do not bypass the
guard with an invented empty history, remove old evidence, or record local
success as if it came from a GitHub run. Reconcile a successful DB commit whose
evidence upload failed before restoring or publishing again. History fails closed
on every unresolved intent, including interrupted restores. Preserve drafts as
operational evidence rather than deleting or manually finalizing them.

A failure in the write stage is uncertain even when a loader normally rolls back:
the commit may have succeeded before a connection/result failure. The workflow
finalizes only acknowledged completion or a known pre-write failure. Use the
[reviewed reconciliation procedure](operations.md#reconcile-an-interrupted-or-uncertain-publication)
for other outcomes; the CLI records human DB evidence and never inspects DB state
itself. `not_committed` also requires evidence that the write **never** committed.
Today's restored dataset cannot establish that, and an acknowledged success may
not be erased. If evidence is insufficient, leave the intent pending.

Restoring an annual crash candidate requires a prior successful record matching
that exact candidate ID and period. The adopted 2021–2025 baseline predates the
workflow and is the sole prior-success exception; resolved history remains
mandatory. Keep regression cases for commit/result loss, pending-intent blocking,
reviewed reconciliation, restore eligibility and annual reuse after rollback.

The schedule prepares independent candidates on the fifth of each month at
01:23 UTC; September adds annual crashes from 2027. Tuesday at 02:17 UTC is a
read-only status check. Neither schedule publishes. Keep schedule tests, docs and
operator notification/access settings aligned when changing these times. A job
cannot observe its own absence, so the operator also checks for missed runs.

For bounded calendars, deploy and verify the backend contract **before** opting
into publication. Test manifest policy/status/bounds, outside-range dates,
overnight/year boundaries and cache replacement for every consumer. Keep those
checks compatible until bounded records have been replaced by a supported
full-year version; do not roll back to a year-only backend while such records
remain active. This repository cannot establish that external backend readiness.

## Operating policy fields

[config/operations.json](../config/operations.json) is kept as valid JSON without
inline comments. This table documents its units and consumers; the policy module
also explains its inputs, exceptions and lack of database side effects. These
are initial review limits, not claims about provider guarantees or statistically
validated anomaly thresholds.

| Field | Current value | Meaning and consumer |
| --- | --- | --- |
| `schema_version` | `1` | Version accepted by `read_policy`; an unsupported version raises an error. |
| `crashes.baseline_start` / `baseline_end` | `2021-01-01` / `2025-12-31` | Fixed defaults for direct builds and the only supported pre-workflow recovery baseline. They do not roll with today's date. |
| `crashes.first_release_year` | `2027` | First planned annual replacement. A release year refers to the September publication cycle, not the last crash-data year. |
| `crashes.release_month` | `9` | First month in which that cycle is due. January–August catch-up uses the previous September cycle. |
| `crashes.window_years` | `5` | Number of complete calendar years in a candidate: release 2027 means 2022 through 2026, inclusive. |
| `crashes.minimum_retained_fraction` | `0.7` | If a crash build already exists, normal publication blocks a candidate with fewer than 70% of its event rows. Compare counts and investigate; do not lower the limit just to pass. |
| `crashes.maximum_missing_coordinate_fraction` | `0.15` | The missing-point count divided by candidate event rows must not exceed 15%. This checks the candidate itself, including during recovery. |
| `speed.minimum_retained_fraction` | `0.8` | With existing road rows, block normal publication if the candidate retains fewer than 80% of that count. |
| `speed.maximum_unresolved_fraction_increase` | `0.05` | Maximum increase in the unresolved-road share is five percentage points, not a relative five-percent increase. |
| `monitoring.calendar_warning_days` | `90` | Status requires supported coverage from the Melbourne check date through this many days ahead, crossing calendar years when necessary. |
| `monitoring.database_warning_bytes` | `800000000` | Alert when total database size reaches 800,000,000 bytes. This is a review threshold, not the Neon account's quota. |

Follow [the code flow](../code_responsibilities.md#read-the-maintenance-flow-in-this-order)
before changing these values. The monthly scheduler and candidate-format checks
also encode September, 2027 and the five-year range; changing dates or window
length requires updating that contract across modules, workflow documentation
and boundary tests. `read_policy` checks the schema version but does not perform
comprehensive validation of arbitrary configuration, so review types and units.

Publication uses the current policy. The candidate's saved `producer/operations.json`
records its preparation context; it is not a way to override current publication
checks. `--restore` bypasses current-versus-candidate count comparisons and the
normal annual timing gate, but still checks file identity and candidate quality,
bounded-calendar compatibility and applicable crash-history/restore eligibility.

## Documentation conventions for future changes

Keep the same English documentation style as `load.py` and `crash.py`. Explain a
module's responsibility and external effects first. Public build, publish,
archive and recovery functions document arguments, return values, failure cases
and side effects; short pure helpers need a concise contract. Comments should
explain an invariant or a decision, such as why a pending publication blocks
recovery, rather than translate each Python statement into prose.

Update comments, the [code guide](../code_responsibilities.md), relevant config
field descriptions and the [operator commands](operations.md) together when
changing behavior. Tests should state the failure or boundary they protect,
including why an apparently valid alternative must be rejected. Keep credentials
and private application rows out of examples. Do not insert comments into
CSV/JSON evidence or alter hashes merely to make a documentation change.

## Schema changes

The existing `migrate` applies numbered SQL files together. Migrations 001–005
create all nine current tables; production already has nine according to the
owner. `CREATE TABLE IF NOT EXISTS` preserves rows but does not repair a divergent
definition. Inspect the intended contract before changing it. Add a new numbered,
reviewed migration for future changes; do not edit old SQL to imply it upgraded
an existing table. Test both a fresh database and an upgrade containing history.
Runtime grants remain a separate deployment responsibility.

The `report_email_dispatch` contract uses a unique `request_hash`, mandatory
`digest`, `status` and Unix-seconds `created_epoch`; statuses are `PENDING`,
`ACCEPTED`, `FAILED`, `UNKNOWN`. It is independent of notification-event statuses.
Do not infer retry behaviour from the constraint: the backend owns that policy.
Agree any history retention with that owner so cleanup does not defeat duplicate
prevention. A rebuildable open-data snapshot is not a backup of application state.

## Regression checks

Run from `data-pipeline/iteration3_v1` after installing `requirements.lock`:

```bash
PYTHONPATH=src:tests python -m unittest test_build test_crash test_calendar test_calendar_download test_calendar_cli test_calendar_postgis test_notification_postgis test_deployment test_operations test_calendar_review test_backup test_workflow_support -v
```

Keep this module list aligned with the current CI configuration.
`ITERATION2_TEST_DATABASE_URL` must point to a disposable database
named `iteration2_test*` for PostGIS tests; those tests recreate the schema.
Missing DB configuration skips integration checks and is not evidence that they
passed. Never set this variable to production. Use explicit module selection
because ignored local reference-query tests are not part of a clean checkout.

The key regression claims are: input/hash validation before publication; calendar
version and coverage checks; preservation of both `iteration2_v2` email tables and unrelated
calendar years; rollback after failed speed/crash replacement; and restore-target
guards. Backup unit tests exercise client calls without a server; they complement,
not replace, the documented restore rehearsal. The backend team separately tests
request-time timezone, overnight, coverage and cache behaviour.
The [6 October backup rehearsal](verification/backup-rehearsal-2026-10-06.json)
uses synthetic local data; it verifies nine-table schema/data preservation and
both `iteration2_v2` email tables, not the excluded temporary `iteration1_v2`
notification table, full-production capacity or recovery time.

## Known limits and ongoing review

Record outstanding bugs with impact, workaround, owner and next review date in
the issue tracker linked by the handover package. Current technical limits include
uncollected regional/part-day holidays, unknown dynamic sign states, independent
dataset transactions, provider availability, and operator-managed account access.
Do not describe missing dates as confirmed non-holidays. Do not present historical
test counts or past table counts as evidence for the current release.

`crash-measure` is a destructive disposable-DB experiment. Its scoped crash plus
notification measurement excludes the report email dispatch table and does not
prove current production capacity. A maintenance review should consider all nine
tables, indexes and service storage limits using a read-only capacity check.

After a change or incident, record the outcome, lessons and any updated tests in
the issue/release record. Link these records from the Maintenance Document along
with architecture, code, outstanding work and the regression evidence. This
supports the handover's knowledge-transfer requirement without duplicating every
implementation detail in the sponsor-facing Product Document.
