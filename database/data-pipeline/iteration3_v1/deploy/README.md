# Prepare, review and publish pipeline data

The [GitHub workflow](../../../.github/workflows/iteration3-v1-neon.yml) creates
separate speed, calendar and crash candidates. A person reviews a candidate and
starts a separate publication run using its exact identity. Scheduled runs never
publish to Neon. The existing production schema is `iteration2_v2` with nine
tables; publication does not recreate the schema or apply migrations.

| Event | Automatic work | Production writes |
| --- | --- | --- |
| Pull request changing this pipeline/workflow | Unit and disposable PostGIS tests | None |
| Merge or push to `main` | No data publication | None |
| Monthly, day 5 at 01:23 UTC | Independently prepare speed, current-year calendar and next-year calendar | None |
| September monthly run, starting in 2027 | Also prepare the annual crash candidate | None |
| Weekly, Tuesday at 02:17 UTC | Read-only DB status, if configured, plus recent workflow runs | None |
| Manual `operation=prepare` | Test and prepare the selected dataset | None |
| Manual `operation=publish` on `main` | Test current code, verify and archive the selected candidate, then publish it | Selected dataset only |
| Manual `operation=status` on `main` | Same read-only status check | None |

The monthly scheduler dispatches a separate preparation run for each dataset/year.
A rejected next-year calendar therefore does not turn a successful speed run into
a failed candidate source. Each candidate run reports its own success or failure.
Schedules start only after this workflow is merged into the default branch and
Actions is enabled. GitHub may delay scheduled jobs; the maintainer checks the
weekly summary and reruns missed preparations manually. UTC scheduling means the
local Melbourne time changes with daylight saving.

## Setup and ownership

Use the existing **`neon-production`** environment and its
**`NEON_DIRECT_DATABASE_URL`** secret for publication. Only the publication step
receives that connection. Preserve the writer concurrency group
`iteration2-v2-neon-production`, which also coordinates with older compatible
workflows. Only trusted `main` runs can prepare publishable candidates or publish.

Add a separate repository Actions secret **`NEON_READ_ONLY_DATABASE_URL`** if
weekly DB monitoring is wanted. It must use a role that can inspect the nine
pipeline tables but cannot change them. The status job does not use the
production writer secret or production environment. If this optional secret is
absent, the status report says `not_configured` and the run requests attention;
it does not report a healthy database.

The scheduler needs `actions: write` to dispatch preparation runs. The publication
job needs `actions: read` to validate/download a selected artifact and
`contents: write` to retain release archives. Repository/organisation policies
must allow these workflow permissions. No settings or secrets are provisioned
by the workflow. Environment reviewer requirements are a separate GitHub setting;
merely naming an environment does not configure an approval gate.

Assign a primary and backup maintainer, subscribe to failed Actions runs, and
record access and recovery contacts in the [operations runbook](../docs/operations.md).
The weekly summary includes recent runs for missed-monthly checks. It cannot
detect its own failure to run, so the owner also checks the Actions page weekly.

## Prepare a candidate

Open **Actions → Iteration 3 v1 - Neon data deployment → Run workflow**, select
`main`, choose `operation=prepare` and select one dataset. Equivalently, from the
repository root:

```bash
gh workflow run iteration3-v1-neon.yml --ref main -f operation=prepare -f dataset=speed
gh run list --workflow iteration3-v1-neon.yml --limit 10
```

| Dataset | Inputs and policy |
| --- | --- |
| `speed` | Optional `as_of=YYYY-MM-DD`; otherwise today's Melbourne date. Roads and signs form one candidate and publish together. |
| `calendar` | `year`, defaulting to the current Melbourne year. Optional `calendar_review_path` for committed manual corrections. Each year is prepared independently. |
| `crashes` | Explicit `release_year`. September 2027 prepares 2022–2026; September 2028 prepares 2023–2027. Current 2021–2025 remains active until an approved annual replacement succeeds. |

Crash candidates are not prepared by ordinary speed/calendar runs. The annual
five-year cohort is rebuilt as a whole. The publication policy checks the latest September release that is due
(including catch-up through the following August), source completeness and the
already-published cohort; it does not
change the active period solely because the date crossed September. Before publication,
the workflow also reads successful crash publication evidence across every page
of the retained releases. The annual guard rejects a different candidate for an
already-published period, including after a rollback to the old cohort. Retain the
original 2021–2025 snapshot and a database backup before the first annual update.
The local frozen-input adoption procedure in the operations runbook preserves
that baseline without downloading newer crash sources.

