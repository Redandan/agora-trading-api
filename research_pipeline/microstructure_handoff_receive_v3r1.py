from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tarfile
from typing import Callable, Iterable, Any

from research_pipeline.local_node import (
    MANDATORY_FORBIDDEN_ACTIONS,
    validate_local_research_task,
)
from research_pipeline.microstructure_handoff import HandoffContext, RESULT_NAME
from research_pipeline.microstructure_handoff_receive import (
    HandoffReceiveBlocked,
    MAX_ARCHIVE_BYTES,
    MAX_MANIFEST_BYTES,
    MAX_MEMBER_BYTES,
    MAX_PACKAGE_BYTES,
    _FileSnapshot,
    _read_manifest_member,
    _rename_exclusive,
    _repository_path,
    _require_directory,
    _require_regular,
    _safe_member_name,
    _snapshot,
    _validate_layout,
    _write_member,
)
from research_pipeline.microstructure_handoff_runner_v3r1 import (
    RuntimePaths as HandoffRuntimePaths,
    _validate_fixed_package,
)
from research_pipeline.microstructure_handoff_v3r1 import (
    MANIFEST_NAME,
    expected_file_names,
    load_manifest_bytes,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
TRANSFER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3r1-handoff-transfer.v1.json"
)
TRANSFER_TASK_ID = "local-node-microstructure-v3r1-handoff-transfer-v1"
TRANSFER_TASK_SHA256 = (
    "81affa9f98b436820209d15eed334663c441efd64de73a65abd5caa2975ed2b0"
)
DIAGNOSTIC_TASK_ID = "local-node-microstructure-v3r1-evidence-diagnostic-v1"
DIAGNOSTIC_TASK_SHA256 = (
    "7c18f791996ddd1b55ba43ee0a2e194284574155d4b4e536e857e56a83a8596b"
)
LOCAL_NODE_ROOT = Path("C:/Users/Redan/.codex/local-research-node")
TRANSPORT_PARENT = LOCAL_NODE_ROOT / "transport"
ARCHIVE_PATH = TRANSPORT_PARENT / f"{DIAGNOSTIC_TASK_ID}.tar"
STAGING_PARENT = LOCAL_NODE_ROOT / "staging"
FINAL_PARENT = LOCAL_NODE_ROOT / "inbox"

EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/examples/local-research-task.microstructure-v3r1-evidence-diagnostic.v1.json": "7c18f791996ddd1b55ba43ee0a2e194284574155d4b4e536e857e56a83a8596b",
    "research_pipeline/local_node.py": "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed",
    "research_pipeline/microstructure_handoff.py": "eda4e965a0e91636d19e62902488f57db900d4b37c61a058d54879b84b350865",
    "research_pipeline/microstructure_handoff_receive.py": "5aacfd1a305b371d8363866505d53591adf3f4af8f241ea1e94ed116a881b851",
    "research_pipeline/microstructure_handoff_runner.py": "6f44d5afc5f3254670414028a00843a79da1f94e97c168cc834d463b187384bc",
    "research_pipeline/microstructure_handoff_v3r1.py": "8bd8d7d02906251b10fcf43e08b9683ea24495495b27ac53bb76dd1f51eeb76d",
    "research_pipeline/microstructure_handoff_runner_v3r1.py": "d80f253c4bdf933855b067f75d95d55cbbafb5189d353df4756356b878382718",
    "research_pipeline/microstructure-discovery-handoff-manifest.v3r1.schema.json": "eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617",
    "research_pipeline/microstructure-handoff-result.v3r1.schema.json": "11efb602cc8365034ea5128f9b76fa53c24d1480ab075dfec115ea1b7ac385f9",
    "research_pipeline/microstructure_discovery_recovery_v3r1.py": "856cf47012d83fc46280f87312f35dd15c51ea8f62083fd073f23d5847208adb",
    "research_pipeline/microstructure_source_contract.py": "1e98f439cdf6921d6299ac2f5b27e33ac0ca818b5a52a3d10e38e213563c34ee",
    "scripts/pull_microstructure_v3r1_handoff_ssh.ps1": "42e0e60a7510c39186d675757662e75290919bdb86066e3b510062f2b8a22f63",
}


