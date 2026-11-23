# Pipeline support and recovery

This runbook is for the incoming operator. The release is `iteration3_v1`; the
database schema remains `iteration2_v2`. The owner has confirmed that production
has all nine tables. This document does not claim a fresh production permission,
row-count or application check. The pipeline prepares datasets; the backend owns
requests, caches and email delivery.

## Ownership and access

Complete this register before acceptance. These are deliberately unassigned,
not promises that a departing developer will continue operating the service.

| Item | Handover value |
| --- | --- |
| Primary operator and contact | Not assigned |
| Backup operator and contact | Not assigned |
| GitHub/Neon account and billing owner | Not assigned |
| Failure notification recipient and tested delivery date | Not assigned |
| Private release/backup storage location and access owner | Not assigned |
| Accepted maximum data loss and recovery time | Not agreed |
| Successor-led restore rehearsal and evidence | Not yet performed; developer synthetic rehearsal described below |

Transfer access through the organisation's secret manager and provider access
controls. Keep passwords, connection URLs and private backups out of Git,
public handover documents and screenshots. The writer, backend runtime role and
backup reader have different responsibilities; do not give the backend an owner
connection. Current runtime grants are documented in [backend handover](../backend_handover.md).

## What remains fixed and what changes

Keep the accepted source snapshots and prepared release. An unchanged snapshot
is not a failed update, but its date and limitations remain visible. A check of
the upstream source does not automatically authorise replacing production.

| When | Operator task |
| --- | --- |
| Before handover | Save the baseline, record its commit and identifiers, prepare the needed calendar coverage, test notifications, and rehearse recovery |
| Monthly, day 5 at 01:23 UTC | Scheduled independent preparations create speed, current-year calendar and next-year calendar candidates; review relevant changes before a separate manual publication |
| Weekly, Tuesday at 02:17 UTC | Scheduled read-only status and recent-run checks; the owner also checks Actions for a missing monitoring run |
| Monthly operator review | Check latest successful publication, calendar coverage through the supported period, application access, storage/billing, backup completion, and outstanding incidents |
| On a reported source correction or incident | Prepare a separate candidate, inspect evidence and changes, then publish that exact candidate if accepted |
| Before a calendar year is needed | Verify dates against official sources; review a corrected calendar with provenance if necessary; publish and check backend coverage handling |
| September monthly run, starting in 2027 | Also prepare the five completed crash years; review 2022–2026 first, replacing 2021–2025 only after successful manual publication |
| Before every publication or schema change | Confirm a restorable backup, reviewer, exact candidate ID and recovery path |

The crash baseline remains 2021–2025 until the annual replacement succeeds. A
source that lacks an expected year, changed unexpectedly, or failed validation
does not justify advancing the range. A missed September update remains due over
New Year: the September 2027 release can be completed through August 2028. From
September 2028 the next due release is 2028. Speed data also remains an explicitly dated
snapshot until its separately reviewed replacement is accepted. Refer to the
candidate commands in [deployment instructions](../deploy/README.md).

Schedules prepare or inspect data; they never publish it. They start only after
the workflow reaches the default branch and Actions is enabled. UTC schedules
shift their Melbourne local time with daylight saving and can be delayed by
GitHub. The weekly DB check requires the separate read-only secret documented in
the deployment guide; without it the result is `not_configured`, not healthy.

## Preserve the existing crash baseline

Before the first annual update, adopt the saved 2021–2025 outputs and their exact
original sources. This creates a recovery candidate without downloading data or
re-running clustering. `--frozen-input` contains `crash_event.csv`, `hotspot.csv`
and `crash_report.json`. `--frozen-source-dir` contains `crashes.csv`,
`accident-location.csv`, `crashes.source.json` and `accident-location.source.json`.
Use the accepted files; the tool checks their hashes and period.

```bash
python deploy/operations.py prepare --dataset crashes --commit FULL_GIT_COMMIT --frozen-input /secure/baseline/processed --frozen-source-dir /secure/baseline/raw --output /secure/candidates/crashes-baseline-2021-2025
python deploy/operations.py validate --input /secure/candidates/crashes-baseline-2021-2025 --candidate-id CANDIDATE_ID --commit FULL_GIT_COMMIT
```

