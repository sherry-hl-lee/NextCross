"""Coordinate trusted GitHub candidates, durable archives and publication evidence.

This module owns the GitHub/transport boundary. operations.py owns candidate
content validation, quality policy and database publication. Downloaded candidates
remain data: their producer commit is recorded, but their code is never executed.
Only the prepare wrapper starts operations.py, and it selects source preparation
without database access rather than a database-writing operation.

The publication workflow follows this order:

    verify source -> validate candidate -> archive -> check complete history
    -> retain intent -> publish through operations.py -> retain confirmed outcome

Candidate archives and publication evidence use separate releases. Candidate
assets can be immutable before any DB write starts. Evidence begins as a draft
intent and becomes final only after a known result or explicit human reconciliation.
A missing result or uncertain write keeps the intent pending and blocks later
history checks, including recovery attempts; a failed connection is not proof
that PostgreSQL rolled the write back.

Helpers use the runner's narrowly scoped GH_TOKEN through gh, or an authenticated
local gh session for maintenance. Listing publication history requires access to
all releases, including drafts. This module never opens a Neon connection and
never reads DB credentials. GitHub mutations are confined to workflow dispatch,
archive/evidence creation and explicit reconciliation.

Release assets outlive Actions artifact retention, but repository owners can
still delete them. Preserve an independent backup and the operator responsibilities
in the handover runbook. Lower-level helpers propagate errors; main emits sanitized
JSON so connection or authentication diagnostics are not copied into CI logs.
"""

import argparse
from datetime import date, datetime
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
from zoneinfo import ZoneInfo


# These identities are shared by source verification, archives and history. A
# release/package rename must preserve compatibility with retained evidence.
WORKFLOW = "iteration3-v1-neon.yml"
WORKFLOW_PATH = ".github/workflows/" + WORKFLOW
ARCHIVE_PREFIX = "pipeline-candidate-"
# Leave headroom below GitHub's per-asset 2 GiB ceiling. Extraction also has
# independent aggregate/file-count limits so valid checksums do not imply safety.
PART_BYTES = 1900 * 1024 * 1024
MAX_ARCHIVE_BYTES = 12 * 1024 * 1024 * 1024
MAX_ARCHIVE_FILES = 10_000


def checked_id(value):
    """Validate and return a candidate's lowercase SHA-256 identity.

    The value is used in release names and comparisons, never executed. Raise
    ValueError for a non-string or anything other than 64 hexadecimal characters.
    This checks the identifier's shape; operations.validate_candidate verifies
    that the identifier actually covers the supplied manifest and files.
    """
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Candidate ID must contain 64 lowercase hexadecimal characters")
    return value


def checked_commit(value):
    """Validate and return a full lowercase 40-character Git commit.

    Raise ValueError for an invalid value. This is a format check only: run/API
    provenance checks establish where that commit came from. No Git or network
    operation is performed here.
    """
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Source commit must contain 40 lowercase hexadecimal characters")
    return value


def checked_number(value):
    """Return a positive integer run/artifact ID or raise ValueError.

    Convert the input to text before checking it so both argparse strings and
    API integers use the same rule. Excluding signs, whitespace and separators
    keeps IDs safe when they are interpolated into fixed API paths and tags.
    """
    value = str(value)
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise ValueError("Run and artifact IDs must be positive integers")
    return int(value)


def repository():
    """Read and validate the owner/repository identity from the environment.

    Return GITHUB_REPOSITORY as a string. Missing configuration raises KeyError;
    a malformed owner/name raises ValueError. Local maintainers must supply this
    environment variable explicitly; this function does not infer a Git remote.
    """
    value = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value):
        raise ValueError("Invalid repository identity")
    return value


