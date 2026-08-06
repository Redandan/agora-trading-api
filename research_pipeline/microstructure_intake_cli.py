from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
from typing import Callable, Sequence

from .microstructure_intake import (
    ObservedDelivery,
    RecoveryBlocked,
    apply_observed_delivery,
    canonical_state_bytes,
    commit_canonical_state,
    initial_state_bytes,
    load_canonical_state_bytes,
    state_lock_path,
    state_temp_path,
)
from .microstructure_source_contract import (
    AUTHORIZATION,
    DAY_SCHEMA_SHA256,
    DIAGNOSTIC_CONTRACT_SHA256,
    DROP_ENVELOPE_SCHEMA_SHA256,
    REQUIRED_DAYS,
    SOURCE_CONTRACT_SHA256,
    ContractViolation,
    block_intake_state,
    canonical_json_bytes,
    load_json_bytes_strict,
)


BINDING_PATH = Path(
    "/etc/agora-research/okx-microstructure-continuous-source-v1.json"
)
DROP_ROOT = Path("/var/lib/agora-evidence-source/microstructure-drop")
STAGING_ROOT = Path(
    "/var/lib/agora-evidence-source/microstructure-private-staging"
)
STATE_ROOT = Path("/var/lib/agora-research/state/microstructure")
CURRENT_RELEASE = Path("/opt/agora-research-worker/current")
MINIMUM_FREE_BYTES = 2 * 1024 * 1024 * 1024

_BINDING_KEYS = {
    "schema_version",
    "authorization",
    "forward_start_day",
    "required_complete_utc_days",
    "diagnostic_id",
    "source_contract_sha256",
    "day_schema_sha256",
    "diagnostic_contract_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
}
_DIAGNOSTIC_ID = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}$")
_RELEASE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DAY_NAME = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_RESERVATION_NAME = re.compile(r"^\.(\d{4}-\d{2}-\d{2})\.publish-reserved$")
_TERMINAL_STATES = {"DIAGNOSTIC_READY", "INTEGRITY_BLOCKED"}

_OBSERVED_DELIVERY = ObservedDelivery(
    intake_identity="agora-research",
    network_access="DENY",
    producer_identity="agora-evidence-source",
    delivered_via_atomic_rename=True,
    source_path_is_symlink=False,
    overwrite_attempted=False,
    historical_backfill_requested=False,
    candle_chain_reuse_requested=False,
    research_lifecycle_action_requested=False,
)


@dataclass(frozen=True, slots=True)
class RuntimePaths:
    binding: Path
    drop_root: Path
    staging_root: Path
    state_root: Path
    release: Path


@dataclass(frozen=True, slots=True)
class Binding:
    forward_start_day: date
    diagnostic_id: str
    producer_release_id: str
    producer_manifest_sha256: str


@dataclass(frozen=True, slots=True)
class PublishedDay:
    day: date
    directory: Path
    bundle: Path
    envelope: Path
    reservation: Path | None


Freezer = Callable[[PublishedDay], tuple[bytes, bytes]]
Clock = Callable[[], datetime]
FreeBytes = Callable[[Path], int]
DeviceId = Callable[[Path], int]