Do not supply `--release-year` for adoption. Archive the whole candidate privately,
its reviewed identity and a database backup before the annual change. An adopted
baseline is for recovery: normal publication rejects it unless explicit `--restore`
is used with resolved publication history. As a pre-workflow baseline it does not
need an earlier successful annual-publication record; that exception does not
bypass an unresolved intent. Adoption neither changes production nor establishes that newer sources
match the saved baseline. If any original inputs are unavailable, record that gap
and preserve a database backup; do not recreate provenance by downloading again.
The original baseline's Accident Location metadata predates the current format
and does not establish `reporting_period_end`. Adoption may therefore report
`LEGACY_BASELINE_LOCATION_PERIOD_UNCONFIRMED`. Retain those original bytes and
have the operator review the warning; do not invent a reporting period to silence
it. This exception is limited to the existing 2021–2025 recovery baseline.
New annual inputs still require confirmed source periods. Successful offline
adoption does not verify that the saved baseline is the dataset currently in production.
This local adoption is separate from the workflow's trusted preparation-artifact
path, which does not accept arbitrary local artifact uploads.

## New operator or new environment

Use a clean checkout of the accepted commit, Python matching the tested release,
and its pinned `requirements.lock`. The working directory for these commands is
`data-pipeline/iteration3_v1`.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.lock
export PYTHONPATH=src:tests
python -m iteration3_v1_pipeline --help
```

Provide `DATABASE_URL` securely for the selected database; `.env` is not loaded
automatically. A fresh database also requires PostgreSQL/PostGIS and appropriate
runtime-role grants. Normal migration applies SQL 001–005 in a transaction;
running it does not load data, copy email history or deploy backend code. Avoid
re-running setup merely because an ordinary dataset refresh is needed.

The pipeline has no permanent daemon to start or stop. Start a chosen command or
manual workflow. To pause changes, stop dispatching publications. Before stopping
a running publication, inspect its result: each run publishes one selected domain
in its transaction, and another run's committed update remains in place. Pause
backend email writes separately during history
recovery. Never clear either email-history table to resolve a pipeline issue.

## Review, publish and verify

1. Prepare a new candidate in a new directory; preserve its raw inputs, prepared
   data, policy, commit and manifest together.
2. Validate it, inspect source dates, range, row counts, exclusions, calendar
   corrections and changes from the accepted release. Record the reviewer and
   reason. A calendar correction needs the original snapshot, corrected CSV,
   hashes, official evidence and the review record; editing a CSV alone is not
   an accepted correction.
3. Publish the exact accepted candidate ID through the documented publication
   command. Do not download fresh inputs between review and publication.
4. Inspect the outcome and read-only status; verify application behaviour with
   the backend owner. Record the candidate ID, operator, time and evidence in the
   release register. Retain the previous good candidate.

Use the manually dispatched GitHub publication workflow for routine production
changes. It verifies the preparation source, archives the candidate, checks past
publication history and retains a verified **draft intent before any DB write**.
Its two release types serve different purposes:

| Record | Meaning |
| --- | --- |
| `pipeline-candidate-<candidate_id>` release | Exact source/payload archive, checksums and preparation provenance; it does not prove a successful DB update |
| `pipeline-publication-<run_id>-<attempt>` release | Begins as a draft with `intent.json`; retains the result and any reconciliation, and is finalized only when the outcome is resolved |

Keep every publication intent/evidence release, including drafts and past successful annual crash
updates after a restore. The annual guard reads this durable history as well as
the current DB build, so restoring 2021–2025 does not permit a different second
2022–2026 release. An unresolved draft blocks further workflow publication and
recovery through the history check, even if the runner stopped before writing a
result. Do not delete or manually finalize it to unblock a run. Candidate archives and publication records are independent of
the 90-day requested Actions artifact retention. Repository owners can still
delete releases; retain an independent copy in the handover storage.

The following commands use the accepted commit's full 40-character SHA and the
64-character candidate ID printed by preparation. Replace the uppercase tokens
and paths with the reviewed values. These local commands are suitable for an
operator-controlled rehearsal; use a disposable DB for the publication example.
They do not create the GitHub archive, pre-write intent or finalized evidence
automatically. Use the workflow for routine production publication and restoration.
A new speed candidate is prepared only when a refresh is intended:

```bash
python deploy/operations.py prepare --dataset speed --commit FULL_GIT_COMMIT --as-of YYYY-MM-DD --output /secure/candidates/speed-YYYYMMDD
python deploy/operations.py validate --input /secure/candidates/speed-YYYYMMDD --candidate-id CANDIDATE_ID --commit FULL_GIT_COMMIT
python deploy/operations.py publish --input /secure/candidates/speed-YYYYMMDD --candidate-id CANDIDATE_ID --reviewed-by OPERATOR_NAME --reason "Reviewed source refresh" --report /secure/records/speed-publication-YYYYMMDD.json
python deploy/operations.py status --report /secure/records/status-YYYYMMDD.json
```

Preparation and validation need no DB connection. Before publication, archive the
exact candidate directory in the private durable storage, then supply the writer
connection securely. Use a read-only connection for the separate status command;
it returns an attention/failure exit code when operator review is needed. Retain
the local publication report outside the candidate. A production recovery using
advanced local commands requires an explicitly reviewed durable intent/evidence
procedure; a terminal success message alone does not enter the workflow history.

For the planned September 2027 update, prepare crashes with `--dataset crashes
--release-year 2027 --commit FULL_GIT_COMMIT --output NEW_DIRECTORY`, then use the
same validate/review process and publish through the workflow. The policy rejects
an update before its release window and selects the latest due September release,
so a delayed 2027 update remains possible in January–August 2028. Local crash publication and restore additionally
requires `--publication-history HISTORY_FILE`, fetched from all retained GitHub
publication releases by the authenticated history helper:

```bash
GITHUB_REPOSITORY=OWNER/REPOSITORY python deploy/workflow_support.py history --output /secure/records/publication-history.json
```

Use that file, not a fabricated empty list. The helper refuses unresolved intents
instead of returning incomplete history. Prefer the workflow so history
acquisition, pre-write intent and per-run outcome retention remain part of the
same process. Resolve uncertain outcomes using the procedure below before another
update or restore.

For a reviewed calendar, prepare with `--dataset calendar --year
2027 --calendar-review-dir REVIEW_DIRECTORY --commit FULL_GIT_COMMIT --output
NEW_DIRECTORY`. The review directory contains `original/`, `corrected.csv` and
`review.json` according to the
[exact review fields and validation rules](../backend_handover.md#reviewed-corrections-and-bounded-calendar-coverage).

Bounded calendar coverage is blocked by default. The backend must first implement
the documented date-bound checks before an operator uses the explicit bounded
coverage opt-in. Having the nine tables does not prove that the backend has this
behaviour. Missing coverage must remain unknown, not become a normal school day.

Roll out bounded coverage in this order:

1. Prepare and review the candidate without publishing it.
2. Deploy compatible handling to **every consuming backend** and verify supported
   policy/status, inclusive bounds, an outside-coverage date, an overnight/year
   boundary and cache refresh. An existing year row alone must never imply coverage.
3. Only then publish with workflow `allow_bounded_calendar=true` or local
   `--allow-bounded-calendar`, retaining the evidence for that operator assertion.
4. Check the published manifest and application results. Keep compatible backend
   behaviour while bounded data is present. Before rolling the backend back to a
   year-only implementation, restore compatible full-year data or remove that
   unsupported use path through an agreed recovery change.

The repository does not deploy or verify the backend adaptation. The existing
nine-table shape is sufficient; the prerequisite is consuming behaviour.

To restore an earlier reference-data candidate, validate its immutable archive,
inspect the current version and backup, then use `publish` with `--restore`, its
exact candidate ID, a named reviewer and a recovery reason. For workflow recovery,
choose `source=archive`, `restore=true` and the retained candidate ID; inspect the
prior publication evidence first. Keep the outcome in a new publication release,
not by altering the finalized candidate archive. This replaces only that
candidate's dataset; it does not restore
application email history or perform a whole-database recovery.

Crash restoration requires resolved history even when restoring the old baseline.
An annual candidate must have a prior successful record for that **exact candidate
ID and period**; merely having its archive is insufficient. The adopted 2021–2025
baseline is the narrow pre-workflow exception to the prior-success requirement.
All unresolved intents must still be reconciled before using it.

## Reconcile an interrupted or uncertain publication

The workflow finalizes a complete result, or a failure known to occur before the
write (`validate` or `preflight`). A missing result, failure in `publish`/`complete`,
or interrupted evidence upload leaves the intent unresolved. This is deliberate:
a failed process can have committed before losing its result. It must not be
treated as rollback automatically.

1. Stop retries and restores. Keep the draft intent, available result artifact and
   candidate archive. Identify the exact run, attempt and candidate.
2. Have the database owner inspect the actual DB **read-only** and compare it with
   that candidate. For crashes check build ID and period; for speed/calendar use
   the complete stored data and metadata/fingerprint, not row counts alone. Retain
   the observations and relevant run evidence without credentials or private data.
3. Decide `committed` or `not_committed` only when evidence establishes it. A later
   restored DB does not prove that the earlier write never committed. A recorded
   success cannot be reclassified as not committed. If the outcome is still
   uncertain, keep the draft and investigate with the database owner.
4. Save a reviewed evidence JSON and run the authenticated reconciliation command:

```json
{
  "candidate_id": "EXACT_CANDIDATE_ID",
  "outcome": "committed",
  "database_verified": true,
  "checked_at": "ACTUAL_TIMESTAMP_WITH_TIMEZONE",
  "reviewed_by": "ACTUAL_OPERATOR_NAME",
  "observations": "Describe the read-only checks and evidence establishing this outcome."
}
```

These are placeholders, not an attestation to copy unchanged. For
`not_committed`, the evidence must also contain `"never_committed": true`, supported
by checks showing the write never committed rather than only today's DB contents.

```bash
GITHUB_REPOSITORY=OWNER/REPOSITORY python deploy/workflow_support.py reconcile --run-id RUN_ID --attempt ATTEMPT_NUMBER --candidate-id CANDIDATE_ID --outcome committed --reviewed-by ACTUAL_OPERATOR_NAME --reason "Verified interrupted publication outcome" --evidence /secure/records/reconciliation-evidence.json
GITHUB_REPOSITORY=OWNER/REPOSITORY python deploy/workflow_support.py history --output /secure/records/publication-history.json
```

Use matching outcome and reviewer values in the command and evidence. This tool
records the review; it **does not perform the DB verification itself**. It retains
the intent, original result when present and `reconciliation.json`, then finalizes
the effective outcome. Confirm that history is clear before starting another
publication. Never erase successful annual history to permit another replacement
for the same release period.

## Backups and restore rehearsal

The backup scope is **only `iteration2_v2` and its nine tables**. Its seven
reference-data tables can be reconstructed from accepted artifacts; its
`notification_event` and `report_email_dispatch` tables contain application state
that cannot be recreated by downloading open data. Back up these nine tables
together; retain provider backups and the independent handover copy according to
the agreed recovery objectives.

The owner treats `iteration1_v2.notification_event` as temporary storage and
intentionally excludes it. This backup does not preserve or restore that table,
does not imply that all active backend notification state is included, and does
not require moving the backend notification writer to `iteration2_v2`.

`deploy/backup.py` creates a PostgreSQL custom-format archive plus a SHA-256
manifest. Install compatible `pg_dump`, `pg_restore` and `psql` clients first.
It reads the source through the securely supplied `DATABASE_URL`; credentials
are passed to PostgreSQL clients through their environment, not command arguments.
It records only `iteration2_v2` schema/data, including that schema's email tables,
and excludes the temporary `iteration1_v2` notification table, roles, passwords and
grants. Only restore archives from the trusted handover/backup location.

```bash
python deploy/backup.py backup --output /secure/backups/iteration3-v1-YYYYMMDD --commit FULL_GIT_COMMIT
python deploy/backup.py verify --input /secure/backups/iteration3-v1-YYYYMMDD
```

Use a new output directory for every backup. The helper checks all nine tables,
archive contents and hashes; a failed attempt does not replace a completed backup.
Store copies in access-controlled storage independent of the runner/laptop. Keep
the handover baseline throughout the support period, at least the previous good
release, and the database backups required by the agreed recovery objectives.
Actions' short-lived artifacts alone are not this storage policy. Agree backup
frequency and retention with the service owner before sign-off; this helper does
not install a schedule or confirm that provider backups are enabled.

For rehearsal, provision an empty **disposable** PostgreSQL database whose name
starts with `iteration2_test`, then supply that database's `DATABASE_URL` securely:

```bash
python deploy/backup.py restore --input /secure/backups/iteration3-v1-YYYYMMDD --confirm-database iteration2_test_restore
```

The helper checks the connected server's database name before changing anything,
creates PostGIS if needed, and restores in one transaction. It refuses production
names or a mismatch with the confirmation. Rehearsal replaces objects in that
test schema. After restore, verify all nine `iteration2_v2` tables, both email
tables in that schema, calendar coverage, dataset build IDs and representative
application reads; apply test
runtime grants separately. Record the elapsed time and evidence.

For production recovery, the database owner follows the provider-approved
recovery procedure after pausing writers, verifies the target and restoration
point, reapplies needed grants and tests the backend before resuming traffic.
This test-only restore command deliberately cannot perform that cutover. Preserve
the included `iteration2_v2` delivery keys/statuses. Recovery of the intentionally
excluded temporary `iteration1_v2.notification_event` is outside this runbook.

Developer validation on 6 October 2026 restored a custom archive between two
separate local Docker test databases. All nine tables, their columns, constraints,
triggers and seeded rows matched, including three notification states and all
four report dispatch states. This used synthetic data and does not establish
full-production restore duration, provider backup availability or successor access.
The sanitized [rehearsal result](verification/backup-rehearsal-2026-10-06.json)
records the test scope, table counts and comparisons.

## Troubleshooting

| Symptom | First action and recovery |
| --- | --- |
| Missing/invalid calendar or rejected correction | Read validation details; compare original and reviewed evidence; retain existing coverage and notify the owner |
| Source unavailable or changed columns | Retain the accepted snapshot; retry acquisition later or raise a maintenance issue with the source identity and error code |
| Unexpected record loss or crash range rejected | Inspect annual completeness and exclusion counts; do not publish to satisfy a target date |
| Legacy baseline location-period warning | Review the preserved old metadata and recovery scope; do not fill in unverified dates or apply this exception to annual inputs |
| Calendar version changed since review | Run the preview again, examine the new current version, and obtain a fresh review |
| A publication fails after an earlier run succeeded | Inspect both run outcomes; preserve the earlier committed dataset and identify actual versions before retrying |
| Publication intent remains draft or DB result is uncertain | Stop retries/restores, verify the exact candidate against DB read-only, and use the reviewed reconciliation procedure; never delete the intent |
| Annual update says history missing/already published | Fetch and review retained publication records; replay the exact accepted candidate or use reviewed recovery rather than manufacturing a fresh cohort |
| Connection/permission failure | Check selected environment, role and secret access with the owner; never paste connection URLs into an issue |
| Backup fails | Check client/server compatibility, read permissions and storage space; keep the previous verified backup and report the failure |
| Old dates or emails after a restore | Check restored build/coverage and application cache; inspect the restored `iteration2_v2` email tables with the backend owner before resuming delivery |

Send actionable failures to the named operator and backup operator; confirm their
existing GitHub/provider notifications work with a test event. Monthly reviews
also detect missed work that generates no failure event. Never use `crash-measure`
as a production health check: it reloads crash data in a disposable database.

## Handover acceptance

An incoming operator should be able to verify a frozen candidate and rehearse
restore using this document without the original developer operating the keyboard.
Record training participants, topics and time; capture redacted result evidence.
Package the Product, Support and Maintenance documents with the repository and
private artifact locations in the Project Governance Portfolio, and use the
project-adapted mentor acceptance/sign-off form. Assignment dates and submission
details remain subject to current Moodle guidance. Technical change instructions
are in [maintenance](maintenance.md).