@dataclass(frozen=True, slots=True)
class RuntimePaths:
    repository_root: Path
    archive_path: Path
    staging_parent: Path
    final_parent: Path

    @property
    def staging_root(self) -> Path:
        return self.staging_parent / DIAGNOSTIC_TASK_ID

    @property
    def final_root(self) -> Path:
        return self.final_parent / DIAGNOSTIC_TASK_ID


@dataclass(frozen=True, slots=True)
class _TaskSnapshot:
    task_sha256: str
    repository_hashes: tuple[tuple[str, str], ...]


PRODUCTION_PATHS = RuntimePaths(
    REPOSITORY_ROOT, ARCHIVE_PATH, STAGING_PARENT, FINAL_PARENT
)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _validate_transfer_task(paths: RuntimePaths) -> _TaskSnapshot:
    root = Path(os.path.abspath(paths.repository_root))
    _require_directory(root, "repository root")
    task_path = _repository_path(root, TRANSFER_TASK_RELATIVE)
    _require_regular(task_path, "fixed V3R1 transfer task")
    raw = task_path.read_bytes()
    if _sha256(raw) != TRANSFER_TASK_SHA256:
        raise HandoffReceiveBlocked("fixed V3R1 transfer task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(raw, "fixed V3R1 transfer task")
        )
    except ValueError as error:
        raise HandoffReceiveBlocked(str(error)) from error
    if (
        task["task_id"] != TRANSFER_TASK_ID
        or task["task_type"] != "TOOLING_VERTICAL_SLICE"
        or task["execution_mode"] != "WORKTREE_WRITE"
        or task["authorization"] != AUTHORIZATION
        or task["state_authority"] != "SERVER_CANONICAL"
        or task["timer_authority"] != "CODEX_CLOUD_OPS_ONLY"
        or task["limits"]
        != {
            "timeout_seconds": 7200,
            "max_files_changed": 3,
            "max_candidate_variants": 0,
            "network_access": "NONE",
        }
        or not MANDATORY_FORBIDDEN_ACTIONS.issubset(set(task["forbidden_actions"]))
    ):
        raise HandoffReceiveBlocked("fixed V3R1 transfer authority changed")
    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise HandoffReceiveBlocked("fixed V3R1 transfer inputs changed")
    observed: list[tuple[str, str]] = []
    for name, expected in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(root, name)
        _require_regular(target, f"repository input {name}")
        actual = _sha256(target.read_bytes())
        if actual != expected:
            raise HandoffReceiveBlocked(f"repository input hash changed: {name}")
        observed.append((name, actual))
    return _TaskSnapshot(TRANSFER_TASK_SHA256, tuple(observed))


def _expected_archive_names(manifest: dict[str, Any]) -> tuple[set[str], set[str]]:
    files = set(expected_file_names(manifest))
    directories = {
        str(PurePosixPath(name).parent)
        for name in files
        if PurePosixPath(name).parent != PurePosixPath(".")
    }
    directories.add("days")
    return (
        {DIAGNOSTIC_TASK_ID}
        | {f"{DIAGNOSTIC_TASK_ID}/{name}" for name in directories},
        {f"{DIAGNOSTIC_TASK_ID}/{name}" for name in files},
    )