def fixed_runtime_paths() -> RuntimePaths:
    try:
        release = CURRENT_RELEASE.resolve(strict=True)
    except OSError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_INSTALLED_RELEASE") from error
    expected_parent = Path("/opt/agora-research-worker/releases")
    if release.parent != expected_parent or not release.is_dir() or release.is_symlink():
        raise RecoveryBlocked("RECOVERY_BLOCKED_INSTALLED_RELEASE")
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
        raise RecoveryBlocked("RECOVERY_BLOCKED_COMMAND")
    utc_now = _utc_now(clock)
    binding = _load_binding(paths, require_future=command == "initialize", today=utc_now.date())
    state_path = _state_path(paths.state_root, binding.diagnostic_id)

    if command == "initialize":
        _storage_gates(paths, free_bytes=free_bytes, device_id=device_id)
        _scan_drop(paths.drop_root, binding)
        if os.path.lexists(state_path):
            state = _load_matching_state(state_path, binding)
            return str(state["status"])
        state_bytes = initial_state_bytes(
            binding.diagnostic_id,
            binding.forward_start_day,
            as_of_day=utc_now.date(),
        )
        commit_canonical_state(state_path, state_bytes)
        return "WAITING_FOR_DAY"

    if not os.path.lexists(state_path):
        raise RecoveryBlocked("RECOVERY_BLOCKED_STATE_MISSING")
    state = _load_matching_state(state_path, binding)
    if state["status"] in _TERMINAL_STATES:
        return str(state["status"])

    _storage_gates(paths, free_bytes=free_bytes, device_id=device_id)
    published_days = _scan_drop(paths.drop_root, binding)
    accepted_at = _accepted_at(utc_now, state)
    freeze = freezer or freeze_published_day
    state_bytes = state_path.read_bytes()
    disposition = str(state["status"])

    for published in published_days:
        bundle_bytes, envelope_bytes = freeze(published)
        if not _envelope_matches_binding(envelope_bytes, binding):
            blocked = block_intake_state(
                load_canonical_state_bytes(state_bytes),
                code="CONTRACT_HASH_MISMATCH",
                day=published.day,
                detail="drop envelope producer release identity does not match binding",
            )
            next_bytes = canonical_state_bytes(blocked)
            commit_canonical_state(state_path, next_bytes)
            state_bytes = next_bytes
            disposition = "INTEGRITY_BLOCKED"
            break
        result = apply_observed_delivery(
            state_bytes,
            envelope_bytes,
            bundle_bytes,
            observed=_OBSERVED_DELIVERY,
            accepted_at=accepted_at,
        )
        if result.state_bytes != state_bytes:
            commit_canonical_state(state_path, result.state_bytes)
            state_bytes = result.state_bytes
        disposition = result.disposition
        current = load_canonical_state_bytes(state_bytes)
        if current["status"] in _TERMINAL_STATES:
            break
    return disposition


def validate_existing_installation(*, paths: RuntimePaths) -> str:
    binding = _load_binding(
        paths,
        require_future=False,
        today=datetime.now(timezone.utc).date(),
    )
    state = _load_matching_state(
        _state_path(paths.state_root, binding.diagnostic_id), binding
    )
    return str(state["status"])


def freeze_published_day(published: PublishedDay) -> tuple[bytes, bytes]:
    if os.name == "nt":
        raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_PLATFORM")
    try:
        import grp
        import pwd

        root_uid = pwd.getpwnam("root").pw_uid
        research_gid = grp.getgrnam("agora-research").gr_gid
    except (KeyError, OSError) as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_IDENTITY") from error

    files = [published.bundle, published.envelope]
    if published.reservation is not None:
        files.append(published.reservation)
    targets = _metadata_targets(published)
    protected_count = 2 if published.reservation is not None else 1
    _apply_metadata_entries(
        targets[:protected_count], root_uid=root_uid, research_gid=research_gid
    )
    before = {path: _file_digest(path) for path in files}
    _apply_metadata_entries(
        targets[protected_count:], root_uid=root_uid, research_gid=research_gid
    )

    for path in files:
        details = path.lstat()
        if (
            not stat.S_ISREG(details.st_mode)
            or details.st_uid != root_uid
            or details.st_gid != research_gid
            or stat.S_IMODE(details.st_mode) != 0o440
            or _file_digest(path) != before[path]
        ):
            raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
    directory_details = published.directory.lstat()
    if (
        not stat.S_ISDIR(directory_details.st_mode)
        or directory_details.st_uid != root_uid
        or directory_details.st_gid != research_gid
        or stat.S_IMODE(directory_details.st_mode) != 0o550
    ):
        raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
    return published.bundle.read_bytes(), published.envelope.read_bytes()


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if len(arguments) != 1 or arguments[0] not in {"initialize", "ingest"}:
        print("usage: python -m research_pipeline.microstructure_intake_cli {initialize|ingest}", file=sys.stderr)
        return 2
    try:
        status = run(arguments[0], paths=fixed_runtime_paths())
    except (ContractViolation, RecoveryBlocked, OSError, ValueError) as error:
        print(str(error) or error.__class__.__name__, file=sys.stderr)
        return 2
    print(json.dumps({"status": status}, separators=(",", ":"), sort_keys=True))
    return 0


