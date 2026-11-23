"""Verify the GitHub/transport boundary without network or database access.

Synthetic REST documents exercise source-run trust checks. Tiny local files
exercise deterministic archival, checksums and safe extraction. Fixed candidate
IDs intentionally isolate those transport contracts; test_operations covers the
candidate manifest's cryptographic identity and database publication rules.

The fake release store preserves draft/final state and asset bytes, rejects
writes to finalized releases, and can fail an upload or finalization. This lets
the suite reproduce uncertain outcomes without sending emails, dispatching real
workflows, modifying GitHub releases or connecting to Neon. Real GitHub API and
permission behavior still requires the separately reviewed deployment trial.
"""

from copy import deepcopy
from datetime import date
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from deploy import workflow_support as workflow

COMMIT = "a" * 40
CANDIDATE = "b" * 64
REPO = "team/pipeline"
PROVENANCE = {"schema_version": 1, "repository": REPO, "workflow": workflow.WORKFLOW_PATH,
              "run_id": 100, "run_attempt": 1, "artifact_id": 200, "commit": COMMIT}


class WorkflowSchedulingTests(unittest.TestCase):
    """Check monthly/annual request selection separately from actual workflow execution."""
    def test_monthly_candidates_are_independent_and_annual_crashes_begin_in_september_2027(self):
        """Check the first allowed annual cycle and exclude monthly crash refreshes."""
        for day in (date(2026, 9, 5), date(2027, 8, 5), date(2027, 10, 5)):
            jobs = workflow.scheduled_candidates(day)
            self.assertEqual([item["dataset"] for item in jobs], ["speed", "calendar", "calendar"])
            self.assertEqual([item["year"] for item in jobs[1:]], [str(day.year), str(day.year + 1)])
        self.assertEqual(workflow.scheduled_candidates(date(2027, 9, 5))[-1],
                         {"dataset": "crashes", "release_year": "2027"})
        self.assertEqual(workflow.scheduled_candidates(date(2028, 9, 5))[-1]["release_year"], "2028")

    def test_scheduler_dispatches_prepare_only_with_no_database_or_publication_parameters(self):
        """A schedule may queue preparation only, even when the annual job is due."""
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": REPO}), patch.object(workflow, "gh") as gh:
            workflow.dispatch_candidates(date(2027, 9, 5))
        self.assertEqual(gh.call_count, 4)
        for call in gh.call_args_list:
            arguments = call.args
            self.assertIn("operation=prepare", arguments)
            self.assertEqual(arguments[arguments.index("--ref") + 1], "main")
            self.assertNotIn("operation=publish", arguments)


