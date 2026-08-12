from __future__ import annotations

from dataclasses import dataclass
from datetime import date
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Sequence

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.local_node import validate_local_research_task
from research_pipeline.microstructure_handoff import (
    HANDOFF_CANONICALIZATION,
    INFERENCE_BOUNDARIES,
)
from research_pipeline.microstructure_discovery_recovery_intake_cli import (
    CURRENT_RELEASE,
    DROP_ROOT,
    STAGING_ROOT,
    STATE_ROOT,
    BINDING_PATH as V3R1_BINDING_PATH,
    IntakeCliBlocked,
    RuntimePaths as IntakeRuntimePaths,
    _load_binding,
    _load_state,
    _scan_drop,
    _state_path,
)
from research_pipeline.microstructure_discovery_recovery_v3r1 import (
    CONTRACT_SHA256,
    STATE_SCHEMA_SHA256,
    DiscoveryRecoveryBlocked,
    load_canonical_intake_state_bytes,
    validate_complete_envelope,
)
from research_pipeline.microstructure_source_contract import (
    AUTHORIZATION,
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    canonical_json_bytes,
    load_json_bytes_strict,
    validate_v3_day_bundle,
)


BINDING_PATH = V3R1_BINDING_PATH
CANONICAL_STATE_ROOT = STATE_ROOT
RETAINED_DAY_ROOT = DROP_ROOT
LOCAL_DIAGNOSTIC_TASK = Path(
    "/etc/agora-research/local-tasks/microstructure-v3r1-evidence-diagnostic.v1.json"
)
EXPORT_STAGING_ROOT = Path(
    "/var/lib/agora-research/microstructure-v3r1-handoff-staging"
)
EXPORT_FINAL_ROOT = Path(
    "/var/lib/agora-research/microstructure-v3r1-handoff-export"
)
HANDOFF_SCHEMA = Path(__file__).with_name(
    "microstructure-discovery-handoff-manifest.v3r1.schema.json"
)
HANDOFF_SCHEMA_SHA256 = "eef8749db62179482404dee510d6dfefd4b386c5960d98da1bc8b096e85c4617"
MANIFEST_NAME = "handoff-manifest.json"
MANIFEST_TYPE = "MICROSTRUCTURE_DISCOVERY_V3R1_CREATE_ONLY_HANDOFF_MANIFEST"
REQUIRED_DAYS = 14

_REPARSE_POINT = 0x400
_AT_FDCWD = -100
_RENAME_NOREPLACE = 1
_PACKAGE_TOP_LEVEL = {MANIFEST_NAME, "canonical", "days"}


