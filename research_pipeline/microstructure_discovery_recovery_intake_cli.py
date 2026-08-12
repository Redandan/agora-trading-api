from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
from typing import Any, Callable, Sequence

from .microstructure_discovery_recovery_v3r1 import (
    BLOCKING_FAILURE_CODES,
    CALENDAR_DAY_BUDGET,
    DiscoveryRecoveryBlocked,
    TERMINAL_STATUSES,
    advance_complete_envelope,
    advance_rejected_day,
    block_intake_state,
    canonical_intake_state_bytes,
    initial_intake_state,
    load_canonical_intake_state_bytes,
    validate_complete_envelope,
    validate_frozen_files,
    validate_rejection_envelope,
    validate_source_binding,
)
from .microstructure_source_contract import (
    ContractViolation,
    canonical_json_bytes,
    load_json_bytes_strict,
)


BINDING_PATH = Path(
    "/etc/agora-research/okx-microstructure-continuous-source-v3r1.json"
)
DROP_ROOT = Path("/var/lib/agora-evidence-source/microstructure-v3r1-drop")
STAGING_ROOT = Path(
    "/var/lib/agora-evidence-source/microstructure-v3r1-private-staging"
)
STATE_ROOT = Path("/var/lib/agora-research/state/microstructure-v3r1")
CURRENT_RELEASE = Path("/opt/agora-research-worker/current")
MINIMUM_FREE_BYTES = 2 * 1024 * 1024 * 1024