def _load_binding(paths: RuntimePaths, *, require_future: bool, today: date) -> Binding:
    raw = _read_regular_bytes(paths.binding, "RECOVERY_BLOCKED_BINDING")
    value = load_json_bytes_strict(raw, "microstructure binding")
    if raw != canonical_json_bytes(value) or set(value) != _BINDING_KEYS:
        raise RecoveryBlocked("RECOVERY_BLOCKED_BINDING")
    expected = {
        "schema_version": "1",
        "authorization": AUTHORIZATION,
        "required_complete_utc_days": REQUIRED_DAYS,
        "source_contract_sha256": SOURCE_CONTRACT_SHA256,
        "day_schema_sha256": DAY_SCHEMA_SHA256,
        "diagnostic_contract_sha256": DIAGNOSTIC_CONTRACT_SHA256,
    }
    if any(value.get(key) != expected_value for key, expected_value in expected.items()):
        raise RecoveryBlocked("RECOVERY_BLOCKED_BINDING")

    diagnostic_id = value.get("diagnostic_id")
    release_id = value.get("producer_release_id")
    manifest_hash = value.get("producer_manifest_sha256")
    if not isinstance(diagnostic_id, str) or _DIAGNOSTIC_ID.fullmatch(diagnostic_id) is None:
        raise RecoveryBlocked("RECOVERY_BLOCKED_BINDING")
    if not isinstance(release_id, str) or _RELEASE_ID.fullmatch(release_id) is None:
        raise RecoveryBlocked("RECOVERY_BLOCKED_BINDING")
    if not isinstance(manifest_hash, str) or _SHA256.fullmatch(manifest_hash) is None:
        raise RecoveryBlocked("RECOVERY_BLOCKED_BINDING")
    try:
        start_day = date.fromisoformat(value["forward_start_day"])
    except (KeyError, TypeError, ValueError) as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_BINDING") from error
    if require_future and start_day <= today:
        raise RecoveryBlocked("RECOVERY_BLOCKED_NONFUTURE_START")

    release = paths.release
    if not release.is_dir() or release.is_symlink() or release.name != release_id:
        raise RecoveryBlocked("RECOVERY_BLOCKED_RELEASE_IDENTITY")
    manifest = release / ".release" / "source.sha256"
    provenance_path = release / ".release" / "provenance.json"
    manifest_bytes = _read_regular_bytes(manifest, "RECOVERY_BLOCKED_RELEASE_MANIFEST")
    if hashlib.sha256(manifest_bytes).hexdigest() != manifest_hash:
        raise RecoveryBlocked("RECOVERY_BLOCKED_RELEASE_MANIFEST")
    provenance = load_json_bytes_strict(
        _read_regular_bytes(provenance_path, "RECOVERY_BLOCKED_RELEASE_PROVENANCE"),
        "release provenance",
    )
    if (
        provenance.get("release_id") != release_id
        or provenance.get("source_manifest_sha256") != manifest_hash
    ):
        raise RecoveryBlocked("RECOVERY_BLOCKED_RELEASE_PROVENANCE")
    return Binding(start_day, diagnostic_id, release_id, manifest_hash)


def _state_path(state_root: Path, diagnostic_id: str) -> Path:
    if _DIAGNOSTIC_ID.fullmatch(diagnostic_id) is None:
        raise RecoveryBlocked("RECOVERY_BLOCKED_DIAGNOSTIC_ID")
    if not state_root.is_dir() or state_root.is_symlink():
        raise RecoveryBlocked("RECOVERY_BLOCKED_STATE_DIRECTORY")
    return state_root / f"{diagnostic_id}.json"


def _load_matching_state(state_path: Path, binding: Binding) -> dict[str, object]:
    if state_path.is_symlink() or not state_path.is_file():
        raise RecoveryBlocked("RECOVERY_BLOCKED_STATE_FILE")
    if os.path.lexists(state_lock_path(state_path)):
        raise RecoveryBlocked("RECOVERY_BLOCKED_LOCK_PRESENT")
    if os.path.lexists(state_temp_path(state_path)):
        raise RecoveryBlocked("RECOVERY_BLOCKED_STALE_TEMP")
    state = load_canonical_state_bytes(state_path.read_bytes())
    if (
        state["diagnostic_id"] != binding.diagnostic_id
        or state["start_day"] != binding.forward_start_day.isoformat()
        or state["source_contract_sha256"] != SOURCE_CONTRACT_SHA256
        or state["drop_envelope_schema_sha256"] != DROP_ENVELOPE_SCHEMA_SHA256
        or state["day_schema_sha256"] != DAY_SCHEMA_SHA256
        or state["diagnostic_contract_sha256"] != DIAGNOSTIC_CONTRACT_SHA256
    ):
        raise RecoveryBlocked("RECOVERY_BLOCKED_STATE_BINDING")
    return state