def _validated_members(
    archive: tarfile.TarFile,
) -> tuple[dict[str, tarfile.TarInfo], dict[str, Any]]:
    members = archive.getmembers()
    observed: dict[str, tarfile.TarInfo] = {}
    package_bytes = 0
    prefix = f"{DIAGNOSTIC_TASK_ID}/"
    for member in members:
        name = _safe_member_name(member.name)
        if name in observed:
            raise HandoffReceiveBlocked("V3R1 archive contains duplicate members")
        if name != DIAGNOSTIC_TASK_ID and not name.startswith(prefix):
            raise HandoffReceiveBlocked("V3R1 archive member escaped fixed task root")
        if member.linkname or getattr(member, "sparse", None):
            raise HandoffReceiveBlocked("V3R1 archive links or sparse files are forbidden")
        if member.pax_headers and any(key.startswith("GNU.sparse") for key in member.pax_headers):
            raise HandoffReceiveBlocked("V3R1 archive sparse metadata is forbidden")
        if member.isdir():
            if member.size != 0:
                raise HandoffReceiveBlocked("V3R1 archive directory shape changed")
        elif member.isreg():
            if member.size < 0 or member.size > MAX_MEMBER_BYTES:
                raise HandoffReceiveBlocked("V3R1 archive member exceeds size bound")
            package_bytes += member.size
            if package_bytes > MAX_PACKAGE_BYTES:
                raise HandoffReceiveBlocked("V3R1 archive package exceeds size bound")
        else:
            raise HandoffReceiveBlocked("V3R1 archive member type changed")
        observed[name] = member
    manifest_name = f"{DIAGNOSTIC_TASK_ID}/{MANIFEST_NAME}"
    manifest_member = observed.get(manifest_name)
    if manifest_member is None or not manifest_member.isreg():
        raise HandoffReceiveBlocked("V3R1 archive manifest is missing")
    try:
        manifest = load_manifest_bytes(
            _read_manifest_member(archive, manifest_member),
            expected_task_id=DIAGNOSTIC_TASK_ID,
            expected_task_sha256=DIAGNOSTIC_TASK_SHA256,
        )
    except ValueError as error:
        raise HandoffReceiveBlocked(str(error)) from error
    expected_directories, expected_files = _expected_archive_names(manifest)
    if len(members) != len(expected_directories | expected_files) or set(observed) != expected_directories | expected_files:
        raise HandoffReceiveBlocked("V3R1 archive closure has missing or extra members")
    for name, member in observed.items():
        if name in expected_directories and (not member.isdir() or member.size != 0):
            raise HandoffReceiveBlocked("V3R1 archive directory shape changed")
        if name in expected_files and not member.isreg():
            raise HandoffReceiveBlocked("V3R1 archive file type changed")
    return observed, manifest


def _stage_archive(paths: RuntimePaths) -> _FileSnapshot:
    info = _require_regular(paths.archive_path, "fixed V3R1 transport archive")
    if info.st_size <= 0 or info.st_size > MAX_ARCHIVE_BYTES:
        raise HandoffReceiveBlocked("fixed V3R1 archive size is invalid")
    snapshot = _snapshot(info)
    try:
        with paths.archive_path.open("rb") as stream:
            if _snapshot(os.fstat(stream.fileno())) != snapshot:
                raise HandoffReceiveBlocked("fixed V3R1 archive identity changed")
            with tarfile.open(fileobj=stream, mode="r:") as archive:
                members, manifest = _validated_members(archive)
                try:
                    paths.staging_root.mkdir()
                except OSError as error:
                    raise HandoffReceiveBlocked("fixed V3R1 staging create failed") from error
                expected_directories, expected_files = _expected_archive_names(manifest)
                relative_directories = sorted(
                    (
                        name.removeprefix(f"{DIAGNOSTIC_TASK_ID}/")
                        for name in expected_directories
                        if name != DIAGNOSTIC_TASK_ID
                    ),
                    key=lambda value: (len(PurePosixPath(value).parts), value),
                )
                for name in relative_directories:
                    paths.staging_root.joinpath(*PurePosixPath(name).parts).mkdir()
                for archive_name in sorted(expected_files):
                    relative = archive_name.removeprefix(f"{DIAGNOSTIC_TASK_ID}/")
                    destination = paths.staging_root.joinpath(*PurePosixPath(relative).parts)
                    _write_member(archive, members[archive_name], destination)
            if _snapshot(os.fstat(stream.fileno())) != snapshot:
                raise HandoffReceiveBlocked("fixed V3R1 archive changed during receipt")
    except HandoffReceiveBlocked:
        raise
    except (OSError, tarfile.TarError, EOFError) as error:
        raise HandoffReceiveBlocked("fixed V3R1 archive is invalid") from error
    if _snapshot(_require_regular(paths.archive_path, "fixed V3R1 transport archive")) != snapshot:
        raise HandoffReceiveBlocked("fixed V3R1 archive path changed")
    return snapshot