class ExportBlocked(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class RuntimePaths:
    binding: Path
    canonical_state_root: Path
    retained_day_root: Path
    local_task: Path
    staging_root: Path
    final_root: Path
    release: Path


@dataclass(frozen=True, slots=True)
class ExportResult:
    status: str
    task_id: str
    task_sha256: str
    state_sha256: str | None
    manifest_sha256: str | None


DeviceId = Callable[[Path], int]


def fixed_runtime_paths() -> RuntimePaths:
    try:
        release = CURRENT_RELEASE.resolve(strict=True)
    except OSError as error:
        raise ExportBlocked("EXPORT_BLOCKED_INSTALLED_RELEASE") from error
    expected_parent = Path("/opt/agora-research-worker/releases")
    if release.parent != expected_parent:
        raise ExportBlocked("EXPORT_BLOCKED_INSTALLED_RELEASE")
    _require_directory(release, "EXPORT_BLOCKED_INSTALLED_RELEASE")
    return RuntimePaths(
        binding=BINDING_PATH,
        canonical_state_root=CANONICAL_STATE_ROOT,
        retained_day_root=RETAINED_DAY_ROOT,
        local_task=LOCAL_DIAGNOSTIC_TASK,
        staging_root=EXPORT_STAGING_ROOT,
        final_root=EXPORT_FINAL_ROOT,
        release=release,
    )


def _is_reparse(info: os.stat_result) -> bool:
    return bool(getattr(info, "st_file_attributes", 0) & _REPARSE_POINT)


def _lstat(path: Path, code: str) -> os.stat_result:
    try:
        info = path.lstat()
    except OSError as error:
        raise ExportBlocked(code) from error
    if stat.S_ISLNK(info.st_mode) or _is_reparse(info):
        raise ExportBlocked(code)
    return info


def _require_directory(path: Path, code: str) -> os.stat_result:
    info = _lstat(path, code)
    if not stat.S_ISDIR(info.st_mode):
        raise ExportBlocked(code)
    return info


def _read_regular_bytes(
    path: Path, code: str, *, require_nonmutable: bool = False
) -> bytes:
    before = _lstat(path, code)
    if not stat.S_ISREG(before.st_mode):
        raise ExportBlocked(code)
    if require_nonmutable and stat.S_IMODE(before.st_mode) & 0o222:
        raise ExportBlocked(code)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0)
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
        try:
            opened = os.fstat(descriptor)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_dev != before.st_dev
                or opened.st_ino != before.st_ino
            ):
                raise ExportBlocked(code)
            chunks: list[bytes] = []
            while True:
                chunk = os.read(descriptor, 1024 * 1024)
                if not chunk:
                    break
                chunks.append(chunk)
        finally:
            os.close(descriptor)
    except ExportBlocked:
        raise
    except OSError as error:
        raise ExportBlocked(code) from error
    after = _lstat(path, code)
    stable = (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
        stat.S_IMODE(after.st_mode),
    ) == (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        stat.S_IMODE(before.st_mode),
    )
    raw = b"".join(chunks)
    if not stable or len(raw) != before.st_size:
        raise ExportBlocked(code)
    return raw


def _exact_children(path: Path, expected: set[str], code: str) -> None:
    _require_directory(path, code)
    try:
        entries = list(os.scandir(path))
    except OSError as error:
        raise ExportBlocked(code) from error
    if {entry.name for entry in entries} != expected:
        raise ExportBlocked(code)
    for entry in entries:
        try:
            info = entry.stat(follow_symlinks=False)
        except OSError as error:
            raise ExportBlocked(code) from error
        if entry.is_symlink() or _is_reparse(info):
            raise ExportBlocked(code)


def _load_task(path: Path) -> tuple[dict[str, Any], bytes, str]:
    raw = _read_regular_bytes(
        path, "EXPORT_BLOCKED_LOCAL_TASK", require_nonmutable=True
    )
    try:
        value = load_json_bytes_strict(raw, "local research task")
    except ValueError as error:
        raise ExportBlocked("EXPORT_BLOCKED_LOCAL_TASK") from error
    canonical = canonical_json_bytes(value)
    if raw not in {canonical, canonical + b"\n"}:
        raise ExportBlocked("EXPORT_BLOCKED_LOCAL_TASK")
    try:
        task = validate_local_research_task(value)
    except ValueError as error:
        raise ExportBlocked("EXPORT_BLOCKED_LOCAL_TASK") from error
    limits = task["limits"]
    if (
        task["task_type"] != "EVIDENCE_DIAGNOSTIC"
        or task["execution_mode"] != "READ_ONLY"
        or limits["max_files_changed"] != 0
        or limits["max_candidate_variants"] != 0
        or limits["network_access"] != "NONE"
    ):
        raise ExportBlocked("EXPORT_BLOCKED_LOCAL_TASK_AUTHORITY")
    return task, raw, hashlib.sha256(raw).hexdigest()


def _intake_paths(paths: RuntimePaths) -> IntakeRuntimePaths:
    return IntakeRuntimePaths(
        binding=paths.binding,
        drop_root=paths.retained_day_root,
        staging_root=STAGING_ROOT,
        state_root=paths.canonical_state_root,
        release=paths.release,
    )


def _fixed_names(day_text: str) -> tuple[str, str]:
    base = f"okx-btc-usdt-microstructure-{day_text}"
    return f"{base}.json", f"{base}.complete.envelope.json"