A successful preparation summary contains:

- the preparation run ID and exact artifact ID;
- a 64-character `candidate_id`, producer commit, source files and hashes;
- dataset period, counts, quality checks and comparison information when supplied;
- the full manifest and sanitized preparation outcome.

The candidate includes original source evidence, built payloads and producer
configuration. The candidate artifact has a 90-day requested retention period.
Read its contents and the source evidence before publication. A failed build
retains a failure report; it does not create a publishable candidate.

## Manually reviewed calendar data

Manual corrections are accepted through the documented review format:
`original/`, `corrected.csv`, and `review.json`. Keep the original source intact.
The review records what changed, why, authoritative evidence, reviewer and date.
Follow the calendar review instructions in the
[operations runbook](../docs/operations.md) for exact fields and validation.

Commit the small reviewed calendar directory to this repository through a PR.
After it reaches `main`, set `calendar_review_path` to that directory's path
relative to the repository root. The workflow verifies that the directory stays
inside the checkout and its files are tracked, then passes it to the calendar
review builder. This does not support uploading arbitrary local artifacts for
production publication. Never commit DB credentials or private configuration.

A source duplicate such as New Year's Day can be corrected with evidence.
An unannounced holiday must not be invented. A bounded calendar candidate declares
an explicit coverage end and unresolved events. Its publication additionally
requires `allow_bounded_calendar=true` and a backend that enforces those coverage
dates. The ordinary full-year path remains available when the complete year is
verified. Other calendar years and both `iteration2_v2` email tables are preserved.

## Publish the exact reviewed candidate

Run the workflow again with:

| Input | Required value |
| --- | --- |
| `operation` | `publish` |
| `source` | `artifact` |
| `candidate_run_id` | The successful preparation run ID |
| `candidate_artifact_id` | The artifact ID shown in that run |
| `candidate_id` | The exact 64-character candidate ID you reviewed |
| `reason` | Your review/approval note |
| `allow_bounded_calendar` | Leave false unless bounded coverage and backend handling were reviewed |
| `restore` | `false` |

The selected `dataset` input is used only for preparation. Publication reads the
dataset from the verified candidate manifest. The reviewer is recorded from the
GitHub actor; the candidate is never rebuilt or re-downloaded from its live source.

Before providing DB credentials, the job checks that the artifact belongs to a
successful run of this workflow, from `main` in this repository, triggered by a
trusted preparation path. It verifies artifact identity, candidate identity,
producer commit and all candidate files using the current trusted publication
code. Candidate files are treated as data, never as executable code.

It then creates a release named `pipeline-candidate-<candidate_id>` containing a
deterministic archive, chunks smaller than 2 GiB, `SHA256SUMS`, and source-run
provenance. Existing assets are verified instead of overwritten. An incomplete
archive stops publication. Release assets do not expire with Actions artifacts,
but repository owners can delete them; keep the independent backup required by
the runbook.

After archival and history verification, the job saves `intent.json` in a new
draft publication release and verifies it. Only then does `operations.py publish`
receive the writer connection. The selected domain is replaced in its existing transaction. Speed
updates preserve crash/calendar/email state; crash updates preserve
speed/calendar/email state; calendar updates preserve other years and domains.
No scheduled operation sends application emails. The two backend-owned tables
`iteration2_v2.notification_event` and `iteration2_v2.report_email_dispatch` are
never source refresh inputs. Publication checks that the existing nine-table schema is available.

The intent and result are retained as `intent.json` and `publication.json` in a
separate release named `pipeline-publication-<run_id>-<attempt>`, linking the
candidate archive. A confirmed result finalizes this release. This keeps
finalized candidate releases compatible with GitHub immutable releases.
The result records the actual reviewer, reason, source commit and outcome.
**An archived candidate is not proof that it was successfully published.** Read
the publication evidence. A successful DB update followed by failed evidence
upload leaves an unresolved draft. History checks reject every unresolved
publication intent, including interrupted restores, so another publication cannot
proceed until the outcome is reconciled. Preserve all publication releases: the
annual guard uses them beyond the currently active DB rows.

## Restore a retained candidate