def _storage_gates(
    paths: RuntimePaths,
    *,
    free_bytes: FreeBytes | None,
    device_id: DeviceId | None,
) -> None:
    for root in (paths.staging_root, paths.drop_root):
        if not root.is_dir() or root.is_symlink():
            raise RecoveryBlocked("RECOVERY_BLOCKED_STORAGE_ROOT")
    free = (free_bytes or (lambda path: shutil.disk_usage(path).free))(paths.drop_root)
    if free < MINIMUM_FREE_BYTES:
        raise RecoveryBlocked("RECOVERY_BLOCKED_CAPACITY")
    device = device_id or (lambda path: path.stat().st_dev)
    if device(paths.staging_root) != device(paths.drop_root):
        raise RecoveryBlocked("RECOVERY_BLOCKED_FILESYSTEM")


def _scan_drop(drop_root: Path, binding: Binding) -> list[PublishedDay]:
    try:
        entries = sorted(os.scandir(drop_root), key=lambda entry: entry.name)
    except OSError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_DROP_SCAN") from error
    day_entries: dict[date, Path] = {}
    reservations: dict[date, Path] = {}
    for entry in entries:
        if entry.is_symlink():
            raise RecoveryBlocked("RECOVERY_BLOCKED_DROP_SYMLINK")
        day_match = _DAY_NAME.fullmatch(entry.name)
        reservation_match = _RESERVATION_NAME.fullmatch(entry.name)
        if day_match is not None and entry.is_dir(follow_symlinks=False):
            parsed_day = _parse_day(entry.name)
            day_entries[parsed_day] = Path(entry.path)
        elif reservation_match is not None and entry.is_file(follow_symlinks=False):
            parsed_day = _parse_day(reservation_match.group(1))
            if entry.stat(follow_symlinks=False).st_size != 0:
                raise RecoveryBlocked("RECOVERY_BLOCKED_RESERVATION")
            reservations[parsed_day] = Path(entry.path)
        else:
            raise RecoveryBlocked("RECOVERY_BLOCKED_DROP_ENTRY")

    if len(day_entries) > REQUIRED_DAYS or len(reservations) > REQUIRED_DAYS:
        raise RecoveryBlocked("RECOVERY_BLOCKED_ENTRY_BOUND")
    if set(reservations) != set(day_entries):
        raise RecoveryBlocked("RECOVERY_BLOCKED_UNMATCHED_RESERVATION")
    permitted = {
        binding.forward_start_day + timedelta(days=index)
        for index in range(REQUIRED_DAYS)
    }
    if not set(day_entries).issubset(permitted) or not set(reservations).issubset(permitted):
        raise RecoveryBlocked("RECOVERY_BLOCKED_STALE_ENTRY")

    published: list[PublishedDay] = []
    for day in sorted(day_entries):
        directory = day_entries[day]
        expected_bundle = f"okx-btc-usdt-microstructure-{day.isoformat()}.json"
        expected_envelope = (
            f"okx-btc-usdt-microstructure-{day.isoformat()}.envelope.json"
        )
        try:
            children = sorted(os.scandir(directory), key=lambda entry: entry.name)
        except OSError as error:
            raise RecoveryBlocked("RECOVERY_BLOCKED_DAY_SCAN") from error
        if {child.name for child in children} != {expected_bundle, expected_envelope}:
            raise RecoveryBlocked("RECOVERY_BLOCKED_DAY_SHAPE")
        child_by_name = {child.name: child for child in children}
        if any(
            child.is_symlink() or not child.is_file(follow_symlinks=False)
            for child in children
        ):
            raise RecoveryBlocked("RECOVERY_BLOCKED_DAY_FILE")
        published.append(
            PublishedDay(
                day=day,
                directory=directory,
                bundle=Path(child_by_name[expected_bundle].path),
                envelope=Path(child_by_name[expected_envelope].path),
                reservation=reservations.get(day),
            )
        )
    return published


def _accepted_at(now: datetime, state: dict[str, object]) -> str:
    accepted_days = state["accepted_days"]
    if isinstance(accepted_days, list) and accepted_days:
        prior = datetime.fromisoformat(
            str(accepted_days[-1]["accepted_at"]).replace("Z", "+00:00")
        )
        if now < prior:
            raise RecoveryBlocked("RECOVERY_BLOCKED_CLOCK_REGRESSION")
    return now.isoformat().replace("+00:00", "Z")


def _envelope_matches_binding(raw_bytes: bytes, binding: Binding) -> bool:
    try:
        envelope = load_json_bytes_strict(raw_bytes, "drop envelope")
    except ContractViolation:
        return True
    return (
        envelope.get("producer_release_id") == binding.producer_release_id
        and envelope.get("producer_manifest_sha256")
        == binding.producer_manifest_sha256
    )


