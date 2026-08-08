from __future__ import annotations

from dataclasses import dataclass
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tarfile
from typing import Callable, Iterable, Sequence

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_handoff import (
    RESULT_NAME,
    HandoffContext,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_handoff_runner import (
    DIAGNOSTIC_TASK_ID,
    DIAGNOSTIC_TASK_SHA256,
    HandoffRunnerBlocked,
    ManifestIdentity,
    RuntimePaths as HandoffRuntimePaths,
    _expected_directory_names,
    _expected_file_names,
    _manifest_identity_from_bytes,
    _validate_fixed_package,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    load_json_bytes_strict,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
TRANSFER_TASK_RELATIVE = (
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3-handoff-transfer.v3.json"
)
TRANSFER_TASK_ID = "local-node-microstructure-v3-handoff-transfer-v3"
TRANSFER_TASK_SHA256 = (
    "1147fa58e09eb74e4ed58a2c88c9a3c5bc58a76e30083635f2fdd94a9b30a2a2"
)
LOCAL_NODE_ROOT = Path("C:/Users/Redan/.codex/local-research-node")
TRANSPORT_PARENT = LOCAL_NODE_ROOT / "transport"
ARCHIVE_PATH = TRANSPORT_PARENT / f"{DIAGNOSTIC_TASK_ID}.tar"
STAGING_PARENT = LOCAL_NODE_ROOT / "staging"
FINAL_PARENT = LOCAL_NODE_ROOT / "inbox"
STAGING_ROOT = STAGING_PARENT / DIAGNOSTIC_TASK_ID
FINAL_ROOT = FINAL_PARENT / DIAGNOSTIC_TASK_ID

MAX_ARCHIVE_BYTES = 2 * 1024 * 1024 * 1024
MAX_MEMBER_BYTES = 512 * 1024 * 1024
MAX_PACKAGE_BYTES = 2 * 1024 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024

EXPECTED_REPOSITORY_INPUTS = {
    "research_pipeline/examples/"
    "local-research-task.microstructure-v3-evidence-diagnostic.v1.json": (
        "d50e41e5fe98e76c1ff9930baeb89ba357040dd70b2cfdd51656edbc8c03ad86"
    ),
    "research_pipeline/local_node.py": (
        "b824b431a92cbcab5609b04e80f446c17518d712192aee0e23cd3b37ea2512ed"
    ),
    "research_pipeline/microstructure-handoff-manifest.v1.schema.json": (
        "9f1d65c144ee34cd49cd74fc4b74218dbc7232d0622a8cba1ccdbe667171b090"
    ),
    "research_pipeline/microstructure_handoff.py": (
        "eda4e965a0e91636d19e62902488f57db900d4b37c61a058d54879b84b350865"
    ),
    "research_pipeline/microstructure_handoff_runner.py": (
        "6f44d5afc5f3254670414028a00843a79da1f94e97c168cc834d463b187384bc"
    ),
    "scripts/pull_microstructure_v3_handoff_ssh.ps1": (
        "b8645a9b98807bd1bb9ff64e0f42681c8fecb9f06d921132970ca6fa8f218062"
    ),
}

MANDATORY_FORBIDDEN_ACTIONS = {
    "CANONICAL_STATE_WRITE",
    "SERVER_RESEARCH_MCP_WRITE",
    "SECOND_TIMER_OR_WRITER",
    "TRADING_DB_ORDERS_FUNDS_SHADOW_PAPER_LIVE",
    "OOS_OPEN_OR_GATE_RELAXATION",
    "EXTERNAL_BACKFILL_OR_IMPORT",
    "PAID_API_OR_API_KEY",
    "PRODUCTION_OR_DATABASE_MUTATION",
    "SERVER_NETWORK_OR_SSH_EXECUTION",
    "CLOUD_SCHEDULE_CREATE_UPDATE_OR_DELETE",
    "REAL_FIXED_ROOT_RECEIVER_EXECUTION",
    "CALLER_SELECTED_REMOTE_PATH_LOCAL_DESTINATION_TASK_OR_IDENTITY",
    "TAR_EXTRACTALL_OR_UNVALIDATED_ARCHIVE_WRITE",
    "FINAL_INBOX_OVERWRITE_DELETE_REPAIR_OR_CLEANUP",
    "SOURCE_EXPORT_WRITE_DELETE_REPAIR_OR_CLEANUP",
    "HYPOTHESIS_OR_CANDIDATE_REGISTRATION",
    "JAVA_MAVEN_SPRING_OR_TRADING_EXECUTION",
}

_REPARSE_POINT = 0x400
_AT_FDCWD = -100
_RENAME_NOREPLACE = 1


class HandoffReceiveBlocked(RuntimeError):
    pass


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


@dataclass(frozen=True, slots=True)
class _FileSnapshot:
    device: int
    inode: int
    size: int
    modified_ns: int


PRODUCTION_PATHS = RuntimePaths(
    repository_root=REPOSITORY_ROOT,
    archive_path=ARCHIVE_PATH,
    staging_parent=STAGING_PARENT,
    final_parent=FINAL_PARENT,
)


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _has_reparse_point(info: os.stat_result) -> bool:
    return bool(getattr(info, "st_file_attributes", 0) & _REPARSE_POINT)


def _lstat(path: Path, label: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise HandoffReceiveBlocked(f"{label} is missing or inaccessible") from error
    if stat.S_ISLNK(info.st_mode) or _has_reparse_point(info):
        raise HandoffReceiveBlocked(f"{label} must not be a link or reparse point")
    return info


def _require_directory(path: Path, label: str) -> os.stat_result:
    info = _lstat(path, label)
    if not stat.S_ISDIR(info.st_mode):
        raise HandoffReceiveBlocked(f"{label} has the wrong filesystem type")
    return info


def _require_regular(path: Path, label: str) -> os.stat_result:
    info = _lstat(path, label)
    if not stat.S_ISREG(info.st_mode):
        raise HandoffReceiveBlocked(f"{label} has the wrong filesystem type")
    return info


def _repository_path(root: Path, relative_name: str) -> Path:
    if (
        not relative_name
        or "\\" in relative_name
        or ":" in relative_name
        or PurePosixPath(relative_name).is_absolute()
        or any(part in {"", ".", ".."} for part in relative_name.split("/"))
    ):
        raise HandoffReceiveBlocked("task repository input path is unsafe")
    current = Path(os.path.abspath(root))
    for part in PurePosixPath(relative_name).parts[:-1]:
        current = current / part
        _require_directory(current, f"repository input parent {relative_name}")
    return current / PurePosixPath(relative_name).parts[-1]


def _validate_transfer_task(paths: RuntimePaths) -> _TaskSnapshot:
    repository_root = Path(os.path.abspath(paths.repository_root))
    _require_directory(repository_root, "repository root")
    task_path = _repository_path(repository_root, TRANSFER_TASK_RELATIVE)
    _require_regular(task_path, "fixed transfer task")
    task_raw = task_path.read_bytes()
    task_hash = _sha256(task_raw)
    if task_hash != TRANSFER_TASK_SHA256:
        raise HandoffReceiveBlocked("fixed transfer task bytes changed")
    try:
        task = validate_local_research_task(
            load_json_bytes_strict(task_raw, "fixed transfer task")
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
        raise HandoffReceiveBlocked("fixed transfer task authority changed")

    listed = {
        item["locator"]: item["sha256"]
        for item in task["inputs"]
        if item["kind"] == "REPOSITORY_PATH"
    }
    if listed != EXPECTED_REPOSITORY_INPUTS:
        raise HandoffReceiveBlocked("fixed transfer task repository inputs changed")
    observed: list[tuple[str, str]] = []
    for relative_name, expected_hash in sorted(EXPECTED_REPOSITORY_INPUTS.items()):
        target = _repository_path(repository_root, relative_name)
        _require_regular(target, f"repository input {relative_name}")
        actual_hash = _sha256(target.read_bytes())
        if actual_hash != expected_hash:
            raise HandoffReceiveBlocked(
                f"repository input hash changed: {relative_name}"
            )
        observed.append((relative_name, actual_hash))
    return _TaskSnapshot(task_hash, tuple(observed))


def _validate_layout(paths: RuntimePaths) -> tuple[os.stat_result, os.stat_result]:
    archive_parent = Path(os.path.abspath(paths.archive_path.parent))
    staging_parent = Path(os.path.abspath(paths.staging_parent))
    final_parent = Path(os.path.abspath(paths.final_parent))
    _require_directory(archive_parent, "fixed transport parent")
    staging_info = _require_directory(staging_parent, "fixed staging parent")
    final_info = _require_directory(final_parent, "fixed final parent")
    if staging_info.st_dev != final_info.st_dev:
        raise HandoffReceiveBlocked("staging and final parents must share one device")
    roots = [archive_parent, staging_parent, final_parent]
    normalized = [os.path.normcase(str(item)) for item in roots]
    if len(set(normalized)) != len(normalized):
        raise HandoffReceiveBlocked("transport, staging, and final parents must differ")
    for left_index, left in enumerate(roots):
        for right_index, right in enumerate(roots):
            if left_index == right_index:
                continue
            try:
                common = os.path.commonpath((left, right))
            except ValueError as error:
                raise HandoffReceiveBlocked("fixed local roots are incompatible") from error
            if os.path.normcase(common) == os.path.normcase(str(left)):
                raise HandoffReceiveBlocked("fixed local roots must not overlap")
    return staging_info, final_info


def _snapshot(info: os.stat_result) -> _FileSnapshot:
    return _FileSnapshot(info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def _safe_member_name(name: str) -> str:
    if (
        not name
        or "\\" in name
        or ":" in name
        or "\x00" in name
        or name.startswith("/")
        or PurePosixPath(name).is_absolute()
        or any(part in {"", ".", ".."} for part in name.split("/"))
        or PurePosixPath(name).as_posix() != name
    ):
        raise HandoffReceiveBlocked("archive member path is unsafe")
    return name


def _archive_expected(identity: ManifestIdentity) -> tuple[set[str], set[str]]:
    expected_directories = {DIAGNOSTIC_TASK_ID}
    expected_directories.update(
        f"{DIAGNOSTIC_TASK_ID}/{name}"
        for name in _expected_directory_names(identity)
    )
    expected_files = {
        f"{DIAGNOSTIC_TASK_ID}/{name}"
        for name in _expected_file_names(identity)
    }
    return expected_directories, expected_files


def _read_manifest_member(
    archive: tarfile.TarFile, member: tarfile.TarInfo
) -> bytes:
    if member.size <= 0 or member.size > MAX_MANIFEST_BYTES:
        raise HandoffReceiveBlocked("archive manifest size is invalid")
    source = archive.extractfile(member)
    if source is None:
        raise HandoffReceiveBlocked("archive manifest payload is unavailable")
    try:
        raw = source.read(MAX_MANIFEST_BYTES + 1)
    finally:
        source.close()
    if len(raw) != member.size or len(raw) > MAX_MANIFEST_BYTES:
        raise HandoffReceiveBlocked("archive manifest payload size changed")
    return raw


def _validated_members(
    archive: tarfile.TarFile,
) -> tuple[dict[str, tarfile.TarInfo], ManifestIdentity]:
    members = archive.getmembers()
    observed: dict[str, tarfile.TarInfo] = {}
    package_bytes = 0
    fixed_prefix = f"{DIAGNOSTIC_TASK_ID}/"
    for member in members:
        name = _safe_member_name(member.name)
        if name in observed:
            raise HandoffReceiveBlocked("archive contains duplicate members")
        if name != DIAGNOSTIC_TASK_ID and not name.startswith(fixed_prefix):
            raise HandoffReceiveBlocked("archive member is outside the fixed task root")
        if member.linkname:
            raise HandoffReceiveBlocked("archive links are forbidden")
        if member.pax_headers and any(
            key.startswith("GNU.sparse") for key in member.pax_headers
        ):
            raise HandoffReceiveBlocked("archive sparse members are forbidden")
        sparse = getattr(member, "sparse", None)
        if sparse:
            raise HandoffReceiveBlocked("archive sparse members are forbidden")
        if member.isdir():
            if member.size != 0:
                raise HandoffReceiveBlocked("archive directory shape changed")
        elif member.isreg():
            if member.size < 0 or member.size > MAX_MEMBER_BYTES:
                raise HandoffReceiveBlocked("archive member exceeds its size bound")
            package_bytes += member.size
            if package_bytes > MAX_PACKAGE_BYTES:
                raise HandoffReceiveBlocked("archive package exceeds its size bound")
        else:
            raise HandoffReceiveBlocked("archive file type changed")
        observed[name] = member
    manifest_name = f"{DIAGNOSTIC_TASK_ID}/handoff-manifest.json"
    manifest_member = observed.get(manifest_name)
    if manifest_member is None or not manifest_member.isreg():
        raise HandoffReceiveBlocked("archive manifest member is missing or invalid")
    try:
        identity = _manifest_identity_from_bytes(
            _read_manifest_member(archive, manifest_member)
        )
    except HandoffRunnerBlocked as error:
        raise HandoffReceiveBlocked(str(error)) from error
    expected_directories, expected_files = _archive_expected(identity)
    expected_names = expected_directories | expected_files
    if len(members) != len(expected_names):
        raise HandoffReceiveBlocked("archive member count changed")
    if set(observed) != expected_names:
        raise HandoffReceiveBlocked("archive closure has missing or extra members")
    for name, member in observed.items():
        if name in expected_directories:
            if not member.isdir() or member.size != 0:
                raise HandoffReceiveBlocked("archive directory shape changed")
        elif not member.isreg():
            raise HandoffReceiveBlocked("archive file type changed")
    return observed, identity


def _write_member(
    archive: tarfile.TarFile,
    member: tarfile.TarInfo,
    destination: Path,
) -> None:
    source = archive.extractfile(member)
    if source is None:
        raise HandoffReceiveBlocked("archive member payload is unavailable")
    written = 0
    try:
        with destination.open("xb") as output:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > member.size:
                    raise HandoffReceiveBlocked("archive member exceeded declared size")
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
    except FileExistsError as error:
        raise HandoffReceiveBlocked("staging member already exists") from error
    except OSError as error:
        raise HandoffReceiveBlocked("staging member write failed") from error
    finally:
        source.close()
    if written != member.size:
        raise HandoffReceiveBlocked("archive member was truncated")


def _stage_archive(paths: RuntimePaths) -> _FileSnapshot:
    archive_info = _require_regular(paths.archive_path, "fixed transport archive")
    if archive_info.st_size <= 0 or archive_info.st_size > MAX_ARCHIVE_BYTES:
        raise HandoffReceiveBlocked("fixed transport archive size is invalid")
    archive_snapshot = _snapshot(archive_info)
    try:
        with paths.archive_path.open("rb") as stream:
            if _snapshot(os.fstat(stream.fileno())) != archive_snapshot:
                raise HandoffReceiveBlocked("fixed transport archive identity changed")
            with tarfile.open(fileobj=stream, mode="r:") as archive:
                members, identity = _validated_members(archive)
                try:
                    paths.staging_root.mkdir()
                except OSError as error:
                    raise HandoffReceiveBlocked("fixed staging root create failed") from error
                expected_directories, expected_files = _archive_expected(identity)
                relative_directories = sorted(
                    (
                        name.removeprefix(f"{DIAGNOSTIC_TASK_ID}/")
                        for name in expected_directories
                        if name != DIAGNOSTIC_TASK_ID
                    ),
                    key=lambda value: (len(PurePosixPath(value).parts), value),
                )
                for relative_name in relative_directories:
                    target = paths.staging_root.joinpath(*PurePosixPath(relative_name).parts)
                    try:
                        target.mkdir()
                    except OSError as error:
                        raise HandoffReceiveBlocked("staging directory create failed") from error
                for archive_name in sorted(expected_files):
                    relative_name = archive_name.removeprefix(
                        f"{DIAGNOSTIC_TASK_ID}/"
                    )
                    destination = paths.staging_root.joinpath(
                        *PurePosixPath(relative_name).parts
                    )
                    _write_member(archive, members[archive_name], destination)
            if _snapshot(os.fstat(stream.fileno())) != archive_snapshot:
                raise HandoffReceiveBlocked("fixed transport archive changed during receipt")
    except HandoffReceiveBlocked:
        raise
    except (OSError, tarfile.TarError, EOFError) as error:
        raise HandoffReceiveBlocked("fixed transport archive is invalid") from error
    final_archive_info = _require_regular(
        paths.archive_path, "fixed transport archive"
    )
    if _snapshot(final_archive_info) != archive_snapshot:
        raise HandoffReceiveBlocked("fixed transport archive path changed during receipt")
    return archive_snapshot


def _validate_package(
    repository_root: Path, package_root: Path
) -> tuple[HandoffContext, dict[str, Path]]:
    try:
        context, inventory = _validate_fixed_package(
            HandoffRuntimePaths(
                repository_root=repository_root,
                task_owned_root=package_root,
            )
        )
    except ValueError as error:
        raise HandoffReceiveBlocked(str(error)) from error
    result_path = inventory.get(RESULT_NAME)
    if result_path is not None:
        try:
            validate_handoff_result_bytes(result_path.read_bytes(), context)
        except ValueError as error:
            raise HandoffReceiveBlocked("existing diagnostic result is invalid") from error
    return context, inventory


def _rename_exclusive(source: Path, target: Path) -> None:
    if os.name == "nt":
        try:
            os.rename(source, target)
        except FileExistsError as error:
            raise HandoffReceiveBlocked("final inbox appeared during publish") from error
        except OSError as error:
            raise HandoffReceiveBlocked("exclusive inbox publish failed") from error
        return
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise HandoffReceiveBlocked("exclusive directory rename is unavailable")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        _AT_FDCWD,
        os.fsencode(source),
        _AT_FDCWD,
        os.fsencode(target),
        _RENAME_NOREPLACE,
    )
    if result != 0:
        error_number = ctypes.get_errno()
        if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
            raise HandoffReceiveBlocked("final inbox appeared during publish")
        if error_number == errno.EXDEV:
            raise HandoffReceiveBlocked("staging and final roots crossed devices")
        raise HandoffReceiveBlocked("exclusive inbox publish failed")


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
    staging_exists = os.path.lexists(paths.staging_root)
    final_exists = os.path.lexists(paths.final_root)
    if staging_exists:
        raise HandoffReceiveBlocked("fixed staging root already exists")
    if final_exists:
        context, _inventory = _validate_package(
            Path(os.path.abspath(paths.repository_root)), paths.final_root
        )
        if _validate_transfer_task(paths) != before_task:
            raise HandoffReceiveBlocked("transfer inputs changed during validation")
        return _result("IDEMPOTENT_IDENTICAL", context)

    _stage_archive(paths)
    staged_context, staged_inventory = _validate_package(
        Path(os.path.abspath(paths.repository_root)), paths.staging_root
    )
    if RESULT_NAME in staged_inventory:
        raise HandoffReceiveBlocked("transport package must not contain a result")
    if before_publish is not None:
        before_publish(paths)
    if _validate_transfer_task(paths) != before_task:
        raise HandoffReceiveBlocked("transfer inputs changed during receipt")
    verified_context, verified_inventory = _validate_package(
        Path(os.path.abspath(paths.repository_root)), paths.staging_root
    )
    if RESULT_NAME in verified_inventory or verified_context != staged_context:
        raise HandoffReceiveBlocked("staged handoff changed before publish")
    _rename_exclusive(paths.staging_root, paths.final_root)
    final_context, final_inventory = _validate_package(
        Path(os.path.abspath(paths.repository_root)), paths.final_root
    )
    if RESULT_NAME in final_inventory or final_context != staged_context:
        raise HandoffReceiveBlocked("published handoff identity changed")
    return _result("RECEIVED", final_context)


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(json.dumps({"reason": "zero arguments required", "status": "BLOCKED"}))
        return 2
    try:
        result = receive_handoff(PRODUCTION_PATHS)
    except Exception as error:
        print(
            json.dumps(
                {"reason": f"{type(error).__name__}: {error}", "status": "BLOCKED"},
                sort_keys=True,
            )
        )
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