def _validate_retained_root(
    root: Path, dispositions: list[dict[str, Any]]
) -> None:
    expected: set[str] = set()
    for record in dispositions:
        day_text = str(record["day"])
        expected.add(day_text)
        expected.add(f".{day_text}.publish-reserved")
    _exact_children(root, expected, "EXPORT_BLOCKED_RETAINED_ROOT")
    for record in dispositions:
        day_text = str(record["day"])
        reservation = root / f".{day_text}.publish-reserved"
        info = _lstat(reservation, "EXPORT_BLOCKED_RESERVATION")
        if not stat.S_ISREG(info.st_mode) or info.st_size != 0:
            raise ExportBlocked("EXPORT_BLOCKED_RESERVATION")
        base = f"okx-btc-usdt-microstructure-{day_text}"
        expected_day = (
            {f"{base}.json", f"{base}.complete.envelope.json"}
            if record["disposition"] == "COMPLETE"
            else {f"{base}.rejection.envelope.json"}
        )
        _exact_children(
            root / day_text, expected_day, "EXPORT_BLOCKED_RETAINED_DAY"
        )


def _source_package(
    paths: RuntimePaths,
    *,
    task: dict[str, Any],
    task_sha256: str,
    binding: Any,
    state: dict[str, Any],
    state_raw: bytes,
) -> tuple[dict[str, bytes], bytes, str]:
    accepted = state["selected_streak"]
    if not isinstance(accepted, list) or len(accepted) != REQUIRED_DAYS:
        raise ExportBlocked("EXPORT_BLOCKED_NOT_READY")
    _validate_retained_root(
        paths.retained_day_root, state["calendar_dispositions"]
    )
    package: dict[str, bytes] = {
        f"canonical/{binding['generation_id']}.json": state_raw
    }
    manifest_days: list[dict[str, Any]] = []
    for record in accepted:
        try:
            day = date.fromisoformat(str(record["day"]))
        except ValueError as error:
            raise ExportBlocked("EXPORT_BLOCKED_DAY") from error
        source_dir = paths.retained_day_root / day.isoformat()
        bundle_leaf, envelope_leaf = _fixed_names(day.isoformat())
        _exact_children(
            source_dir,
            {bundle_leaf, envelope_leaf},
            "EXPORT_BLOCKED_RETAINED_DAY",
        )
        bundle_path = source_dir / bundle_leaf
        envelope_path = source_dir / envelope_leaf
        bundle_raw = _read_regular_bytes(
            bundle_path, "EXPORT_BLOCKED_RETAINED_BUNDLE", require_nonmutable=True
        )
        envelope_raw = _read_regular_bytes(
            envelope_path,
            "EXPORT_BLOCKED_RETAINED_ENVELOPE",
            require_nonmutable=True,
        )
        try:
            bundle = load_json_bytes_strict(bundle_raw, "retained V3 bundle")
            envelope = load_json_bytes_strict(envelope_raw, "retained V3 envelope")
            if bundle_raw != canonical_json_bytes(bundle) or envelope_raw != canonical_json_bytes(envelope):
                raise ExportBlocked("EXPORT_BLOCKED_NONCANONICAL_SOURCE")
            bundle_result = validate_v3_day_bundle(bundle, raw_bytes=bundle_raw)
            envelope_result = validate_complete_envelope(
                envelope,
                bundle_value=bundle,
                raw_envelope_bytes=envelope_raw,
                raw_bundle_bytes=bundle_raw,
                binding_value=binding,
                expected_day=day,
                delivered_via_atomic_rename=True,
                source_path_is_symlink=False,
                overwrite_attempted=False,
                observed_producer_identity="agora-evidence-source",
            )
        except ExportBlocked:
            raise
        except (DiscoveryRecoveryBlocked, ValueError) as error:
            raise ExportBlocked("EXPORT_BLOCKED_SOURCE_CONTRACT") from error
        if (
            envelope["producer_release_id"] != binding["producer_release_id"]
            or envelope["producer_manifest_sha256"]
            != binding["producer_manifest_sha256"]
            or envelope["producer_identity"] != "agora-evidence-source"
        ):
            raise ExportBlocked("EXPORT_BLOCKED_SOURCE_RELEASE")
        if (
            record["bundle_sha256"] != bundle_result["bundle_sha256"]
            or record["envelope_sha256"] != envelope_result["envelope_sha256"]
        ):
            raise ExportBlocked("EXPORT_BLOCKED_STATE_SOURCE_DRIFT")
        bundle_name = f"days/{day.isoformat()}/{bundle_leaf}"
        envelope_name = f"days/{day.isoformat()}/{envelope_leaf}"
        package[bundle_name] = bundle_raw
        package[envelope_name] = envelope_raw
        manifest_days.append(
            {
                "day": day.isoformat(),
                "bundle_relative_name": bundle_name,
                "bundle_sha256": bundle_result["bundle_sha256"],
                "envelope_relative_name": envelope_name,
                "envelope_sha256": envelope_result["envelope_sha256"],
                "accepted_at": record["accepted_at"],
            }
        )

    first_day = date.fromisoformat(str(accepted[0]["day"]))
    last_day = date.fromisoformat(str(accepted[-1]["day"]))
    state_hash = hashlib.sha256(state_raw).hexdigest()
    dispositions = state["calendar_dispositions"]
    rejected = [
        record for record in dispositions
        if record["disposition"] == "SOURCE_LIVENESS_REJECTED"
    ]
    reason_counts: dict[str, int] = {}
    for record in rejected:
        reason = str(record["reason"])
        reason_counts[reason] = reason_counts.get(reason, 0) + 1
    nonselected_complete_count = sum(
        len(prefix) for prefix in state["nonselected_complete_prefixes"]
    ) + len(state["current_streak"])
    manifest: dict[str, Any] = {
        "schema_version": "3R1",
        "manifest_type": MANIFEST_TYPE,
        "authorization": AUTHORIZATION,
        "task_id": task["task_id"],
        "task_sha256": task_sha256,
        "canonical_state": {
            "relative_name": f"canonical/{binding['generation_id']}.json",
            "sha256": state_hash,
            "intake_state_schema_sha256": STATE_SCHEMA_SHA256,
            "state_type": "SERVER_CANONICAL_MICROSTRUCTURE_DISCOVERY_V3R1_INTAKE",
            "state_authority": "SERVER_CANONICAL",
            "generation_id": binding["generation_id"],
            "diagnostic_id": binding["diagnostic_id"],
            "status": "DIAGNOSTIC_READY",
            "calendar_start_day": binding["start_day"],
            "calendar_end_day": binding["end_day"],
            "selected_start_day": first_day.isoformat(),
            "selected_last_day": last_day.isoformat(),
            "required_day_count": REQUIRED_DAYS,
            "selected_day_count": REQUIRED_DAYS,
            "calendar_disposition_count": len(dispositions),
            "calendar_chain_head_sha256": state["calendar_chain_head_sha256"],
            "selected_streak_chain_head_sha256": state["selected_streak_chain_head_sha256"],
            "recovery_contract_sha256": CONTRACT_SHA256,
            "day_schema_sha256": V3_DAY_SCHEMA_SHA256,
            "diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
        },
        "source_release": {
            "producer_identity": "agora-evidence-source",
            "producer_release_id": binding["producer_release_id"],
            "producer_manifest_sha256": binding["producer_manifest_sha256"],
        },
        "days": manifest_days,
        "missingness": {
            "selection_rule": "FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK",
            "calendar_disposition_count": len(dispositions),
            "rejected_day_count": len(rejected),
            "rejected_reason_counts": dict(sorted(reason_counts.items())),
            "nonselected_complete_day_count": nonselected_complete_count,
            "rejected_days_as_market_input": False,
            "nonselected_prefixes_as_market_input": False,
        },
        "inference_boundaries": dict(INFERENCE_BOUNDARIES),
    }
    manifest["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(manifest, exclude_key="seal")
        ).hexdigest(),
        "canonicalization": HANDOFF_CANONICALIZATION,
    }
    _validate_handoff_manifest(manifest)
    manifest_raw = canonical_json_bytes(manifest)
    package[MANIFEST_NAME] = manifest_raw
    if len(package) != 30:
        raise ExportBlocked("EXPORT_BLOCKED_PACKAGE_COUNT")
    return package, manifest_raw, state_hash