_DAY_NAME = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
_RESERVATION_NAME = re.compile(
    r"^\.([0-9]{4}-[0-9]{2}-[0-9]{2})\.publish-reserved$"
)
_MANIFEST_LINE = re.compile(r"^([0-9a-f]{64})  (.+)$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_COMMIT_INTENT_VERSION = "V3R1_CANONICAL_STATE_COMMIT_INTENT_V1"
_REQUIRED_RELEASE_FILES = {
    "research_pipeline/microstructure_discovery_recovery_v3r1.py",
    "research_pipeline/microstructure_discovery_recovery_intake_cli.py",
    "research_pipeline/okx-microstructure-discovery-recovery-contract.v3r1.json",
    "research_pipeline/okx-microstructure-discovery-source-binding.v3r1.schema.json",
    "research_pipeline/okx-microstructure-discovery-complete-envelope.v3r1.schema.json",
    "research_pipeline/okx-microstructure-discovery-rejection-envelope.v3r1.schema.json",
    "research_pipeline/okx-microstructure-discovery-intake-state.v3r1.schema.json",
    "research_pipeline/okx-microstructure-forward-day.v3.schema.json",
    "research_pipeline/okx-microstructure-forward-diagnostic-contract.v3.json",
}


class IntakeCliBlocked(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class RuntimePaths:
    binding: Path
    drop_root: Path
    staging_root: Path
    state_root: Path
    release: Path


@dataclass(frozen=True, slots=True)
class PublishedDisposition:
    day: date
    directory: Path
    reservation: Path
    kind: str
    envelope: Path
    bundle: Path | None


@dataclass(frozen=True, slots=True)
class FrozenDisposition:
    envelope_bytes: bytes
    bundle_bytes: bytes | None


Clock = Callable[[], datetime]
FreeBytes = Callable[[Path], int]
DeviceId = Callable[[Path], int]
Freezer = Callable[[PublishedDisposition], FrozenDisposition]


def fixed_runtime_paths() -> RuntimePaths:
    try:
        release = CURRENT_RELEASE.resolve(strict=True)
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_INSTALLED_RELEASE") from error
    if (
        release.parent != Path("/opt/agora-research-worker/releases")
        or release.is_symlink()
        or not release.is_dir()
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_INSTALLED_RELEASE")
    return RuntimePaths(
        binding=BINDING_PATH,
        drop_root=DROP_ROOT,
        staging_root=STAGING_ROOT,
        state_root=STATE_ROOT,
        release=release,
    )


def run(
    command: str,
    *,
    paths: RuntimePaths,
    clock: Clock | None = None,
    free_bytes: FreeBytes | None = None,
    device_id: DeviceId | None = None,
    freezer: Freezer | None = None,
) -> str:
    if command not in {"initialize", "ingest"}:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_COMMAND")
    validate_frozen_files()
    now = _utc_now(clock)
    binding = _load_binding(
        paths, require_future=command == "initialize", today=now.date()
    )
    state_path = _state_path(paths.state_root, binding["generation_id"])
    _recover_interrupted_commit(state_path, binding)

    if command == "initialize":
        _storage_gates(paths, free_bytes=free_bytes, device_id=device_id)
        if _scan_drop(paths.drop_root, binding):
            raise IntakeCliBlocked("RECOVERY_BLOCKED_PREEXISTING_DROP")
        if os.path.lexists(state_path):
            return str(_load_state(state_path, binding)["status"])
        state = initial_intake_state(binding)
        _commit_state(state_path, canonical_intake_state_bytes(state, binding), binding)
        return str(state["status"])

    if not os.path.lexists(state_path):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STATE_MISSING")
    state = _load_state(state_path, binding)
    _storage_gates(paths, free_bytes=free_bytes, device_id=device_id)
    published = _scan_drop(paths.drop_root, binding)
    recorded_days = {
        disposition["day"] for disposition in state["calendar_dispositions"]
    }
    if not recorded_days.issubset({item.day.isoformat() for item in published}):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RECORDED_DROP_MISSING")
    freeze = freezer or freeze_published_disposition
    disposition = str(state["status"])
    for item in published:
        state = _load_state(state_path, binding)
        frozen = freeze(item)
        prior_count = len(state["calendar_dispositions"])
        calendar_index = (item.day - date.fromisoformat(binding["start_day"])).days + 1
        if calendar_index <= prior_count:
            try:
                _validate_prior_disposition(state, binding, item, frozen)
            except DiscoveryRecoveryBlocked as error:
                if state["status"] in TERMINAL_STATUSES:
                    raise IntakeCliBlocked(
                        "RECOVERY_BLOCKED_TERMINAL_ARTIFACT_DRIFT"
                    ) from error
                state = _block_from_error(state, binding, error)
                _commit_state(
                    state_path,
                    canonical_intake_state_bytes(state, binding),
                    binding,
                )
                return "INTEGRITY_BLOCKED"
            disposition = "IDEMPOTENT_DUPLICATE"
            continue
        if state["status"] in TERMINAL_STATUSES:
            raise IntakeCliBlocked("RECOVERY_BLOCKED_TERMINAL_EXTRA_DROP")
        if calendar_index != prior_count + 1:
            state = _block_from_error(
                state,
                binding,
                DiscoveryRecoveryBlocked(
                    "WRONG_DAY", "published disposition skips the next calendar day"
                ),
            )
            _commit_state(
                state_path, canonical_intake_state_bytes(state, binding), binding
            )
            return "INTEGRITY_BLOCKED"
        try:
            state = _advance(state, binding, item, frozen, accepted_at=now)
        except DiscoveryRecoveryBlocked as error:
            state = _block_from_error(state, binding, error)
        _commit_state(state_path, canonical_intake_state_bytes(state, binding), binding)
        disposition = str(state["status"])
    return disposition


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if len(arguments) != 1 or arguments[0] not in {"initialize", "ingest"}:
        print(
            "usage: python -m research_pipeline.microstructure_discovery_recovery_intake_cli {initialize|ingest}",
            file=sys.stderr,
        )
        return 2
    try:
        status = run(arguments[0], paths=fixed_runtime_paths())
    except (
        ContractViolation,
        DiscoveryRecoveryBlocked,
        IntakeCliBlocked,
        OSError,
        ValueError,
    ) as error:
        print(str(error) or error.__class__.__name__, file=sys.stderr)
        return 2
    print(json.dumps({"status": status}, separators=(",", ":"), sort_keys=True))
    return 0


def _load_binding(
    paths: RuntimePaths, *, require_future: bool, today: date
) -> dict[str, Any]:
    raw = _read_stable_regular_bytes(
        paths.binding, "RECOVERY_BLOCKED_BINDING"
    )
    try:
        value = load_json_bytes_strict(raw, "V3R1 source binding")
    except ContractViolation as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_BINDING") from error
    if raw != canonical_json_bytes(value):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_BINDING")
    try:
        binding = validate_source_binding(value)
    except DiscoveryRecoveryBlocked as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_BINDING") from error
    start_day = date.fromisoformat(binding["start_day"])
    if require_future and start_day <= today:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_NONFUTURE_START")
    _validate_release(paths.release, binding)
    return binding


def _validate_release(release: Path, binding: dict[str, Any]) -> None:
    if (
        not release.is_dir()
        or release.is_symlink()
        or release.name != binding["producer_release_id"]
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_IDENTITY")
    manifest_path = release / ".release" / "source.sha256"
    provenance_path = release / ".release" / "provenance.json"
    manifest_bytes = _read_stable_regular_bytes(
        manifest_path, "RECOVERY_BLOCKED_RELEASE_MANIFEST"
    )
    if hashlib.sha256(manifest_bytes).hexdigest() != binding[
        "producer_manifest_sha256"
    ]:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST")
    try:
        provenance = load_json_bytes_strict(
            _read_stable_regular_bytes(
                provenance_path, "RECOVERY_BLOCKED_RELEASE_PROVENANCE"
            ),
            "release provenance",
        )
    except ContractViolation as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_PROVENANCE") from error
    if (
        provenance.get("release_id") != release.name
        or provenance.get("source_manifest_sha256")
        != binding["producer_manifest_sha256"]
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_PROVENANCE")
    entries: dict[str, str] = {}
    try:
        lines = manifest_bytes.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST") from error
    for line in lines:
        match = _MANIFEST_LINE.fullmatch(line)
        if match is None or match.group(2) in entries:
            raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST")
        relative = match.group(2)
        if (
            relative.startswith(("/", "\\"))
            or "\\" in relative
            or ".." in Path(relative).parts
        ):
            raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST")
        entries[relative] = match.group(1)
    if not _REQUIRED_RELEASE_FILES.issubset(entries):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST")
    for relative, expected_hash in entries.items():
        path = release / Path(relative)
        if hashlib.sha256(
            _read_stable_regular_bytes(
                path, "RECOVERY_BLOCKED_RELEASE_MANIFEST"
            )
        ).hexdigest() != expected_hash:
            raise IntakeCliBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST")


def _state_path(state_root: Path, generation_id: str) -> Path:
    if not state_root.is_dir() or state_root.is_symlink():
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STATE_DIRECTORY")
    if not re.fullmatch(
        r"okx-btcusdt-microstructure-discovery-v3r1-[0-9]{8}-r[0-9]+",
        generation_id,
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_GENERATION_ID")
    return state_root / f"{generation_id}.json"


def _load_state(state_path: Path, binding: dict[str, Any]) -> dict[str, Any]:
    if state_path.is_symlink() or not state_path.is_file():
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STATE_FILE")
    if os.path.lexists(_lock_path(state_path)):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_PRESENT")
    if os.path.lexists(_temp_path(state_path)):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STALE_TEMP")
    return load_canonical_intake_state_bytes(
        _read_stable_regular_bytes(state_path, "RECOVERY_BLOCKED_STATE_FILE"),
        binding,
    )


def _storage_gates(
    paths: RuntimePaths,
    *,
    free_bytes: FreeBytes | None,
    device_id: DeviceId | None,
) -> None:
    for root in (paths.staging_root, paths.drop_root):
        if not root.is_dir() or root.is_symlink():
            raise IntakeCliBlocked("RECOVERY_BLOCKED_STORAGE_ROOT")
    free = (free_bytes or (lambda path: shutil.disk_usage(path).free))(
        paths.drop_root
    )
    if free < MINIMUM_FREE_BYTES:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_CAPACITY")
    device = device_id or (lambda path: path.stat().st_dev)
    if device(paths.staging_root) != device(paths.drop_root):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_FILESYSTEM")


def _scan_drop(
    drop_root: Path, binding: dict[str, Any]
) -> list[PublishedDisposition]:
    try:
        entries = sorted(os.scandir(drop_root), key=lambda entry: entry.name)
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DROP_SCAN") from error
    day_entries: dict[date, Path] = {}
    reservations: dict[date, Path] = {}
    for entry in entries:
        if entry.is_symlink():
            raise IntakeCliBlocked("RECOVERY_BLOCKED_DROP_SYMLINK")
        day_match = _DAY_NAME.fullmatch(entry.name)
        reservation_match = _RESERVATION_NAME.fullmatch(entry.name)
        if day_match and entry.is_dir(follow_symlinks=False):
            day_entries[_parse_day(entry.name)] = Path(entry.path)
        elif reservation_match and entry.is_file(follow_symlinks=False):
            parsed = _parse_day(reservation_match.group(1))
            if entry.stat(follow_symlinks=False).st_size != 0:
                raise IntakeCliBlocked("RECOVERY_BLOCKED_RESERVATION")
            reservations[parsed] = Path(entry.path)
        else:
            raise IntakeCliBlocked("RECOVERY_BLOCKED_DROP_ENTRY")
    if (
        len(day_entries) > CALENDAR_DAY_BUDGET
        or set(day_entries) != set(reservations)
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DROP_INVENTORY")
    start = date.fromisoformat(binding["start_day"])
    end = date.fromisoformat(binding["end_day"])
    if any(day < start or day > end for day in day_entries):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STALE_ENTRY")
    return [
        _published_disposition(day, day_entries[day], reservations[day])
        for day in sorted(day_entries)
    ]


def _published_disposition(
    day: date, directory: Path, reservation: Path
) -> PublishedDisposition:
    prefix = f"okx-btc-usdt-microstructure-{day.isoformat()}"
    bundle_name = f"{prefix}.json"
    complete_name = f"{prefix}.complete.envelope.json"
    rejection_name = f"{prefix}.rejection.envelope.json"
    try:
        children = sorted(os.scandir(directory), key=lambda entry: entry.name)
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DAY_SCAN") from error
    names = {child.name for child in children}
    complete_shape = names == {bundle_name, complete_name}
    rejection_shape = names == {rejection_name}
    if not complete_shape and not rejection_shape:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DAY_SHAPE")
    if any(
        child.is_symlink() or not child.is_file(follow_symlinks=False)
        for child in children
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DAY_FILE")
    by_name = {child.name: Path(child.path) for child in children}
    return PublishedDisposition(
        day=day,
        directory=directory,
        reservation=reservation,
        kind="COMPLETE" if complete_shape else "SOURCE_LIVENESS_REJECTED",
        envelope=by_name[complete_name] if complete_shape else by_name[rejection_name],
        bundle=by_name[bundle_name] if complete_shape else None,
    )


def freeze_published_disposition(item: PublishedDisposition) -> FrozenDisposition:
    if os.name == "nt":
        raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_PLATFORM")
    try:
        import grp
        import pwd

        root_uid = pwd.getpwnam("root").pw_uid
        research_gid = grp.getgrnam("agora-research").gr_gid
    except (KeyError, OSError) as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_IDENTITY") from error
    files = [item.reservation, item.envelope]
    if item.bundle is not None:
        files.append(item.bundle)
    before = {path: _file_digest(path) for path in files}
    try:
        for path in files:
            details = path.lstat()
            if not stat.S_ISREG(details.st_mode) or path.is_symlink():
                raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
            os.chown(path, root_uid, research_gid, follow_symlinks=False)
            os.chmod(path, 0o440, follow_symlinks=False)
        directory_details = item.directory.lstat()
        if not stat.S_ISDIR(directory_details.st_mode) or item.directory.is_symlink():
            raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
        os.chown(item.directory, root_uid, research_gid, follow_symlinks=False)
        os.chmod(item.directory, 0o550, follow_symlinks=False)
    except IntakeCliBlocked:
        raise
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_FREEZE") from error
    for path in files:
        details = path.lstat()
        if (
            not stat.S_ISREG(details.st_mode)
            or path.is_symlink()
            or details.st_uid != root_uid
            or details.st_gid != research_gid
            or stat.S_IMODE(details.st_mode) != 0o440
            or _file_digest(path) != before[path]
        ):
            raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
    directory_details = item.directory.lstat()
    if (
        directory_details.st_uid != root_uid
        or directory_details.st_gid != research_gid
        or stat.S_IMODE(directory_details.st_mode) != 0o550
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
    return FrozenDisposition(
        envelope_bytes=_read_stable_regular_bytes(
            item.envelope, "RECOVERY_BLOCKED_ENVELOPE"
        ),
        bundle_bytes=(
            None
            if item.bundle is None
            else _read_stable_regular_bytes(
                item.bundle, "RECOVERY_BLOCKED_BUNDLE"
            )
        ),
    )


def _advance(
    state: dict[str, Any],
    binding: dict[str, Any],
    item: PublishedDisposition,
    frozen: FrozenDisposition,
    *,
    accepted_at: datetime,
) -> dict[str, Any]:
    envelope = _load_document(frozen.envelope_bytes, "V3R1 disposition envelope")
    if item.kind == "COMPLETE":
        if frozen.bundle_bytes is None:
            raise DiscoveryRecoveryBlocked(
                "CONTRACT_HASH_MISMATCH", "complete bundle bytes are missing"
            )
        bundle = _load_document(frozen.bundle_bytes, "V3R1 complete bundle")
        return advance_complete_envelope(
            state,
            envelope,
            bundle,
            raw_complete_bytes=frozen.envelope_bytes,
            raw_bundle_bytes=frozen.bundle_bytes,
            binding_value=binding,
            accepted_at=accepted_at,
        )
    if frozen.bundle_bytes is not None:
        raise DiscoveryRecoveryBlocked(
            "CONTRACT_HASH_MISMATCH", "rejection contains market bundle bytes"
        )
    return advance_rejected_day(
        state,
        envelope,
        raw_rejection_bytes=frozen.envelope_bytes,
        binding_value=binding,
    )


def _validate_prior_disposition(
    state: dict[str, Any],
    binding: dict[str, Any],
    item: PublishedDisposition,
    frozen: FrozenDisposition,
) -> None:
    index = (item.day - date.fromisoformat(binding["start_day"])).days
    recorded = state["calendar_dispositions"][index]
    envelope_hash = hashlib.sha256(frozen.envelope_bytes).hexdigest()
    if (
        recorded["day"] != item.day.isoformat()
        or recorded["artifact_sha256"] != envelope_hash
        or recorded["disposition"] != item.kind
    ):
        raise DiscoveryRecoveryBlocked(
            "CONFLICTING_DUPLICATE", "prior disposition bytes changed"
        )
    envelope = _load_document(frozen.envelope_bytes, "V3R1 prior envelope")
    if item.kind == "COMPLETE":
        if frozen.bundle_bytes is None:
            raise DiscoveryRecoveryBlocked(
                "CONFLICTING_DUPLICATE", "prior complete bundle is missing"
            )
        bundle = _load_document(frozen.bundle_bytes, "V3R1 prior bundle")
        validated = validate_complete_envelope(
            envelope,
            bundle_value=bundle,
            raw_envelope_bytes=frozen.envelope_bytes,
            raw_bundle_bytes=frozen.bundle_bytes,
            binding_value=binding,
            expected_day=item.day,
            delivered_via_atomic_rename=True,
            source_path_is_symlink=False,
            overwrite_attempted=False,
            observed_producer_identity="agora-evidence-source",
        )
        complete_records = {
            record["day"]: record
            for prefix in state["nonselected_complete_prefixes"]
            for record in prefix
        }
        complete_records.update(
            {record["day"]: record for record in state["current_streak"]}
        )
        if state["selected_streak"] is not None:
            complete_records.update(
                {record["day"]: record for record in state["selected_streak"]}
            )
        if (
            item.day.isoformat() not in complete_records
            or complete_records[item.day.isoformat()]["bundle_sha256"]
            != validated["bundle_sha256"]
        ):
            raise DiscoveryRecoveryBlocked(
                "CONFLICTING_DUPLICATE", "prior complete bundle hash changed"
            )
    else:
        if frozen.bundle_bytes is not None:
            raise DiscoveryRecoveryBlocked(
                "CONFLICTING_DUPLICATE", "prior rejection contains bundle bytes"
            )
        validated = validate_rejection_envelope(
            envelope,
            raw_bytes=frozen.envelope_bytes,
            binding_value=binding,
            expected_day=item.day,
            delivered_via_atomic_rename=True,
            source_path_is_symlink=False,
            overwrite_attempted=False,
            observed_producer_identity="agora-evidence-source",
        )
        if recorded["reason"] != validated["reason"]:
            raise DiscoveryRecoveryBlocked(
                "CONFLICTING_DUPLICATE", "prior rejection reason changed"
            )


def _block_from_error(
    state: dict[str, Any],
    binding: dict[str, Any],
    error: DiscoveryRecoveryBlocked,
) -> dict[str, Any]:
    code = error.code
    if code not in BLOCKING_FAILURE_CODES:
        code = (
            "MARKET_INTEGRITY_FAILURE"
            if code
            in {
                "INCOMPLETE_DAY",
                "STREAM_GAP",
                "INTEGRITY_NOT_CLEAN",
                "CROSSED_BOOK",
            }
            else "CONTRACT_HASH_MISMATCH"
        )
    return block_intake_state(
        state,
        binding_value=binding,
        code=code,
        detail=str(error)[:500],
    )


def _commit_state(
    state_path: Path, next_bytes: bytes, binding: dict[str, Any]
) -> None:
    load_canonical_intake_state_bytes(next_bytes, binding)
    directory = state_path.parent
    if not directory.is_dir() or directory.is_symlink():
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STATE_DIRECTORY")
    if state_path.is_symlink():
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STATE_SYMLINK")
    lock_path = _lock_path(state_path)
    temp_path = _temp_path(state_path)
    if os.path.lexists(temp_path):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STALE_TEMP")
    prior_hash: str | None = None
    if os.path.lexists(state_path):
        prior_bytes = _read_stable_regular_bytes(
            state_path, "RECOVERY_BLOCKED_STATE_FILE"
        )
        load_canonical_intake_state_bytes(prior_bytes, binding)
        prior_hash = hashlib.sha256(prior_bytes).hexdigest()
        if prior_bytes == next_bytes:
            return
    next_hash = hashlib.sha256(next_bytes).hexdigest()
    try:
        lock_path.mkdir(mode=0o700)
    except FileExistsError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_PRESENT") from error
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_CREATE") from error
    try:
        intent = {
            "schema_version": _COMMIT_INTENT_VERSION,
            "state_name": state_path.name,
            "prior_sha256": prior_hash,
            "next_sha256": next_hash,
        }
        intent_path = lock_path / "intent.json"
        with intent_path.open("xb") as stream:
            stream.write(canonical_json_bytes(intent))
            stream.flush()
            os.fsync(stream.fileno())
        _fsync_directory(lock_path)
        with temp_path.open("xb") as stream:
            stream.write(next_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, state_path)
        _fsync_directory(directory)
        intent_path.unlink()
        lock_path.rmdir()
        _fsync_directory(directory)
    except FileExistsError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_TEMP_PRESENT") from error
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_ATOMIC_COMMIT") from error


def _recover_interrupted_commit(
    state_path: Path, binding: dict[str, Any]
) -> None:
    lock_path = _lock_path(state_path)
    temp_path = _temp_path(state_path)
    lock_exists = os.path.lexists(lock_path)
    temp_exists = os.path.lexists(temp_path)
    if not lock_exists and not temp_exists:
        return
    if not lock_exists:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_STALE_TEMP")
    if lock_path.is_symlink() or not lock_path.is_dir():
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_SHAPE")
    try:
        children = list(os.scandir(lock_path))
    except OSError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_SHAPE") from error
    if not children:
        if temp_exists:
            raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_INTENT_MISSING")
        lock_path.rmdir()
        _fsync_directory(state_path.parent)
        return
    if (
        len(children) != 1
        or children[0].name != "intent.json"
        or children[0].is_symlink()
        or not children[0].is_file(follow_symlinks=False)
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_SHAPE")
    intent_path = Path(children[0].path)
    try:
        intent_bytes = _read_stable_regular_bytes(
            intent_path, "RECOVERY_BLOCKED_LOCK_INTENT"
        )
        intent = load_json_bytes_strict(intent_bytes, "V3R1 commit intent")
    except ContractViolation as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_INTENT") from error
    if (
        intent_bytes != canonical_json_bytes(intent)
        or set(intent)
        != {"schema_version", "state_name", "prior_sha256", "next_sha256"}
        or intent.get("schema_version") != _COMMIT_INTENT_VERSION
        or intent.get("state_name") != state_path.name
        or not isinstance(intent.get("next_sha256"), str)
        or _SHA256.fullmatch(intent["next_sha256"]) is None
        or (
            intent.get("prior_sha256") is not None
            and (
                not isinstance(intent["prior_sha256"], str)
                or _SHA256.fullmatch(intent["prior_sha256"]) is None
            )
        )
    ):
        raise IntakeCliBlocked("RECOVERY_BLOCKED_LOCK_INTENT")
    current_hash: str | None = None
    if os.path.lexists(state_path):
        current_bytes = _read_stable_regular_bytes(
            state_path, "RECOVERY_BLOCKED_STATE_FILE"
        )
        load_canonical_intake_state_bytes(current_bytes, binding)
        current_hash = hashlib.sha256(current_bytes).hexdigest()
    temp_hash: str | None = None
    if temp_exists:
        temp_bytes = _read_stable_regular_bytes(
            temp_path, "RECOVERY_BLOCKED_STALE_TEMP"
        )
        load_canonical_intake_state_bytes(temp_bytes, binding)
        temp_hash = hashlib.sha256(temp_bytes).hexdigest()
        if temp_hash != intent["next_sha256"]:
            raise IntakeCliBlocked("RECOVERY_BLOCKED_COMMIT_HASH_DRIFT")
    if current_hash == intent["next_sha256"]:
        if temp_exists:
            temp_path.unlink()
        intent_path.unlink()
        lock_path.rmdir()
        _fsync_directory(state_path.parent)
        return
    if current_hash == intent["prior_sha256"]:
        if temp_exists:
            os.replace(temp_path, state_path)
            _fsync_directory(state_path.parent)
            current_bytes = _read_stable_regular_bytes(
                state_path, "RECOVERY_BLOCKED_STATE_FILE"
            )
            if hashlib.sha256(current_bytes).hexdigest() != intent["next_sha256"]:
                raise IntakeCliBlocked("RECOVERY_BLOCKED_COMMIT_HASH_DRIFT")
        intent_path.unlink()
        lock_path.rmdir()
        _fsync_directory(state_path.parent)
        return
    raise IntakeCliBlocked("RECOVERY_BLOCKED_COMMIT_HASH_DRIFT")


def _load_document(raw_bytes: bytes, label: str) -> dict[str, Any]:
    try:
        value = load_json_bytes_strict(raw_bytes, label)
    except ContractViolation as error:
        raise DiscoveryRecoveryBlocked(error.code, str(error)) from error
    if raw_bytes != canonical_json_bytes(value):
        raise DiscoveryRecoveryBlocked(
            "CONTRACT_HASH_MISMATCH", f"{label} bytes are not canonical"
        )
    return value


def _read_stable_regular_bytes(path: Path, code: str) -> bytes:
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode) or path.is_symlink():
            raise IntakeCliBlocked(code)
        raw = path.read_bytes()
        after = path.lstat()
    except IntakeCliBlocked:
        raise
    except OSError as error:
        raise IntakeCliBlocked(code) from error
    if (
        not stat.S_ISREG(after.st_mode)
        or path.is_symlink()
        or before.st_dev != after.st_dev
        or before.st_ino != after.st_ino
        or before.st_size != after.st_size
        or after.st_size != len(raw)
        or before.st_mtime_ns != after.st_mtime_ns
    ):
        raise IntakeCliBlocked(code)
    return raw


def _file_digest(path: Path) -> str:
    return hashlib.sha256(
        _read_stable_regular_bytes(path, "RECOVERY_BLOCKED_UNSTABLE_FILE")
    ).hexdigest()


def _parse_day(value: str) -> date:
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DAY_NAME") from error
    if parsed.isoformat() != value:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_DAY_NAME")
    return parsed


def _utc_now(clock: Clock | None) -> datetime:
    now = datetime.now(timezone.utc) if clock is None else clock()
    if now.tzinfo is None or now.utcoffset() is None:
        raise IntakeCliBlocked("RECOVERY_BLOCKED_CLOCK")
    return now.astimezone(timezone.utc)


def _lock_path(state_path: Path) -> Path:
    return state_path.with_name(f".{state_path.name}.lock")


def _temp_path(state_path: Path) -> Path:
    return state_path.with_name(f".{state_path.name}.tmp")


def _fsync_directory(directory: Path) -> None:
    if os.name == "nt":
        return
    descriptor = os.open(directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


if __name__ == "__main__":
    raise SystemExit(main())