Identify a previously successful candidate from its publication evidence. Annual
crash restoration requires an exact candidate ID and period in successful
publication history. The narrow exception is a locally adopted original
2021–2025 baseline, which predates this workflow and still requires complete,
unresolved-free history. Resolve all pending intents first. Run
`operation=publish`, `source=archive`, `candidate_id=<that identity>`,
`restore=true`, and a recovery reason. Artifact/run IDs are not needed for this
path. Bounded calendar restores still require explicit bounded-coverage approval.

The workflow downloads the retained archive, verifies checksums and source
provenance, rejects traversal/links and oversized extraction, and revalidates the
candidate using current trusted code. It never downloads live source data.
Restoration affects the candidate's dataset only. Restoring the nine-table
`iteration2_v2` schema, including its notification and dispatch tables, uses the
[backup/restore runbook](../docs/operations.md), not a dataset candidate. The owner
intentionally excludes the temporary `iteration1_v2.notification_event` from
that backup; this is not a whole-database archive.

## Reconcile an interrupted publication

A draft `pipeline-publication-<run_id>-<attempt>` is a retained pending operation,
not disposable clutter. Do not delete it or force-publish it to bypass the guard.
Confirmed failures before the write (`validate` or `preflight`) finalize as failed.
An error during or after the write stays unresolved because a lost connection can
hide a successful commit. Ordinary future publication and restoration both stop.

1. Download the exact candidate archive and the original run's result artifact.
   Inspect `intent.json`, `publication.json` if present, and the failed step.
2. Use a read-only DB connection to determine whether that candidate committed.
   For crashes compare `crash_build.build_id`, its period and row counts with the
   candidate, plus the original run evidence. For speed/calendar compare the
   complete affected payload or a verified data fingerprint. Counts alone are
   insufficient. Consult backups/history if another operator later changed data.
3. Save your findings in an evidence JSON. `not_committed` means the attempted
   update **never committed**, not that it is currently absent after a later
   restore. If the outcome cannot be established, leave the intent unresolved.
4. Record the reviewed decision with the reconciliation command. It writes only
   GitHub evidence; it neither queries nor changes Neon.

The evidence file must contain real values for this exact intent:

```json
{
  "candidate_id": "THE_EXACT_64_CHARACTER_CANDIDATE_ID",
  "outcome": "committed",
  "database_verified": false,
  "checked_at": "YYYY-MM-DDTHH:MM:SS+HH:MM",
  "reviewed_by": "maintainer-name",
  "observations": ""
}
```

Replace the placeholders, record the read-only comparison in `observations`,
and set `database_verified` to true only after checking. For a verified
`not_committed` decision, use that outcome and add `"never_committed": true`.
Use the same reviewer/outcome in the command. Replace the capitalised values:

```bash
GITHUB_REPOSITORY=FIT5120-2026S2-TP10/sdg-platform python deploy/workflow_support.py reconcile \
  --run-id RUN_ID --attempt ATTEMPT --candidate-id CANDIDATE_ID \
  --outcome committed --reviewed-by maintainer-name \
  --reason "Verified the original publication outcome against the database" \
  --evidence /absolute/path/to/evidence.json
```

Run this from `data-pipeline/iteration3_v1` with authenticated `gh` access.
Reconciliation preserves the intent and any original result, adds
`reconciliation.json`, and finalizes the evidence release. An acknowledged
successful publication cannot be reclassified as never committed. A failed first
intent-asset upload can be reconciled from the same intent retained atomically in
the release body. Recheck history after reconciliation before starting another
publication; do not manufacture an empty history file.

## Failures and verification limits

Preparation, validation or archival failure leaves the production database
untouched. A selected domain's load failure rolls back that domain's transaction.
The old all-domain script `github_actions.py` remains a historical interface; the
active workflow calls `operations.py` and does not run its all-domain deployment.

Inspect the current run summary and failure report first. Fix the source or
configuration, prepare a new candidate when its content changes, review it, and
publish that new identity. Do not edit a published candidate in place. Restore
an archived known-good candidate if a completed publication needs reversing.

See [maintenance responsibilities](../docs/maintenance.md) for annual policy,
quality gates, capacity, dependencies and handover acceptance checks. Local tests
and workflow linting verify behavior without deploying to Neon; the first actual
GitHub preparation, archive and publication run still needs operational review.

GitHub references: [manual and scheduled triggers](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow),
[artifact provenance API](https://docs.github.com/en/rest/actions/artifacts), and
[environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).