def _validate_handoff_manifest(value: Any) -> dict[str, Any]:
    schema_raw = _read_regular_bytes(HANDOFF_SCHEMA, "EXPORT_BLOCKED_HANDOFF_SCHEMA")
    if hashlib.sha256(schema_raw).hexdigest() != HANDOFF_SCHEMA_SHA256:
        raise ExportBlocked("EXPORT_BLOCKED_HANDOFF_SCHEMA")
    try:
        schema = json.loads(schema_raw)
        manifest = dict(value)
        errors = sorted(
            Draft202012Validator(
                schema, format_checker=FormatChecker()
            ).iter_errors(manifest),
            key=lambda error: list(error.path),
        )
    except (TypeError, ValueError) as error:
        raise ExportBlocked("EXPORT_BLOCKED_HANDOFF_SCHEMA") from error
    if errors:
        raise ExportBlocked("EXPORT_BLOCKED_HANDOFF_MANIFEST")
    seal = manifest["seal"]
    if seal["payload_sha256"] != hashlib.sha256(
        canonical_json_bytes(manifest, exclude_key="seal")
    ).hexdigest():
        raise ExportBlocked("EXPORT_BLOCKED_HANDOFF_SEAL")
    if manifest["inference_boundaries"] != INFERENCE_BOUNDARIES:
        raise ExportBlocked("EXPORT_BLOCKED_HANDOFF_BOUNDARY")
    days = manifest["days"]
    parsed = [date.fromisoformat(record["day"]) for record in days]
    if any(parsed[index] != parsed[index - 1] + date.resolution for index in range(1, len(parsed))):
        raise ExportBlocked("EXPORT_BLOCKED_HANDOFF_DAYS")
    return manifest