def _metadata_targets(published: PublishedDay) -> tuple[tuple[Path, int], ...]:
    targets: list[tuple[Path, int]] = [(published.directory, 0o550)]
    if published.reservation is not None:
        targets.append((published.reservation, 0o440))
    targets.extend(((published.bundle, 0o440), (published.envelope, 0o440)))
    return tuple(targets)


def _apply_metadata_freeze(
    published: PublishedDay,
    *,
    root_uid: int,
    research_gid: int,
    lstat: Callable[[Path], os.stat_result] | None = None,
    chown: Callable[[Path, int, int], None] | None = None,
    chmod: Callable[[Path, int], None] | None = None,
) -> None:
    _apply_metadata_entries(
        _metadata_targets(published),
        root_uid=root_uid,
        research_gid=research_gid,
        lstat=lstat,
        chown=chown,
        chmod=chmod,
    )


def _apply_metadata_entries(
    targets: tuple[tuple[Path, int], ...],
    *,
    root_uid: int,
    research_gid: int,
    lstat: Callable[[Path], os.stat_result] | None = None,
    chown: Callable[[Path, int, int], None] | None = None,
    chmod: Callable[[Path, int], None] | None = None,
) -> None:
    if lstat is None and chown is None and chmod is None:
        _apply_metadata_entries_fd(
            targets, root_uid=root_uid, research_gid=research_gid
        )
        return
    inspect = lstat or (lambda path: path.lstat())
    change_owner = chown or (
        lambda path, uid, gid: os.chown(
            path, uid, gid, follow_symlinks=False
        )
    )
    change_mode = chmod or (
        lambda path, mode: os.chmod(path, mode)
    )
    try:
        for path, mode in targets:
            details = inspect(path)
            expected_type = stat.S_ISDIR if mode == 0o550 else stat.S_ISREG
            if not expected_type(details.st_mode):
                raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
            if (
                details.st_uid == root_uid
                and details.st_gid == research_gid
                and stat.S_IMODE(details.st_mode) == mode
            ):
                continue
            change_owner(path, root_uid, research_gid)
            change_mode(path, mode)
    except RecoveryBlocked:
        raise
    except (OSError, NotImplementedError) as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_FREEZE") from error


def _apply_metadata_entries_fd(
    targets: tuple[tuple[Path, int], ...],
    *,
    root_uid: int,
    research_gid: int,
) -> None:
    no_follow = getattr(os, "O_NOFOLLOW", None)
    directory_flag = getattr(os, "O_DIRECTORY", None)
    if no_follow is None or directory_flag is None:
        raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_PLATFORM")
    try:
        for path, mode in targets:
            flags = os.O_RDONLY | no_follow
            if mode == 0o550:
                flags |= directory_flag
            descriptor = os.open(path, flags)
            try:
                details = os.fstat(descriptor)
                expected_type = stat.S_ISDIR if mode == 0o550 else stat.S_ISREG
                if not expected_type(details.st_mode):
                    raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_FREEZE")
                if (
                    details.st_uid == root_uid
                    and details.st_gid == research_gid
                    and stat.S_IMODE(details.st_mode) == mode
                ):
                    continue
                os.fchown(descriptor, root_uid, research_gid)
                os.fchmod(descriptor, mode)
            finally:
                os.close(descriptor)
    except RecoveryBlocked:
        raise
    except (AttributeError, OSError, NotImplementedError) as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_METADATA_FREEZE") from error


def _utc_now(clock: Clock | None) -> datetime:
    value = (clock or (lambda: datetime.now(timezone.utc)))()
    if value.tzinfo is None:
        raise RecoveryBlocked("RECOVERY_BLOCKED_CLOCK")
    return value.astimezone(timezone.utc)


def _parse_day(value: str) -> date:
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_DAY_NAME") from error
    if parsed.isoformat() != value:
        raise RecoveryBlocked("RECOVERY_BLOCKED_DAY_NAME")
    return parsed


def _read_regular_bytes(path: Path, code: str) -> bytes:
    try:
        if path.is_symlink() or not path.is_file():
            raise RecoveryBlocked(code)
        return path.read_bytes()
    except RecoveryBlocked:
        raise
    except OSError as error:
        raise RecoveryBlocked(code) from error


def _file_digest(path: Path) -> str:
    return hashlib.sha256(_read_regular_bytes(path, "RECOVERY_BLOCKED_EVIDENCE_READ")).hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