def _validate_package(
    repository_root: Path, package_root: Path
) -> tuple[HandoffContext, dict[str, Path]]:
    try:
        context, inventory = _validate_fixed_package(
            HandoffRuntimePaths(repository_root, package_root)
        )
    except ValueError as error:
        raise HandoffReceiveBlocked(str(error)) from error
    result_path = inventory.get(RESULT_NAME)
    if result_path is not None:
        try:
            validate_handoff_result_bytes(result_path.read_bytes(), context)
        except ValueError as error:
            raise HandoffReceiveBlocked("existing V3R1 diagnostic result is invalid") from error
    return context, inventory


def _result(status: str, context: HandoffContext) -> dict[str, object]:
    return {
        "status": status,
        "task_id": DIAGNOSTIC_TASK_ID,
        "task_sha256": DIAGNOSTIC_TASK_SHA256,
        "manifest_sha256": context.manifest_sha256,
        "state_sha256": context.state_sha256,
        "final_relative_name": DIAGNOSTIC_TASK_ID,
    }


def receive_handoff(
    paths: RuntimePaths,
    *,
    before_publish: Callable[[RuntimePaths], None] | None = None,
) -> dict[str, object]:
    before_task = _validate_transfer_task(paths)
    _validate_layout(paths)
    if os.path.lexists(paths.staging_root):
        raise HandoffReceiveBlocked("fixed V3R1 staging root already exists")
    if os.path.lexists(paths.final_root):
        context, _ = _validate_package(Path(os.path.abspath(paths.repository_root)), paths.final_root)
        if _validate_transfer_task(paths) != before_task:
            raise HandoffReceiveBlocked("V3R1 transfer inputs changed")
        return _result("IDEMPOTENT_IDENTICAL", context)
    _stage_archive(paths)
    staged_context, staged_inventory = _validate_package(
        Path(os.path.abspath(paths.repository_root)), paths.staging_root
    )
    if RESULT_NAME in staged_inventory:
        raise HandoffReceiveBlocked("V3R1 transport package must not contain a result")
    if before_publish is not None:
        before_publish(paths)
    if _validate_transfer_task(paths) != before_task:
        raise HandoffReceiveBlocked("V3R1 transfer inputs changed during receipt")
    verified_context, verified_inventory = _validate_package(
        Path(os.path.abspath(paths.repository_root)), paths.staging_root
    )
    if RESULT_NAME in verified_inventory or verified_context != staged_context:
        raise HandoffReceiveBlocked("staged V3R1 handoff changed before publish")
    _rename_exclusive(paths.staging_root, paths.final_root)
    final_context, final_inventory = _validate_package(
        Path(os.path.abspath(paths.repository_root)), paths.final_root
    )
    if RESULT_NAME in final_inventory or final_context != staged_context:
        raise HandoffReceiveBlocked("published V3R1 handoff identity changed")
    return _result("RECEIVED", final_context)


def main(argv: Iterable[str] | None = None) -> int:
    if list(sys.argv[1:] if argv is None else argv):
        print(json.dumps({"status": "BLOCKED", "reason": "zero arguments required"}))
        return 2
    try:
        result = receive_handoff(PRODUCTION_PATHS)
    except Exception as error:
        print(json.dumps({"status": "BLOCKED", "reason": f"{type(error).__name__}: {error}"}, sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