def _package_inventory(root: Path, diagnostic_id: str, accepted: list[dict[str, Any]]) -> list[tuple[str, Path]]:
    _exact_children(root, _PACKAGE_TOP_LEVEL, "EXPORT_BLOCKED_PACKAGE_TOP_LEVEL")
    canonical = root / "canonical"
    days_root = root / "days"
    state_name = f"{diagnostic_id}.json"
    _exact_children(canonical, {state_name}, "EXPORT_BLOCKED_PACKAGE_CANONICAL")
    day_names = {str(record["day"]) for record in accepted}
    _exact_children(days_root, day_names, "EXPORT_BLOCKED_PACKAGE_DAYS")
    inventory: list[tuple[str, Path]] = [(MANIFEST_NAME, root / MANIFEST_NAME)]
    inventory.append((f"canonical/{state_name}", canonical / state_name))
    for day_text in sorted(day_names):
        bundle_leaf, envelope_leaf = _fixed_names(day_text)
        day_root = days_root / day_text
        _exact_children(
            day_root,
            {bundle_leaf, envelope_leaf},
            "EXPORT_BLOCKED_PACKAGE_DAY",
        )
        inventory.extend(
            (
                (f"days/{day_text}/{bundle_leaf}", day_root / bundle_leaf),
                (f"days/{day_text}/{envelope_leaf}", day_root / envelope_leaf),
            )
        )
    return inventory