class WorkflowProvenanceTests(unittest.TestCase):
    """Reject artifacts outside the successful trusted-main preparation boundary."""
    def setUp(self):
        """Build one accepted synthetic API response pair; individual cases alter one trust condition."""
        self.run = {"id": 100, "workflow_id": 10, "status": "completed", "conclusion": "success",
                    "head_branch": "main", "event": "workflow_dispatch", "head_sha": COMMIT,
                    "head_repository": {"full_name": REPO}, "run_attempt": 1}
        self.artifact = {"id": 200, "name": "iteration3-v1-candidate-100-1", "expired": False,
                         "workflow_run": {"id": 100, "head_sha": COMMIT, "head_branch": "main",
                                          "repository_id": 5, "head_repository_id": 5}}

    def verify(self, run=None, artifact=None):
        """Apply the real verifier to the supplied documents using fixed expected identities."""
        return workflow.verify_source_documents(run or self.run, artifact or self.artifact, 10, REPO, 100, 200)

    def test_only_successful_main_candidate_artifacts_are_trusted(self):
        """Alter each trust condition independently so a passing identity cannot mask another failure."""
        self.assertEqual(self.verify(), PROVENANCE)
        changes = [{"conclusion": "failure"}, {"status": "in_progress"}, {"head_branch": "feature"},
                   {"event": "pull_request"}, {"workflow_id": 11}, {"id": 101},
                   {"head_repository": {"full_name": "other/fork"}}, {"head_sha": "bad"}]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(run={**self.run, **change})
        for change in ({"id": 201}, {"expired": True}, {"name": "iteration3-v1-publication-result-100-1"},
                       {"name": "iteration3-v1-candidate-100-2"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(artifact={**self.artifact, **change})
        for change in ({"id": 101}, {"head_sha": "c" * 40}, {"head_branch": "feature"},
                       {"head_repository_id": 6}, {"repository_id": None, "head_repository_id": None}):
            altered = deepcopy(self.artifact)
            altered["workflow_run"].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.verify(artifact=altered)


class FakeReleaseStore:
    """Model only the release operations needed by these tests, entirely in memory.

    Asset payloads and API digests are retained independently from release metadata.
    Drafts accept new assets; finalized releases reject uploads to model immutable
    release behavior. Failure switches simulate interruptions at durability boundaries.
    """
    def __init__(self):
        """Start with no releases/documents and disabled synthetic failure injection."""
        self.releases = {}
        self.documents = {}
        self.api_calls = []
        self.fail_upload = None
        self.fail_finalize = False

    def release(self, tag):
        """Return an isolated API-like snapshot; production helpers cannot mutate fixture state by reference."""
        return deepcopy(self.releases.get(tag))

    def put(self, tag, name, document):
        """Store synthetic JSON bytes and matching asset metadata, allowing deliberate corruption fixtures."""
        raw = (json.dumps(document, indent=2) + "\n").encode()
        self.documents[tag, name] = raw
        assets = self.releases[tag]["assets"]
        assets[:] = [asset for asset in assets if asset["name"] != name]
        import hashlib
        assets.append({"name": name, "size": len(raw), "digest": "sha256:" + hashlib.sha256(raw).hexdigest()})

    def api(self, path):
        """Return one 100-item release page and record which pages the real history scanner requests."""
        self.api_calls.append(path)
        page = int(path.rsplit("page=", 1)[1])
        return deepcopy(list(self.releases.values())[(page-1)*100:page*100])

    def gh(self, *args):
        """Emulate fixed release subcommands without spawning a process or accessing GitHub.

        Mutate fixture state for create/upload/edit, or write downloaded fixture bytes
        into the caller's temporary directory. Raise for unsupported operations,
        configured failures, duplicate creation or uploads to finalized releases.
        """
        tag = args[2]
        operation = args[:2]
        if operation == ("release", "create"):
            if tag in self.releases:
                raise RuntimeError("Release already exists")
            self.releases[tag] = {"tag_name": tag, "draft": True, "assets": [],
                                  "target_commitish": args[args.index("--target") + 1],
                                  "body": Path(args[args.index("--notes-file") + 1]).read_text()}
        elif operation == ("release", "upload"):
            path = Path(args[3])
            if path.name == self.fail_upload:
                raise RuntimeError("Synthetic failed asset upload")
            if not self.releases[tag]["draft"]:
                raise RuntimeError("Finalized releases are immutable in this fixture")
            self.put(tag, path.name, json.loads(path.read_text()))
        elif operation == ("release", "download"):
            name = args[args.index("--pattern") + 1]
            destination = Path(args[args.index("--dir") + 1]) / name
            destination.write_bytes(self.documents[tag, name])
        elif operation == ("release", "edit"):
            if self.fail_finalize:
                raise RuntimeError("Synthetic failed release finalization")
            self.releases[tag]["draft"] = False
        else:
            raise AssertionError(f"Unexpected operation {operation}")
        return ""


class PublicationHistoryTests(unittest.TestCase):
    """Exercise intent, outcome and reconciliation as an externally durable state machine."""
    def setUp(self):
        """Install an in-memory GitHub store and an already-finalized candidate archive.

        Only the transport metadata needed by the journal is included. Environment
        variables and API/CLI calls are patched for the whole test and restored by
        unittest cleanup, so no real repository or credentials are used.
        """
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.candidate = self.root / "candidate"
        self.candidate.mkdir()
        self.manifest = {"candidate_id": CANDIDATE, "commit": COMMIT, "dataset": "crashes", "release_year": 2027,
                         "period": {"start": "2022-01-01", "end": "2026-12-31", "build_id": "c" * 64}}
        (self.candidate / "candidate_manifest.json").write_text(json.dumps(self.manifest))
        self.store = FakeReleaseStore()
        self.candidate_tag = workflow.ARCHIVE_PREFIX + CANDIDATE
        self.store.releases[self.candidate_tag] = {"tag_name": self.candidate_tag, "draft": False,
                                                   "immutable": True, "assets": [], "target_commitish": COMMIT}
        for patcher in (patch.dict(os.environ, {"GITHUB_REPOSITORY": REPO, "GITHUB_RUN_ID": "100",
                                                "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": COMMIT}),
                        patch.object(workflow, "_release", side_effect=self.store.release),
                        patch.object(workflow, "api", side_effect=self.store.api),
                        patch.object(workflow, "gh", side_effect=self.store.gh)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def begin(self, run=100, restore=False):
        """Record a real helper-generated intent for a synthetic run, optionally marking a restore."""
        os.environ["GITHUB_RUN_ID"] = str(run)
        return workflow.begin_publication(self.candidate, CANDIDATE, "operator", "Reviewed exact candidate", restore=restore)

    def finish(self, run=100, *, status="complete", stage="complete", restore=False):
        """Feed the journal a synthetic operations result at a chosen failure boundary.

        A complete fixture contains acknowledgement evidence; a failed fixture can
        represent a safe preflight rejection or an uncertain write-stage failure.
        """
        os.environ["GITHUB_RUN_ID"] = str(run)
        report = self.root / f"result-{run}.json"
        value = {"candidate_id": CANDIDATE, "status": status, "stage": stage, "dataset": "crashes",
                 "period": self.manifest["period"], "release_year": 2027, "commit": COMMIT, "restore": restore}
        if status == "complete":
            value["published_at"] = "2027-09-05T03:00:00+00:00"
            value["outcome"] = {"build_id": "c" * 64}
        report.write_text(json.dumps(value))
        return workflow.record_publication(CANDIDATE, report)

    def evidence(self, outcome):
        """Write explicitly synthetic human-review evidence matching the candidate and chosen outcome."""
        path = self.root / "evidence.json"
        path.write_text(json.dumps({"candidate_id": CANDIDATE, "outcome": outcome,
                                    "database_verified": True, "checked_at": "2027-09-06T03:00:00+00:00",
                                    "reviewed_by": "maintainer", "never_committed": outcome == "not_committed",
                                    "observations": "Read-only DB comparison and original run evidence verified."}))
        return path

    def history(self):
        """Run the actual paginated history reader and return its checked local JSON document."""
        path = self.root / "history.json"
        workflow.publication_history(path)
        return json.loads(path.read_text())

    def test_all_pages_include_restores_and_exclude_confirmed_preflight_failures(self):
        """Keep annual evidence after the first API page and distinguish a restore from a failed attempt."""
        for number in range(99):
            tag = f"other-{number}"
            self.store.releases[tag] = {"tag_name": tag, "draft": False, "assets": []}
        self.begin(100)
        self.finish(100)
        self.begin(101, restore=True)
        self.finish(101, restore=True)
        self.begin(102)
        self.finish(102, status="failed", stage="preflight")
        history = self.history()
        self.assertEqual([row["restore"] for row in history["publications"]], [False, True])
        self.assertEqual(history["unresolved_intents"], [])
        self.assertTrue(self.store.api_calls[-2].endswith("page=1"))
        self.assertTrue(self.store.api_calls[-1].endswith("page=2"))

    def test_intent_and_uncertain_write_failure_block_every_later_history_check(self):
        """An uncertain committed write cannot disappear before a human reconciles its exact candidate."""
        self.begin()
        with self.assertRaises(workflow.UnresolvedPublicationError):
            self.history()
        result = self.finish(status="failed", stage="publish")
        self.assertEqual(result["status"], "reconciliation_required")
        self.assertTrue(self.store.releases["pipeline-publication-100-1"]["draft"])
        with self.assertRaises(workflow.UnresolvedPublicationError):
            self.history()
        resolved = workflow.reconcile_publication(100, 1, CANDIDATE, "committed", "maintainer",
                                                  "Verified commit after lost connection", self.evidence("committed"))
        self.assertEqual(resolved["status"], "reconciled")
        self.assertEqual(self.history()["publications"][0]["candidate_id"], CANDIDATE)
        self.assertEqual({asset["name"] for asset in self.store.releases["pipeline-publication-100-1"]["assets"]},
                         {"intent.json", "publication.json", "reconciliation.json"})

    def test_interrupted_restore_also_blocks_and_requires_explicit_never_committed_evidence(self):
        """Recovery attempts share the same uncertainty gate; current absence alone does not prove no commit."""
        self.begin(restore=True)
        evidence = self.evidence("not_committed")
        document = json.loads(evidence.read_text())
        document.pop("never_committed")
        evidence.write_text(json.dumps(document))
        with self.assertRaises(ValueError):
            workflow.reconcile_publication(100, 1, CANDIDATE, "not_committed", "maintainer", "Checked", evidence)
        with self.assertRaises(workflow.UnresolvedPublicationError):
            self.history()
        workflow.reconcile_publication(100, 1, CANDIDATE, "not_committed", "maintainer", "Confirmed no write ever committed",
                                       self.evidence("not_committed"))
        self.assertEqual(self.history()["publications"], [])

    def test_failed_intent_asset_upload_can_be_reconciled_from_atomic_release_body(self):
        """Preserve enough intent to recover even when the very first evidence asset cannot upload."""
        self.store.fail_upload = "intent.json"
        with self.assertRaises(RuntimeError):
            self.begin()
        self.assertEqual(self.store.releases["pipeline-publication-100-1"]["assets"], [])
        with self.assertRaises(workflow.UnresolvedPublicationError):
            self.history()
        self.store.fail_upload = None
        workflow.reconcile_publication(100, 1, CANDIDATE, "not_committed", "maintainer",
                                       "Preparation stopped before writer credentials", self.evidence("not_committed"))
        self.assertEqual(self.history()["publications"], [])

    def test_finalization_failure_cannot_erase_an_acknowledged_success(self):
        """A retained success acknowledgement keeps freezing the annual candidate despite later API failure."""
        self.begin()
        self.store.fail_finalize = True
        with self.assertRaises(RuntimeError):
            self.finish()
        self.store.fail_finalize = False
        with self.assertRaises(ValueError):
            workflow.reconcile_publication(100, 1, CANDIDATE, "not_committed", "maintainer",
                                           "Not allowed to erase a confirmed update", self.evidence("not_committed"))
        workflow.reconcile_publication(100, 1, CANDIDATE, "committed", "maintainer",
                                       "Confirmed successful DB update", self.evidence("committed"))
        self.assertEqual(len(self.history()["publications"]), 1)

    def test_candidate_release_remains_immutable_and_intent_identity_cannot_change(self):
        """Publication evidence must use its own release and cannot be relabelled as another candidate."""
        original = deepcopy(self.store.releases[self.candidate_tag])
        self.begin()
        with self.assertRaises(workflow.UnresolvedPublicationError):
            self.begin()
        self.finish()
        self.assertEqual(self.store.releases[self.candidate_tag], original)
        release = self.store.releases["pipeline-publication-100-1"]
        self.assertEqual({asset["name"] for asset in release["assets"]}, {"intent.json", "publication.json"})
        record = json.loads(self.store.documents[release["tag_name"], "publication.json"])
        record["candidate_id"] = "d" * 64
        self.store.put(release["tag_name"], "publication.json", record)
        with self.assertRaises(ValueError):
            self.history()


class RecoveryArchiveTests(unittest.TestCase):
    """Check local archive integrity and extraction boundaries using small disposable directories."""
    def setUp(self):
        """Create fixed manifest/source bytes and a fresh archive destination for each test."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.candidate = self.root / "candidate"
        (self.candidate / "raw").mkdir(parents=True)
        (self.candidate / "candidate_manifest.json").write_text(json.dumps({"candidate_id": CANDIDATE, "commit": COMMIT}))
        (self.candidate / "raw/original.csv").write_text("date,name\n2027-01-01,New Year's Day\n")
        self.assets = self.root / "assets"

    def archive(self):
        """Use tiny part sizes to exercise real multipart reconstruction without large test assets."""
        with patch.object(workflow, "PART_BYTES", 64):
            workflow.make_archive(self.candidate, self.assets, PROVENANCE, CANDIDATE)

    def resign(self):
        """Recompute transport checksums after an intentional fixture mutation.

        This makes malformed tar content checksum-valid, proving that safe extraction
        is an independent boundary rather than merely a side effect of hash checking.
        """
        files = sorted(path for path in self.assets.iterdir() if path.name != "SHA256SUMS")
        (self.assets / "SHA256SUMS").write_text("".join(f"{workflow.digest(path)}  {path.name}\n" for path in files))

    def test_split_archive_round_trip_is_deterministic_and_preserves_original_bytes(self):
        """Filesystem timestamps must not change archive bytes, and joining parts must preserve source bytes."""
        self.archive()
        self.assertGreater(len(list(self.assets.glob("*.part-*"))), 1)
        restored = self.root / "restored"
        provenance = workflow.extract_archive(self.assets, restored, CANDIDATE, REPO)
        self.assertEqual(provenance, {**PROVENANCE, "candidate_id": CANDIDATE})
        for source in self.candidate.rglob("*"):
            if source.is_file():
                self.assertEqual(source.read_bytes(), (restored / source.relative_to(self.candidate)).read_bytes())
        os.utime(self.candidate / "raw/original.csv", (1000, 1000))
        second = self.root / "second"
        with patch.object(workflow, "PART_BYTES", 64):
            workflow.make_archive(self.candidate, second, PROVENANCE, CANDIDATE)
        self.assertEqual((self.assets / "SHA256SUMS").read_bytes(), (second / "SHA256SUMS").read_bytes())

    def test_corruption_missing_parts_and_foreign_provenance_are_rejected(self):
        """Reject damaged transport and provenance before a restored directory can be used for publication."""
        self.archive()
        with self.assertRaises(ValueError):
            workflow.extract_archive(self.assets, self.root / "foreign", CANDIDATE, "other/repository")
        first = self.assets / "candidate.tar.gz.part-0000"
        original = first.read_bytes()
        first.write_bytes(b"corruption")
        with self.assertRaises(ValueError):
            workflow.extract_archive(self.assets, self.root / "corrupt", CANDIDATE, REPO)
        first.write_bytes(original)
        first.unlink()
        self.resign()
        with self.assertRaises(ValueError):
            workflow.extract_archive(self.assets, self.root / "missing", CANDIDATE, REPO)

    def test_checksum_valid_traversal_links_and_duplicate_members_are_still_rejected(self):
        """Valid checksums never authorize paths, links or repeated members that escape safe extraction."""
        self.archive()
        for path in self.assets.glob("*.part-*"):
            path.unlink()
        for number, (name, kind) in enumerate((("../escaped", "file"), ("/absolute", "file"), ("shortcut", "symlink"),
                                              ("hardlink", "hardlink"), ("repeat", "duplicate"))):
            with self.subTest(name=name):
                part = self.assets / "candidate.tar.gz.part-0000"
                with tarfile.open(part, "w:gz") as bundle:
                    member = tarfile.TarInfo(name)
                    member.size = 1
                    if kind in {"symlink", "hardlink"}:
                        member.type = tarfile.SYMTYPE if kind == "symlink" else tarfile.LNKTYPE
                        member.linkname = "../outside"
                        member.size = 0
                    bundle.addfile(member, io.BytesIO(b"x"))
                    if kind == "duplicate":
                        bundle.addfile(member, io.BytesIO(b"x"))
                self.resign()
                with self.assertRaises(ValueError):
                    workflow.extract_archive(self.assets, self.root / f"unsafe-{number}", CANDIDATE, REPO)
        self.assertFalse((self.root / "escaped").exists())

    def test_unpacked_size_limit_is_enforced(self):
        """Enforce the uncompressed limit independently of compressed file size and valid checksums."""
        self.archive()
        with patch.object(workflow, "MAX_ARCHIVE_BYTES", 1), self.assertRaises(ValueError):
            workflow.extract_archive(self.assets, self.root / "too-large", CANDIDATE, REPO)

    def test_archive_is_complete_before_release_publication_and_never_overwrites_assets(self):
        """Finalize only a verified complete archive, and make retries read-only for already retained assets."""
        source = self.root / "source-provenance.json"
        source.write_text(json.dumps(PROVENANCE))
        remote, calls = {}, []

        def github(*args):
            """Emulate draft/archive transitions and fail if unexpected GitHub mutations occur."""
            calls.append(args)
            if args[:2] == ("release", "create"):
                remote.update(target_commitish=COMMIT, draft=True, assets=[])
            elif args[:2] == ("release", "upload"):
                path = Path(args[3])
                remote["assets"].append({"name": path.name, "digest": "sha256:" + workflow.digest(path)})
            elif args[:2] == ("release", "edit"):
                self.assertEqual({asset["name"] for asset in remote["assets"]},
                                 {"candidate.tar.gz.part-0000", "SHA256SUMS", "provenance.json"})
                remote["draft"] = False
            else:
                self.fail(f"Unexpected GitHub mutation: {args[:2]}")
            return ""

        with patch.dict(os.environ, {"GITHUB_REPOSITORY": REPO}), \
             patch.object(workflow, "_release", side_effect=lambda tag: deepcopy(remote) if remote else None), \
             patch.object(workflow, "gh", side_effect=github):
            result = workflow.archive_candidate(self.candidate, CANDIDATE, source)
            self.assertEqual(result["status"], "archived")
            self.assertFalse(remote["draft"])
            before = len(calls)
            workflow.archive_candidate(self.candidate, CANDIDATE, source)
            self.assertEqual(len(calls), before)
            remote["assets"].append({"name": "unexpected.txt", "digest": "sha256:" + "d" * 64})
            with self.assertRaises(ValueError):
                workflow.archive_candidate(self.candidate, CANDIDATE, source)
            self.assertEqual(len(calls), before)

    def test_publication_record_requires_matching_final_outcome(self):
        """Reject a still-running report before invoking any GitHub command."""
        report = self.root / "publication.json"
        report.write_text(json.dumps({"status": "running", "candidate_id": CANDIDATE}))
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": REPO}), patch.object(workflow, "gh") as gh:
            with self.assertRaises(ValueError):
                workflow.record_publication(CANDIDATE, report)
            gh.assert_not_called()



if __name__ == "__main__":
    unittest.main()