def gh(*args):
    """Run an internally assembled GitHub CLI command without a shell.

    Args:
        *args: Individual command arguments supplied by this module's callers.

    Returns:
        Captured standard output as text when the command succeeds.

    Raises:
        RuntimeError: For a nonzero exit, with a fixed message instead of raw
            CLI diagnostics. OSError propagates if the executable cannot run.

    Side effects:
        May read or change GitHub state according to the chosen subcommand.
        Authentication comes from the runner's scoped GH_TOKEN or local gh
        configuration. Argument lists avoid shell expansion of review text.
    """
    # gh receives discrete arguments: review prose and paths never become shell code.
    result = subprocess.run(["gh", *map(str, args)], check=False, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("GitHub request failed; check access and the requested run or release")
    return result.stdout


def api(path):
    """Read a JSON GitHub API response through the authenticated gh wrapper.

    path is an internally constructed REST endpoint. Return the decoded value;
    propagate request or JSON errors. This helper uses gh api's default GET and
    does not infer that a failed request means a resource is absent.
    """
    return json.loads(gh("api", path))


def digest(path):
    """Stream a file and return its SHA-256 hexadecimal digest.

    Propagate filesystem errors. File-size and symlink policies belong to the
    caller; hashing alone does not establish source identity or trust.
    """
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, document):
    """Write indented UTF-8 JSON with a trailing newline, creating parents.

    path is the destination and document must be JSON-serializable. Return None
    and propagate serialization or filesystem errors. This local write replaces
    existing contents; release immutability is enforced by the upload helpers.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def set_output(key, value):
    """Append one single-line value to the runner's GITHUB_OUTPUT file.

    key is a fixed internal output name; value is stringified. Reject embedded
    line breaks with ValueError before writing so values cannot create extra
    outputs. Missing runner configuration and filesystem errors propagate.
    """
    if "\n" in str(value) or "\r" in str(value):
        raise ValueError("Invalid multiline workflow output")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        stream.write(f"{key}={value}\n")


def scheduled_candidates(today):
    """Describe the independently scheduled preparation requests for a date.

    Args:
        today: Melbourne-local date chosen by the scheduler or a test.

    Returns:
        A list of input dictionaries: speed, current-year calendar, next-year
        calendar, and an annual crash candidate only in September from 2027.

    No workflows or database operations run here. Separate requests let an
    invalid future calendar fail without invalidating a successful speed run.
    """
    result = [{"dataset": "speed"}, {"dataset": "calendar", "year": str(today.year)},
              {"dataset": "calendar", "year": str(today.year + 1)}]
    if today.month == 9 and today.year >= 2027:
        result.append({"dataset": "crashes", "release_year": str(today.year)})
    return result


def dispatch_candidates(today=None):
    """Start independent main-branch preparation workflows for one month.

    Args:
        today: Optional local date; defaults to today's date in Melbourne.

    Returns:
        A summary containing the date and the dispatched input dictionaries.

    Raises:
        RuntimeError: When a GitHub dispatch fails. Earlier accepted dispatches
            remain queued; this is not an all-or-nothing remote transaction.

    Side effects:
        Creates workflow runs using actions:write permission. Every dispatched
        operation is prepare, which receives no production DB credentials.
    """
    today = today or datetime.now(ZoneInfo("Australia/Melbourne")).date()
    requests = scheduled_candidates(today)
    for request in requests:
        arguments = ["workflow", "run", WORKFLOW, "--repo", repository(), "--ref", "main",
                     "-f", "operation=prepare"]
        for key, value in request.items():
            arguments += ["-f", f"{key}={value}"]
        gh(*arguments)
    return {"status": "dispatched", "date": today.isoformat(), "candidates": requests}


def prepare_candidate():
    """Translate workflow inputs into one preparation command without DB access.

    Inputs are DATASET, GITHUB_SHA and optional year/date/review environment
    values set by the workflow. Committed calendar review paths are checked
    before being forwarded to operations.py.

    Return the preparation subprocess's exit code, or 1 for handled input/file
    errors. Save stdout or a sanitized input-error record in
    data/processed/preparation-result.json. The operations command retains its
    own detailed failure report beside the candidate. This wrapper never selects
    a publish command or passes a database connection.
    """
    report = Path("data/processed/preparation-result.json")
    report.parent.mkdir(parents=True, exist_ok=True)
    dataset = os.environ["DATASET"]
    command = ["python", "deploy/operations.py", "prepare", "--dataset", dataset,
               "--commit", os.environ["GITHUB_SHA"], "--output", "data/processed/candidate"]
    try:
        if dataset == "calendar":
            year = os.environ.get("CALENDAR_YEAR") or str(datetime.now(ZoneInfo("Australia/Melbourne")).year)
            command += ["--year", year]
            if os.environ.get("REVIEW_PATH"):
                review = validate_review_path(os.environ["REVIEW_PATH"], os.environ["GITHUB_WORKSPACE"])
                command += ["--calendar-review-dir", str(review)]
        elif dataset == "crashes":
            if not os.environ.get("RELEASE_YEAR"):
                raise ValueError("Crash preparation requires an explicit release year")
            command += ["--release-year", os.environ["RELEASE_YEAR"]]
        elif dataset == "speed":
            if os.environ.get("SPEED_AS_OF"):
                command += ["--as-of", os.environ["SPEED_AS_OF"]]
        else:
            raise ValueError("Unknown preparation dataset")
        with report.open("w") as output:
            completed = subprocess.run(command, stdout=output, check=False)
        return completed.returncode
    except (ValueError, OSError) as error:
        write_json(report, {"status": "failed", "stage": "preparation-inputs", "error_type": type(error).__name__})
        return 1


def verify_source_documents(run, artifact, workflow_id, repo, run_id, artifact_id):
    """Validate API documents before accepting a candidate artifact source.

    Args:
        run, artifact: GitHub REST documents returned for the selected IDs.
        workflow_id, repo: Expected workflow and repository identities.
        run_id, artifact_id: Validated positive IDs requested by the operator.

    Returns:
        A provenance dictionary identifying the successful main run, its attempt,
        source commit and exact immutable preparation artifact.

    Raises:
        ValueError: For a failed/foreign/PR/branch run, stale attempt, expired
            artifact or mismatched identity. No network or filesystem work occurs.

    This proves which trusted run produced the artifact. Candidate-file hashes
    and the reviewer's chosen candidate ID are checked separately after download.
    """
    if (run.get("id") != run_id or run.get("workflow_id") != workflow_id
            or run.get("status") != "completed" or run.get("conclusion") != "success"
            or run.get("head_branch") != "main"
            or run.get("event") not in {"workflow_dispatch", "schedule"}
            or run.get("head_repository", {}).get("full_name") != repo):
        raise ValueError("Candidate must come from a successful trusted main preparation run")
    commit = checked_commit(run.get("head_sha"))
    # A run ID alone is insufficient: the artifact must also belong to that run
    # and its current successful attempt, rather than a PR or an older retry.
    source = artifact.get("workflow_run") or {}
    attempt = checked_number(run.get("run_attempt"))
    if (artifact.get("id") != artifact_id or artifact.get("expired") is not False
            or artifact.get("name") != f"iteration3-v1-candidate-{run_id}-{attempt}"
            or source.get("id") != run_id or source.get("head_sha") != commit
            or source.get("head_branch") != "main"
            or not isinstance(source.get("repository_id"), int)
            or source.get("repository_id") != source.get("head_repository_id")):
        raise ValueError("Selected artifact is not this successful preparation run's candidate")
    return {"schema_version": 1, "repository": repo, "workflow": WORKFLOW_PATH,
            "run_id": run_id, "run_attempt": attempt, "artifact_id": artifact_id,
            "commit": commit}


def verify_source(run_id, artifact_id, output):
    """Fetch and persist trusted provenance for a selected preparation artifact.

    Validate the supplied run/artifact IDs, read their API documents and the
    expected workflow ID, then apply verify_source_documents. Return the verified
    provenance, save it at output, and emit its commit as a workflow step output.
    No artifact content is downloaded and no database credential is needed.

    Invalid provenance raises ValueError; API, runner-output and filesystem
    failures propagate. A failed verification therefore stops the workflow
    before any candidate is used by a writer step.
    """
    run_id, artifact_id = checked_number(run_id), checked_number(artifact_id)
    repo = repository()
    workflow = api(f"repos/{repo}/actions/workflows/{WORKFLOW}")
    run = api(f"repos/{repo}/actions/runs/{run_id}")
    artifact = api(f"repos/{repo}/actions/artifacts/{artifact_id}")
    provenance = verify_source_documents(run, artifact, workflow["id"], repo, run_id, artifact_id)
    write_json(output, provenance)
    set_output("commit", provenance["commit"])
    return provenance


def validate_review_path(value, checkout):
    """Resolve a repository-contained directory of tracked calendar review files.

    Args:
        value: Directory path relative to the repository root, from a workflow
            input. Absolute paths and parent traversal are not accepted.
        checkout: Trusted checkout root, normally the clean main Actions checkout.

    Returns:
        The resolved directory Path after containment, symlink and tracking checks.

    Raises:
        ValueError: For a missing/escaping path, symlink or untracked input file.
        OSError: If Git or filesystem inspection cannot run.

    This inspects paths and the Git index; it does not validate calendar contents
    or prove a local checkout has no uncommitted edits. The builder and workflow
    checkout establish those separate parts of the contract.
    """
    checkout = Path(checkout).resolve()
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("Calendar review path must be relative to the repository root")
    resolved = (checkout / path).resolve()
    if not resolved.is_relative_to(checkout) or not resolved.is_dir():
        raise ValueError("Calendar review directory is outside this checkout or is missing")
    for item in resolved.rglob("*"):
        if item.is_symlink():
            raise ValueError("Calendar review inputs must not contain symlinks")
        if item.is_file():
            result = subprocess.run(["git", "-C", str(checkout), "ls-files", "--error-unmatch",
                                     "--", item.relative_to(checkout).as_posix()],
                                    capture_output=True, check=False)
            if result.returncode:
                raise ValueError("Calendar review inputs must be committed to the repository")
    return resolved


def make_archive(candidate, destination, provenance, candidate_id):
    """Create a reproducible, split local archive of one validated candidate.

    Args:
        candidate: Existing candidate directory already checked by operations.py.
        destination: New directory for archive parts, provenance and SHA256SUMS.
        provenance: Verified source-run metadata, including its producer commit.
        candidate_id: Exact reviewed candidate identity to retain.

    Returns:
        The destination Path containing release-ready assets.

    Raises:
        ValueError: For identity mismatch, links/special files or safety limits.
        FileExistsError/OSError: For an existing destination or file failures.

    Side effects:
        Reads source bytes and writes a deterministic gzip/tar with normalized
        ownership, timestamps and modes. Splits it below GitHub's per-asset size
        limit, hashes each part and preserves source provenance. It does not
        upload anything or establish that the candidate was ever published.
    """
    candidate, destination = Path(candidate).resolve(), Path(destination)
    candidate_id = checked_id(candidate_id)
    manifest = json.loads((candidate / "candidate_manifest.json").read_text())
    if manifest.get("candidate_id") != candidate_id or manifest.get("commit") != provenance["commit"]:
        raise ValueError("Archive candidate does not match its verified source")
    destination.mkdir(parents=True, exist_ok=False)
    archive = destination / "candidate.tar.gz"
    paths = sorted(candidate.rglob("*"))
    if len(paths) > MAX_ARCHIVE_FILES or any(not p.is_file() and not p.is_dir() for p in paths):
        raise ValueError("Archive contains unsupported filesystem entries")
    if any(p.is_symlink() for p in paths):
        raise ValueError("Archive must not contain symlinks")
    if sum(p.stat().st_size for p in paths if p.is_file()) > MAX_ARCHIVE_BYTES:
        raise ValueError("Candidate exceeds the archive safety limit")
    # Normalize filesystem metadata as well as gzip headers. Retrying archival
    # should compare the same bytes even on a different runner or checkout time.
    with archive.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as bundle:
            for path in paths:
                if not path.is_file():
                    continue
                info = bundle.gettarinfo(str(path), arcname=path.relative_to(candidate).as_posix())
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ""
                info.mode = 0o644
                with path.open("rb") as stream:
                    bundle.addfile(info, stream)
    # Split by streaming blocks rather than holding a near-2-GiB part in memory.
    # Sorted, padded part names define the reconstruction order in SHA256SUMS.
    with archive.open("rb") as stream:
        number = 0
        while True:
            block = stream.read(min(PART_BYTES, 1024 * 1024))
            if not block:
                break
            part = destination / f"candidate.tar.gz.part-{number:04d}"
            with part.open("wb") as output:
                output.write(block)
                remaining = PART_BYTES - len(block)
                while remaining:
                    block = stream.read(min(remaining, 1024 * 1024))
                    if not block:
                        break
                    output.write(block)
                    remaining -= len(block)
            number += 1
    archive.unlink()
    write_json(destination / "provenance.json", {**provenance, "candidate_id": candidate_id})
    # Record provenance as well as archive-part hashes. SHA256SUMS is created
    # afterwards so the inventory never attempts to include its own digest.
    files = sorted(destination.iterdir())
    (destination / "SHA256SUMS").write_text(
        "".join(f"{digest(path)}  {path.name}\n" for path in files), encoding="utf-8")
    return destination


def verify_archive_files(directory, candidate_id, repo):
    """Check an archive's file inventory, checksums and declared provenance.

    directory must contain only SHA256SUMS, provenance.json and consecutively
    numbered archive parts. candidate_id and repo identify the requested archive.
    Return (provenance, ordered_part_names) after reading and hashing every asset.

    Reject missing/extra assets, links, repeated checksum entries, broken part
    sequences and foreign provenance with ValueError. Filesystem/JSON errors
    propagate. This is read-only and does not inspect tar members yet.
    """
    directory = Path(directory)
    if any(path.is_symlink() or not path.is_file() for path in directory.iterdir()):
        raise ValueError("Archive assets must be regular files")
    expected = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (provenance\.json|candidate\.tar\.gz\.part-[0-9]{4})", line)
        if not match or match[2] in expected:
            raise ValueError("Invalid archive checksum manifest")
        expected[match[2]] = match[1]
    names = {path.name for path in directory.iterdir()}
    if names != set(expected) | {"SHA256SUMS"} or "provenance.json" not in expected:
        raise ValueError("Archive files are missing or unexpected")
    parts = sorted(name for name in expected if name.startswith("candidate.tar.gz.part-"))
    if not parts or parts != [f"candidate.tar.gz.part-{i:04d}" for i in range(len(parts))]:
        raise ValueError("Archive part sequence is incomplete")
    for name, checksum in expected.items():
        path = directory / name
        if not path.is_file() or path.is_symlink() or digest(path) != checksum:
            raise ValueError("Archive checksum mismatch")
    provenance = json.loads((directory / "provenance.json").read_text())
    if (provenance.get("schema_version") != 1 or provenance.get("candidate_id") != checked_id(candidate_id)
            or provenance.get("repository") != repo or provenance.get("workflow") != WORKFLOW_PATH):
        raise ValueError("Archive provenance does not match the requested candidate")
    checked_commit(provenance.get("commit"))
    for name in ("run_id", "run_attempt", "artifact_id"):
        checked_number(provenance.get(name))
    return provenance, parts


def extract_archive(directory, destination, candidate_id, repo):
    """Restore verified candidate files into a new directory without executing them.

    Args:
        directory: Downloaded split assets and their checksum/provenance files.
        destination: New extraction directory; an existing path is rejected.
        candidate_id, repo: The requested candidate and repository identities.

    Returns:
        Source-run provenance after the restored manifest identity is checked.

    Raises:
        ValueError: For bad checksums, traversal, links, repeated members, size
            limits or manifest/provenance mismatch. Tar/filesystem errors propagate.

    Side effects:
        Joins parts in a temporary file and extracts bounded regular files only.
        A failed extraction can leave a partial destination, so callers use a
        fresh directory and stop before database access. operations.py performs
        the full candidate/payload validation after this transport-level check.
    """
    directory, destination = Path(directory), Path(destination)
    provenance, parts = verify_archive_files(directory, candidate_id, repo)
    if destination.exists():
        raise FileExistsError("Choose a new candidate extraction directory")
    destination.mkdir(parents=True)
    # Verify transport bytes before opening the tar, then validate every member
    # instead of delegating path/link handling to an unrestricted extractall().
    with tempfile.TemporaryFile() as joined:
        for name in parts:
            with (directory / name).open("rb") as stream:
                shutil.copyfileobj(stream, joined, length=1024 * 1024)
        joined.seek(0)
        total, count, names = 0, 0, set()
        with tarfile.open(fileobj=joined, mode="r|gz") as bundle:
            for member in bundle:
                path = PurePosixPath(member.name)
                if (not member.isfile() or path.is_absolute() or not path.parts or ".." in path.parts
                        or "\\" in member.name or member.name in names):
                    raise ValueError("Unsafe or repeated archive entry")
                total += member.size
                count += 1
                if member.size < 0 or total > MAX_ARCHIVE_BYTES or count > MAX_ARCHIVE_FILES:
                    raise ValueError("Archive exceeds extraction safety limits")
                names.add(member.name)
                target = destination.joinpath(*path.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                # Exclusive creation also rejects differently spelled member
                # paths that resolve to a file already extracted, such as ./x.
                with bundle.extractfile(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
    manifest = json.loads((destination / "candidate_manifest.json").read_text())
    if manifest.get("candidate_id") != candidate_id or manifest.get("commit") != provenance["commit"]:
        raise ValueError("Restored candidate identity disagrees with archive provenance")
    return provenance


def _release(tag):
    """Read a release by tag, scanning all pages before returning None.

    Return the full API document when found. Authentication/network failures
    propagate instead of being interpreted as absence; otherwise a retry might
    attempt to create a duplicate release. The caller needs access to drafts.
    """
    repo = repository()
    releases = api(f"repos/{repo}/releases?per_page=100")
    found = next((item for item in releases if item["tag_name"] == tag), None)
    if found:
        return api(f"repos/{repo}/releases/{found['id']}")
    # The endpoint above avoids treating permissions/network errors as absence.
    # Older archives may be beyond the first page; paginate before creating.
    page = 2
    while len(releases) == 100:
        releases = api(f"repos/{repo}/releases?per_page=100&page={page}")
        found = next((item for item in releases if item["tag_name"] == tag), None)
        if found:
            return api(f"repos/{repo}/releases/{found['id']}")
        page += 1
    return None


def _all_releases(repo):
    """Yield release API documents across every page using read-only requests.

    repo is a validated owner/name. The caller's token must be able to see draft
    releases because a missing pending intent would make publication history
    incomplete. Pagination prevents older annual evidence from being overlooked.
    """
    page = 1
    while True:
        releases = api(f"repos/{repo}/releases?per_page=100&page={page}")
        yield from releases
        if len(releases) < 100:
            break
        page += 1


class UnresolvedPublicationError(ValueError):
    """Mark an incomplete publication whose DB outcome must be reconciled.

    The retained tag is safe to include in the CLI's sanitized failure output,
    giving the maintainer a concrete record to inspect without exposing secrets.
    """
    def __init__(self, tag):
        """Retain the pending release tag and a fixed, non-sensitive error message."""
        self.tag = tag
        super().__init__("Resolve the recorded publication intent before another database operation")


def _release_document(release, name):
    """Read one bounded JSON release asset and verify its API digest when present.

    release identifies the tag and expected asset inventory; name is a fixed
    internal evidence filename. Return decoded JSON after a temporary download.
    Missing/duplicate/oversized assets or hash mismatches raise ValueError;
    download, decoding and filesystem errors propagate. No remote writes occur.
    """
    assets = [asset for asset in release.get("assets", []) if asset.get("name") == name]
    if len(assets) != 1 or assets[0].get("size", 0) > 2 * 1024 * 1024:
        raise ValueError("Publication evidence asset is missing, duplicated or oversized")
    with tempfile.TemporaryDirectory(prefix="publication-document-") as temporary:
        gh("release", "download", release["tag_name"], "--repo", repository(),
           "--pattern", name, "--dir", temporary)
        path = Path(temporary) / name
        if assets[0].get("digest") and assets[0]["digest"] != "sha256:" + digest(path):
            raise ValueError("Publication evidence checksum mismatch")
        return json.loads(path.read_text())


def _upload_document(tag, name, document):
    """Retain and read back an evidence document without overwriting a prior one.

    tag must identify an existing release and name is a fixed JSON asset name.
    Existing JSON must equal document; only a draft may receive a new asset.
    Return None after read-back verification. Errors leave any successfully
    uploaded evidence intact for retry or reconciliation.
    """
    with tempfile.TemporaryDirectory(prefix="publication-upload-") as temporary:
        path = Path(temporary) / name
        write_json(path, document)
        release = _release(tag)
        if release is None:
            raise ValueError("Publication release is missing")
        if any(asset["name"] == name for asset in release["assets"]):
            # A retry may follow a successful upload whose response was lost.
            # Equal retained content is reusable; different evidence is an error.
            if _release_document(release, name) != document:
                raise ValueError("Existing publication evidence differs; it will not be overwritten")
        else:
            if not release["draft"]:
                raise ValueError("Finalized publication evidence cannot be extended")
            gh("release", "upload", tag, path, "--repo", repository())
        if _release_document(_release(tag), name) != document:
            raise ValueError("Publication evidence verification failed")


def _intent(release):
    """Read and validate the identity of a retained publication intent.

    Prefer intent.json; use the release body only if its initial asset upload
    never completed. Return metadata whose repository, workflow, run/attempt,
    publication commit, candidate and crash period agree with the release.
    Raise ValueError for mismatches; read/API errors propagate. This does not
    determine whether the planned DB write actually happened.
    """
    match = re.fullmatch(r"pipeline-publication-([1-9][0-9]*)-([1-9][0-9]*)", release.get("tag_name", ""))
    if not match:
        raise ValueError("Unexpected publication release name")
    if any(asset["name"] == "intent.json" for asset in release.get("assets", [])):
        intent = _release_document(release, "intent.json")
    else:
        # Intent metadata is atomically retained in the draft release body too,
        # so a failed first asset upload can still be reconciled explicitly.
        intent = json.loads(release.get("body", ""))
    repo = repository()
    if (intent.get("schema_version") != 1 or intent.get("kind") != "publication_intent"
            or intent.get("repository") != repo or intent.get("workflow") != WORKFLOW_PATH
            or intent.get("publication_run_id") != int(match[1])
            or intent.get("publication_run_attempt") != int(match[2])
            or checked_commit(intent.get("publication_commit")) != release.get("target_commitish")
            or intent.get("dataset") not in {"speed", "crashes", "calendar"}
            or type(intent.get("restore")) is not bool):
        raise ValueError("Publication intent provenance does not match its release")
    checked_id(intent.get("candidate_id"))
    checked_commit(intent.get("candidate_commit"))
    if intent["dataset"] == "crashes":
        period = intent.get("period", {})
        for key in ("start", "end"):
            value = period.get(key)
            if not isinstance(value, str) or date.fromisoformat(value).isoformat() != value:
                raise ValueError("Publication intent has an invalid crash period")
        if period["start"] > period["end"]:
            raise ValueError("Publication intent crash period is reversed")
    return intent


def _result_identity(record, intent):
    """Require a final outcome to identify exactly the operation in its intent.

    Compare candidate/domain/period/recovery fields and provenance; return None
    or raise ValueError. This checks record consistency only, not database facts.
    """
    for name in ("candidate_id", "dataset", "period", "restore", "release_year"):
        if record.get(name) != intent.get(name):
            raise ValueError("Publication outcome disagrees with its retained intent")
    for name in ("schema_version", "repository", "workflow", "publication_run_id", "publication_run_attempt", "publication_commit"):
        if record.get(name) != intent.get(name):
            raise ValueError("Publication evidence provenance disagrees with its retained intent")
    if record.get("status") not in {"complete", "failed"}:
        raise ValueError("Publication outcome is not final")


def _resolved_publication(release):
    """Return effective evidence only for a finalized, resolved publication.

    A reviewed reconciliation takes precedence over an original uncertain write
    result while preserving that original asset. A draft, missing result or
    failed write-stage outcome without reconciliation raises
    UnresolvedPublicationError. Invalid inventories/identities raise ValueError.
    Read-only asset requests may occur; this routine never resolves an intent.
    """
    # A complete-looking asset in a draft is still unresolved: the previous run
    # may have lost the finalization response, and human review must preserve that fact.
    if release.get("draft"):
        raise UnresolvedPublicationError(release["tag_name"])
    intent = _intent(release)
    names = {asset["name"] for asset in release.get("assets", [])}
    if "intent.json" not in names or names - {"intent.json", "publication.json", "reconciliation.json"}:
        raise ValueError("Publication release has an invalid evidence inventory")
    if "reconciliation.json" in names:
        reconciliation = _release_document(release, "reconciliation.json")
        _validate_reconciliation(reconciliation, intent)
        record = reconciliation["publication"]
    elif "publication.json" in names:
        record = _release_document(release, "publication.json")
        _result_identity(record, intent)
        if record["status"] == "failed" and record.get("stage") not in {"validate", "preflight"}:
            raise UnresolvedPublicationError(release["tag_name"])
    else:
        raise UnresolvedPublicationError(release["tag_name"])
    return record


def publication_history(output):
    """Write complete annual history only when every publication intent is resolved.

    Args:
        output: Local JSON destination for the verified history used by publish.

    Returns:
        A summary with the number of successful crash publication records.

    Raises:
        UnresolvedPublicationError: If any dataset's intent, including a restore,
            remains pending. Other evidence/API/file errors also stop the scan.

    Side effects:
        Reads every release page and the associated evidence. Writes schema 1
        history with successful crash records (including restores) and an empty
        unresolved_intents list only after the full scan succeeds. It never
        updates GitHub or Neon. Consumers decide which successes freeze an
        annual period; the active DB alone cannot retain history after rollback.
    """
    publications = []
    for release in _all_releases(repository()):
        if not re.fullmatch(r"pipeline-publication-[1-9][0-9]*-[1-9][0-9]*", release.get("tag_name", "")):
            continue
        record = _resolved_publication(release)
        # Inspect every domain for uncertainty before selecting annual crash
        # evidence. An interrupted restore must not disappear from the safety gate.
        if record["status"] == "complete" and record["dataset"] == "crashes":
            publications.append(record)
    document = {"schema_version": 1, "publications": publications, "unresolved_intents": []}
    write_json(output, document)
    return {"status": "history_checked", "successful_crash_publications": len(publications)}

def archive_candidate(candidate, candidate_id, provenance_path):
    """Retain one verified candidate in a durable GitHub release before publication.

    Args:
        candidate: Validated source/payload directory to archive unchanged.
        candidate_id: Exact reviewer-selected identity.
        provenance_path: JSON written by successful source-run verification.

    Returns:
        An archived summary with the candidate release tag and URL.

    Raises:
        ValueError: For provenance/commit mismatch, unexpected or changed assets,
            or incomplete upload. Filesystem and GitHub failures propagate.

    Side effects:
        Builds temporary local archive assets, creates a draft when needed,
        uploads missing assets and verifies every retained asset. Finalizes only
        after verification. Existing assets are never clobbered; partial drafts
        remain recoverable. Archival is not evidence of a successful DB update.
    """
    candidate_id, repo = checked_id(candidate_id), repository()
    provenance = json.loads(Path(provenance_path).read_text())
    if provenance.get("repository") != repo or provenance.get("workflow") != WORKFLOW_PATH:
        raise ValueError("Archive requires verified source provenance from this workflow")
    tag = ARCHIVE_PREFIX + candidate_id
    with tempfile.TemporaryDirectory(prefix="candidate-archive-") as temporary:
        directory = make_archive(candidate, Path(temporary) / "assets", provenance, candidate_id)
        release = _release(tag)
        if release is None:
            notes = Path(temporary) / "notes.txt"
            notes.write_text(
                f"Verified candidate {candidate_id}\n\n"
                f"Source: https://github.com/{repo}/actions/runs/{provenance['run_id']}\n"
                f"Commit: {provenance['commit']}\n\n"
                "Archive retained for publication and recovery. Its existence does not mean publication succeeded.\n"
                "Preserve these assets; do not replace them. Verify SHA256SUMS before restoring.\n")
            gh("release", "create", tag, "--repo", repo, "--draft", "--target", provenance["commit"],
               "--title", f"Pipeline candidate {candidate_id[:12]}", "--notes-file", notes)
            release = _release(tag)
        if release is None or release.get("target_commitish") != provenance["commit"]:
            raise ValueError("Existing archive release points to a different source commit")
        # Resume a partial archive only by verifying existing assets and adding
        # missing ones. Never use an overwrite/clobber option on retained evidence.
        local = {path.name: digest(path) for path in directory.iterdir()}
        remote = {asset["name"]: asset for asset in release["assets"]}
        if set(remote) - set(local):
            raise ValueError("Existing archive has unexpected assets; it will not be overwritten")
        for name, asset in remote.items():
            if asset.get("digest") == "sha256:" + local[name]:
                continue
            # Older API responses may omit a digest. Download and hash those
            # bytes before accepting an existing asset as part of this retry.
            verify = Path(temporary) / "verify" / name
            verify.parent.mkdir(exist_ok=True)
            gh("release", "download", tag, "--repo", repo, "--pattern", name, "--dir", verify.parent)
            if digest(verify) != local[name]:
                raise ValueError("Existing archive asset differs; it will not be overwritten")
        for name in sorted(set(local) - set(remote)):
            gh("release", "upload", tag, directory / name, "--repo", repo)
        release = _release(tag)
        remote = {asset["name"]: asset for asset in release["assets"]}
        if set(remote) != set(local):
            raise ValueError("Durable archive upload is incomplete")
        for name, asset in remote.items():
            if asset.get("digest") != "sha256:" + local[name]:
                verify = Path(temporary) / "final-verify" / name
                verify.parent.mkdir(exist_ok=True)
                gh("release", "download", tag, "--repo", repo, "--pattern", name, "--dir", verify.parent)
                if digest(verify) != local[name]:
                    raise ValueError("Durable archive verification failed")
        if release["draft"]:
            gh("release", "edit", tag, "--repo", repo, "--draft=false", "--latest=false")
    return {"status": "archived", "candidate_id": candidate_id, "release": tag,
            "url": f"https://github.com/{repo}/releases/tag/{tag}"}


def fetch_archive(candidate_id, output, provenance_path):
    """Download a finalized candidate archive for explicit recovery.

    candidate_id selects its fixed release tag; output must be a new directory.
    provenance_path receives the checked source-run metadata. Return restoration
    metadata and emit the producer commit for the next validation step.

    Verify release completion, size bounds, asset hashes, safe extraction and
    commit agreement before returning. Failures propagate and may leave partial
    local files; no GitHub or DB writes occur. The publisher must still validate
    the candidate and its eligibility in successful publication history.
    """
    candidate_id, repo = checked_id(candidate_id), repository()
    tag = ARCHIVE_PREFIX + candidate_id
    release = _release(tag)
    if release is None or release["draft"]:
        raise ValueError("No completed durable archive exists for this candidate")
    if sum(asset["size"] for asset in release["assets"]) > MAX_ARCHIVE_BYTES:
        raise ValueError("Archive download exceeds the safety limit")
    with tempfile.TemporaryDirectory(prefix="candidate-restore-") as temporary:
        gh("release", "download", tag, "--repo", repo, "--dir", temporary,
           "--pattern", "SHA256SUMS", "--pattern", "provenance.json", "--pattern", "candidate.tar.gz.part-*")
        provenance = extract_archive(temporary, output, candidate_id, repo)
    if release.get("target_commitish") != provenance["commit"]:
        raise ValueError("Archive release source commit disagrees with provenance")
    write_json(provenance_path, provenance)
    set_output("commit", provenance["commit"])
    return {"status": "restored_candidate", **provenance}


def begin_publication(candidate, candidate_id, reviewed_by, reason, *, restore=False):
    """Durably record the planned DB operation before writer credentials are used.

    Args:
        candidate, candidate_id: Already validated candidate directory and identity.
        reviewed_by, reason: Nonempty human approval details for this operation.
        restore: Whether the operation is an explicit recovery attempt.

    Returns:
        A summary identifying the verified pending publication release.

    Raises:
        ValueError: For missing approval, identity/archive mismatch or bad metadata.
        UnresolvedPublicationError: If this run/attempt already has an intent.
        GitHub/filesystem failures propagate; any created draft remains pending.

    Side effects:
        Creates a per-run draft containing the intent both in its body and in a
        verified JSON asset. The caller must check complete history first, then
        call this before DB publication. This function does not connect to Neon
        or prove that the subsequent write happened.
    """
    candidate_id, repo = checked_id(candidate_id), repository()
    if not reviewed_by.strip() or not reason.strip():
        raise ValueError("Publication intent requires a reviewer and reason")
    manifest = json.loads((Path(candidate) / "candidate_manifest.json").read_text())
    if manifest.get("candidate_id") != candidate_id:
        raise ValueError("Publication intent candidate identity mismatch")
    checked_commit(manifest.get("commit"))
    candidate_tag = ARCHIVE_PREFIX + candidate_id
    candidate_release = _release(candidate_tag)
    if candidate_release is None or candidate_release["draft"]:
        raise ValueError("Publication intent requires a completed candidate archive")
    run, attempt = checked_number(os.environ["GITHUB_RUN_ID"]), checked_number(os.environ["GITHUB_RUN_ATTEMPT"])
    commit = checked_commit(os.environ["GITHUB_SHA"])
    tag = f"pipeline-publication-{run}-{attempt}"
    if _release(tag) is not None:
        raise UnresolvedPublicationError(tag)
    # Candidate production and publication can use different commits, especially
    # during recovery. Retain both to identify the data and the code that wrote it.
    intent = {"schema_version": 1, "kind": "publication_intent", "repository": repo, "workflow": WORKFLOW_PATH,
              "publication_run_id": run, "publication_run_attempt": attempt, "publication_commit": commit,
              "candidate_id": candidate_id, "candidate_commit": manifest["commit"],
              "dataset": manifest["dataset"], "period": manifest["period"],
              "release_year": manifest.get("release_year"), "restore": restore,
              "reviewed_by": reviewed_by, "reason": reason,
              "intent_created_at": datetime.now(ZoneInfo("UTC")).isoformat(),
              "candidate_archive": f"https://github.com/{repo}/releases/tag/{candidate_tag}"}
    with tempfile.TemporaryDirectory(prefix="publication-intent-") as temporary:
        notes = Path(temporary) / "intent.json"
        write_json(notes, intent)
        gh("release", "create", tag, "--repo", repo, "--draft", "--target", commit,
           "--title", f"Pipeline publication {run}/{attempt}: pending", "--notes-file", notes)
    # Both the atomic release body and the checked asset precede DB access. If
    # this upload fails, the body still identifies the pending operation.
    _upload_document(tag, "intent.json", intent)
    saved = _intent(_release(tag))
    if saved != intent:
        raise ValueError("Retained publication intent verification failed")
    return {"status": "intent_recorded", "candidate_id": candidate_id, "release": tag,
            "url": f"https://github.com/{repo}/releases/tag/{tag}"}


def _finish_publication(tag, status):
    """Finalize an existing publication draft after its evidence is verified.

    tag and status are internally derived. Return None; an already finalized
    release is unchanged. Missing releases raise ValueError and API failures
    propagate. Callers are responsible for verifying the resolved record first.
    """
    release = _release(tag)
    if release is None:
        raise ValueError("Publication intent release is missing")
    if release["draft"]:
        gh("release", "edit", tag, "--repo", repository(), "--draft=false", "--latest=false",
           "--title", f"{tag}: {status}")


def record_publication(candidate_id, report):
    """Retain a writer outcome and resolve only results whose DB state is known.

    Args:
        candidate_id: Exact identity already recorded by begin_publication.
        report: Sanitized operations.py result for the current run and attempt.

    Returns:
        publication_recorded after a confirmed success or pre-write failure;
        reconciliation_required after a failure during/after the write phase.

    Raises:
        ValueError: For missing intent or inconsistent identity/provenance/result.
        GitHub/filesystem errors propagate without discarding retained evidence.

    Side effects:
        Uploads publication.json to the separate evidence release. Confirmed
        results finalize that release; an uncertain write keeps its draft so
        every later history check stops. A connection error alone is not proof
        of rollback. No DB query or mutation is performed by this function.
    """
    candidate_id = checked_id(candidate_id)
    result = json.loads(Path(report).read_text())
    if result.get("candidate_id") != candidate_id or result.get("status") not in {"complete", "failed"}:
        raise ValueError("Publication outcome does not identify this candidate")
    run, attempt = checked_number(os.environ["GITHUB_RUN_ID"]), checked_number(os.environ["GITHUB_RUN_ATTEMPT"])
    tag = f"pipeline-publication-{run}-{attempt}"
    release = _release(tag)
    if release is None:
        raise ValueError("No durable pre-publication intent exists")
    intent = _intent(release)
    if intent["candidate_id"] != candidate_id or intent["publication_commit"] != checked_commit(os.environ["GITHUB_SHA"]):
        raise ValueError("Publication outcome belongs to a different intent")
    if "commit" in result and result["commit"] != intent["candidate_commit"]:
        raise ValueError("Publication outcome source commit disagrees with intent")
    record = {**intent, **result, "kind": "publication_result"}
    _result_identity(record, intent)
    _upload_document(tag, "publication.json", record)
    # A write-phase failure can occur after the server commits but before the
    # client receives acknowledgement. Keep it pending instead of declaring rollback.
    if result["status"] == "failed" and result.get("stage") not in {"validate", "preflight"}:
        return {"status": "reconciliation_required", "candidate_id": candidate_id, "release": tag,
                "message": "The write outcome is uncertain. Verify the DB and reconcile this intent before further publication."}
    _finish_publication(tag, record["status"])
    _resolved_publication(_release(tag))
    return {"status": "publication_recorded", "candidate_id": candidate_id,
            "url": f"https://github.com/{repository()}/releases/tag/{tag}"}


def _validate_reconciliation(document, intent):
    """Check the structure and identity of a human's DB-verification evidence.

    Require an exact candidate, reviewer, reason, timezone-aware check time and
    explicit committed/not_committed decision. The latter must attest that the
    operation never committed, not merely that a later restore changed the DB.
    Return None or raise ValueError/KeyError for invalid evidence. This is not an
    independent database check; the maintainer supplies the verified observations.
    """
    evidence = document.get("evidence", {})
    outcome = document.get("outcome")
    if (document.get("schema_version") != 1 or document.get("kind") != "publication_reconciliation"
            or outcome not in {"committed", "not_committed"}
            or not isinstance(document.get("reviewed_by"), str) or not document["reviewed_by"].strip()
            or not isinstance(document.get("reason"), str) or not document["reason"].strip()
            or evidence.get("candidate_id") != intent["candidate_id"] or evidence.get("outcome") != outcome
            or evidence.get("database_verified") is not True
            or evidence.get("reviewed_by") != document["reviewed_by"]
            or not isinstance(evidence.get("observations"), str) or not evidence["observations"].strip()):
        raise ValueError("Reconciliation requires explicit reviewed database evidence for this exact candidate")
    checked_at = datetime.fromisoformat(evidence["checked_at"])
    if checked_at.tzinfo is None:
        raise ValueError("Reconciliation evidence timestamp must include a timezone")
    if outcome == "not_committed" and evidence.get("never_committed") is not True:
        raise ValueError("A not-committed decision must verify the write never committed, not merely that data was later restored")
    publication = document.get("publication", {})
    _result_identity(publication, intent)
    if publication["status"] != ("complete" if outcome == "committed" else "failed"):
        raise ValueError("Reconciliation outcome and effective publication result disagree")
    for name in ("repository", "workflow", "publication_run_id", "publication_run_attempt", "publication_commit", "candidate_id"):
        if document.get(name) != intent.get(name):
            raise ValueError("Reconciliation provenance disagrees with the retained intent")


def reconcile_publication(run_id, attempt, candidate_id, outcome, reviewed_by, reason, evidence_path):
    """Resolve a pending intent using explicit human-reviewed database evidence.

    Args:
        run_id, attempt, candidate_id: Exact pending publication to reconcile.
        outcome: committed or not_committed after read-only investigation.
        reviewed_by, reason: Person and rationale recording the decision.
        evidence_path: JSON containing the verified observations and timestamp.

    Returns:
        A reconciled summary with the candidate, decision and evidence URL.

    Raises:
        ValueError: For missing/non-pending intent, identity/evidence mismatch,
            conflicting prior reconciliation, or an attempt to erase an
            acknowledged successful publication. API/file failures propagate.

    Side effects:
        Preserves the original intent/result, adds reconciliation.json and
        finalizes the evidence release. A retry of the same retained decision
        reuses its timestamp. This command never queries or writes the DB;
        unresolved outcomes must remain pending until a person establishes them.
    """
    tag = f"pipeline-publication-{checked_number(run_id)}-{checked_number(attempt)}"
    candidate_id = checked_id(candidate_id)
    release = _release(tag)
    if release is None or not release["draft"]:
        raise ValueError("Reconciliation is only available for an existing unresolved publication draft")
    intent = _intent(release)
    if intent["candidate_id"] != candidate_id:
        raise ValueError("Reconciliation candidate does not match the unresolved intent")
    original = None
    if any(asset["name"] == "publication.json" for asset in release.get("assets", [])):
        original = _release_document(release, "publication.json")
        _result_identity(original, intent)
        if (original["status"] == "complete" or original.get("published_at")) and outcome != "committed":
            raise ValueError("An acknowledged successful publication cannot be erased by reconciliation")
    evidence = json.loads(Path(evidence_path).read_text())
    result = {**intent, "kind": "publication_result", "status": "complete" if outcome == "committed" else "failed",
              "stage": "reconciled", "reconciled_at": datetime.now(ZoneInfo("UTC")).isoformat(),
              "reconciliation_outcome": outcome}
    document = {**intent, "kind": "publication_reconciliation", "outcome": outcome,
                "reviewed_by": reviewed_by, "reason": reason, "evidence": evidence, "publication": result}
    # Reuse an already-uploaded decision after a finalization/API failure. Its
    # original timestamp and observations remain evidence, not retry-time data.
    if any(asset["name"] == "reconciliation.json" for asset in release.get("assets", [])):
        saved = _release_document(release, "reconciliation.json")
        if any(saved.get(key) != document.get(key) for key in ("outcome", "reviewed_by", "reason", "evidence")):
            raise ValueError("Existing reconciliation differs; it will not be overwritten")
        document = saved
    _validate_reconciliation(document, intent)
    _upload_document(tag, "intent.json", intent)
    _upload_document(tag, "reconciliation.json", document)
    _finish_publication(tag, "reconciled " + outcome)
    _resolved_publication(_release(tag))
    return {"status": "reconciled", "candidate_id": candidate_id, "outcome": outcome,
            "url": f"https://github.com/{repository()}/releases/tag/{tag}"}

def main(argv=None):
    """Parse the workflow/recovery CLI and dispatch exactly one operation.

    argv is an optional argument list; None uses process arguments. Return 0 on
    success and 1 for handled failures or an uncertain publication needing
    reconciliation. argparse retains its normal help/usage exits.

    Print JSON summaries and sanitized known errors. An unresolved-intent error
    includes its release tag for human follow-up. Network/local/remote side
    effects depend on the selected command; this module never supplies a DB
    writer connection or executes code from a downloaded candidate.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("dispatch")
    commands.add_parser("prepare")
    verify = commands.add_parser("verify-source")
    verify.add_argument("--run-id", required=True)
    verify.add_argument("--artifact-id", required=True)
    verify.add_argument("--output", type=Path, required=True)
    archive = commands.add_parser("archive")
    archive.add_argument("--input", type=Path, required=True)
    archive.add_argument("--candidate-id", required=True)
    archive.add_argument("--provenance", type=Path, required=True)
    intent = commands.add_parser("begin-publication")
    intent.add_argument("--input", type=Path, required=True)
    intent.add_argument("--candidate-id", required=True)
    intent.add_argument("--reviewed-by", required=True)
    intent.add_argument("--reason", required=True)
    intent.add_argument("--restore", action="store_true")
    restore = commands.add_parser("fetch-archive")
    restore.add_argument("--candidate-id", required=True)
    restore.add_argument("--output", type=Path, required=True)
    restore.add_argument("--provenance", type=Path, required=True)
    evidence = commands.add_parser("record-publication")
    evidence.add_argument("--candidate-id", required=True)
    evidence.add_argument("--report", type=Path, required=True)
    history = commands.add_parser("history")
    history.add_argument("--output", type=Path, required=True)
    reconciliation = commands.add_parser("reconcile")
    reconciliation.add_argument("--run-id", required=True)
    reconciliation.add_argument("--attempt", required=True)
    reconciliation.add_argument("--candidate-id", required=True)
    reconciliation.add_argument("--outcome", choices=("committed", "not_committed"), required=True)
    reconciliation.add_argument("--reviewed-by", required=True)
    reconciliation.add_argument("--reason", required=True)
    reconciliation.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            return prepare_candidate()
        if args.command == "dispatch":
            result = dispatch_candidates()
        elif args.command == "verify-source":
            result = verify_source(args.run_id, args.artifact_id, args.output)
        elif args.command == "archive":
            result = archive_candidate(args.input, args.candidate_id, args.provenance)
        elif args.command == "begin-publication":
            result = begin_publication(args.input, args.candidate_id, args.reviewed_by, args.reason, restore=args.restore)
        elif args.command == "record-publication":
            result = record_publication(args.candidate_id, args.report)
        elif args.command == "history":
            result = publication_history(args.output)
        elif args.command == "reconcile":
            result = reconcile_publication(args.run_id, args.attempt, args.candidate_id, args.outcome,
                                           args.reviewed_by, args.reason, args.evidence)
        else:
            result = fetch_archive(args.candidate_id, args.output, args.provenance)
        print(json.dumps(result, indent=2))
        return 1 if result.get("status") == "reconciliation_required" else 0
    except (ValueError, RuntimeError, OSError, KeyError, tarfile.TarError) as error:
        result = {"status": "failed", "stage": args.command, "error_type": type(error).__name__}
        if isinstance(error, UnresolvedPublicationError):
            result.update(error_code="UNRESOLVED_PUBLICATION", release=error.tag,
                          message="Verify the database and reconcile this retained intent before another publication")
        print(json.dumps(result))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