def _validate_package_directory(
    root: Path,
    *,
    task: dict[str, Any],
    task_sha256: str,
    state: dict[str, Any],
    state_sha256: str,
    expected_manifest_raw: bytes,
) -> str:
    inventory = _package_inventory(
        root, str(state["generation_id"]), state["selected_streak"]
    )
    manifest_raw = _read_regular_bytes(root / MANIFEST_NAME, "EXPORT_BLOCKED_MANIFEST")
    try:
        manifest = load_json_bytes_strict(manifest_raw, "V3R1 handoff manifest")
    except ValueError as error:
        raise ExportBlocked("EXPORT_BLOCKED_PACKAGE_VALIDATION") from error
    if manifest_raw != canonical_json_bytes(manifest):
        raise ExportBlocked("EXPORT_BLOCKED_PACKAGE_VALIDATION")
    _validate_handoff_manifest(manifest)
    if (
        manifest_raw != expected_manifest_raw
        or manifest["task_id"] != task["task_id"]
        or manifest["task_sha256"] != task_sha256
        or manifest["canonical_state"]["sha256"] != state_sha256
    ):
        raise ExportBlocked("EXPORT_BLOCKED_PACKAGE_IDENTITY")
    expected_hashes = {
        manifest["canonical_state"]["relative_name"]:
            manifest["canonical_state"]["sha256"]
    }
    for record in manifest["days"]:
        expected_hashes[record["bundle_relative_name"]] = record["bundle_sha256"]
        expected_hashes[record["envelope_relative_name"]] = record["envelope_sha256"]
    actual_names = {name for name, _ in inventory if name != MANIFEST_NAME}
    if actual_names != set(expected_hashes):
        raise ExportBlocked("EXPORT_BLOCKED_PACKAGE_INVENTORY")
    for name, path in inventory:
        if name == MANIFEST_NAME:
            continue
        raw = _read_regular_bytes(path, "EXPORT_BLOCKED_PACKAGE_FILE")
        if hashlib.sha256(raw).hexdigest() != expected_hashes[name]:
            raise ExportBlocked("EXPORT_BLOCKED_PACKAGE_FILE_HASH")
    return hashlib.sha256(manifest_raw).hexdigest()


def _write_new_file(path: Path, raw: bytes) -> None:
    try:
        with path.open("xb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        if os.name != "nt":
            os.chmod(path, 0o400)
    except OSError as error:
        raise ExportBlocked("EXPORT_BLOCKED_STAGE_WRITE") from error


def _write_staging(root: Path, package: dict[str, bytes]) -> None:
    try:
        root.mkdir(mode=0o700)
    except OSError as error:
        raise ExportBlocked("EXPORT_BLOCKED_STAGING_CREATE") from error
    directories: set[Path] = set()
    for relative_name in package:
        parent = Path(relative_name).parent
        while parent != Path("."):
            directories.add(parent)
            parent = parent.parent
    try:
        for relative in sorted(directories, key=lambda item: (len(item.parts), str(item))):
            (root / relative).mkdir(mode=0o700, exist_ok=False)
        for relative_name, raw in sorted(package.items()):
            _write_new_file(root / Path(relative_name), raw)
        if os.name != "nt":
            for relative in sorted(directories, key=lambda item: len(item.parts), reverse=True):
                os.chmod(root / relative, 0o500)
            os.chmod(root, 0o500)
    except ExportBlocked:
        raise
    except OSError as error:
        raise ExportBlocked("EXPORT_BLOCKED_STAGE_WRITE") from error


def _rename_exclusive(source: Path, target: Path) -> None:
    if os.name == "nt":
        try:
            os.rename(source, target)
        except FileExistsError as error:
            raise ExportBlocked("EXPORT_BLOCKED_RENAME_RACE") from error
        except OSError as error:
            raise ExportBlocked("EXPORT_BLOCKED_PUBLISH") from error
        return
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise ExportBlocked("EXPORT_BLOCKED_EXCLUSIVE_RENAME_UNAVAILABLE")
    renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
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
        code = (
            "EXPORT_BLOCKED_RENAME_RACE"
            if error_number in {errno.EEXIST, errno.ENOTEMPTY}
            else "EXPORT_BLOCKED_CROSS_FILESYSTEM"
            if error_number == errno.EXDEV
            else "EXPORT_BLOCKED_PUBLISH"
        )
        raise ExportBlocked(code)


def export_handoff(
    *, paths: RuntimePaths, device_id: DeviceId | None = None
) -> ExportResult:
    task, _task_raw, task_sha256 = _load_task(paths.local_task)
    intake_paths = _intake_paths(paths)
    try:
        binding = _load_binding(
            intake_paths,
            require_future=False,
            today=date.today(),
        )
        state_path = _state_path(
            paths.canonical_state_root, binding["generation_id"]
        )
        state = _load_state(state_path, binding)
        _scan_drop(paths.retained_day_root, binding)
    except (DiscoveryRecoveryBlocked, IntakeCliBlocked, ValueError) as error:
        raise ExportBlocked("EXPORT_BLOCKED_BINDING_OR_STATE") from error
    state_raw = _read_regular_bytes(state_path, "EXPORT_BLOCKED_CANONICAL_STATE")
    try:
        stable_state = load_canonical_intake_state_bytes(state_raw, binding)
    except ValueError as error:
        raise ExportBlocked("EXPORT_BLOCKED_CANONICAL_STATE") from error
    if stable_state != state:
        raise ExportBlocked("EXPORT_BLOCKED_CANONICAL_STATE_DRIFT")
    if state["status"] != "DIAGNOSTIC_READY":
        return ExportResult("NOT_READY", str(task["task_id"]), task_sha256, None, None)

    package, manifest_raw, state_sha256 = _source_package(
        paths,
        task=task,
        task_sha256=task_sha256,
        binding=binding,
        state=state,
        state_raw=state_raw,
    )
    _require_directory(paths.staging_root, "EXPORT_BLOCKED_STAGING_ROOT")
    _require_directory(paths.final_root, "EXPORT_BLOCKED_FINAL_ROOT")
    inspect_device = device_id or (lambda path: path.stat().st_dev)
    if inspect_device(paths.staging_root) != inspect_device(paths.final_root):
        raise ExportBlocked("EXPORT_BLOCKED_CROSS_FILESYSTEM")
    staging = paths.staging_root / str(task["task_id"])
    final = paths.final_root / str(task["task_id"])
    if os.path.lexists(staging):
        raise ExportBlocked("EXPORT_BLOCKED_STALE_STAGING")
    if os.path.lexists(final):
        manifest_hash = _validate_package_directory(
            final,
            task=task,
            task_sha256=task_sha256,
            state=state,
            state_sha256=state_sha256,
            expected_manifest_raw=manifest_raw,
        )
        return ExportResult(
            "IDEMPOTENT_IDENTICAL",
            str(task["task_id"]),
            task_sha256,
            state_sha256,
            manifest_hash,
        )

    _write_staging(staging, package)
    manifest_hash = _validate_package_directory(
        staging,
        task=task,
        task_sha256=task_sha256,
        state=state,
        state_sha256=state_sha256,
        expected_manifest_raw=manifest_raw,
    )
    _rename_exclusive(staging, final)
    final_manifest_hash = _validate_package_directory(
        final,
        task=task,
        task_sha256=task_sha256,
        state=state,
        state_sha256=state_sha256,
        expected_manifest_raw=manifest_raw,
    )
    if final_manifest_hash != manifest_hash:
        raise ExportBlocked("EXPORT_BLOCKED_PUBLISHED_IDENTITY")
    return ExportResult(
        "EXPORTED",
        str(task["task_id"]),
        task_sha256,
        state_sha256,
        manifest_hash,
    )


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments:
        print(
            "usage: python -m research_pipeline.microstructure_handoff_export",
            file=sys.stderr,
        )
        return 2
    try:
        result = export_handoff(paths=fixed_runtime_paths())
    except (
        ExportBlocked,
        DiscoveryRecoveryBlocked,
        IntakeCliBlocked,
        OSError,
        ValueError,
    ) as error:
        print(str(error) or error.__class__.__name__, file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "manifest_sha256": result.manifest_sha256,
                "state_sha256": result.state_sha256,
                "status": result.status,
                "task_id": result.task_id,
                "task_sha256": result.task_sha256,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
