from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import re
import stat
from typing import Any

from .microstructure_intake import load_canonical_v3_state_bytes
from .microstructure_source_contract import ContractViolation


_STATE_NAME = re.compile(r"^[a-z0-9][a-z0-9-]{2,79}\.json$")
_RECOVERY_MARKER = re.compile(
    r"^\.[a-z0-9][a-z0-9-]{2,79}\.json\.(?:lock|tmp)$"
)
_MAX_NAMESPACE_ENTRIES = 3


def microstructure_diagnostic_status(
    state_root: Path,
    *,
    now: datetime,
) -> dict[str, Any]:
    """Read one canonical microstructure intake state without changing it."""

    if now.tzinfo is None:
        raise ValueError("microstructure monitor time must be timezone-aware")
    root = Path(state_root)
    namespace = root / "microstructure-v3"
    if not os.path.lexists(namespace):
        return _summary("RECOVERY_BLOCKED", "RECOVERY_BLOCKED")
    if namespace.is_symlink() or not namespace.is_dir():
        return _summary("RECOVERY_BLOCKED", "RECOVERY_BLOCKED")

    try:
        before = _namespace_snapshot(namespace)
    except OSError:
        return _summary("RECOVERY_BLOCKED", "RECOVERY_BLOCKED")
    if not before or len(before) > _MAX_NAMESPACE_ENTRIES:
        return _summary("RECOVERY_BLOCKED", "RECOVERY_BLOCKED")

    state_names = [
        name
        for name, kind in before
        if kind == "file" and _STATE_NAME.fullmatch(name) is not None
    ]
    if len(state_names) != 1:
        return _summary("RECOVERY_BLOCKED", "RECOVERY_BLOCKED")

    state_path = namespace / state_names[0]
    try:
        raw_bytes = _read_stable_regular_bytes(state_path)
        state = load_canonical_v3_state_bytes(raw_bytes)
        if state_path.name != f"{state['diagnostic_id']}.json":
            raise ValueError("microstructure state filename does not match diagnostic_id")
        after = _namespace_snapshot(namespace)
    except (ContractViolation, OSError, ValueError):
        return _summary("RECOVERY_BLOCKED", "RECOVERY_BLOCKED")

    artifact_path = _relative_artifact_path(root, state_path)
    artifact_sha256 = hashlib.sha256(raw_bytes).hexdigest()
    base = {
        "diagnostic_id": state["diagnostic_id"],
        "start_day": state["start_day"],
        "accepted_day_count": len(state["accepted_days"]),
        "required_day_count": state["required_day_count"],
        "next_expected_day": state["next_expected_day"],
        "artifact_path": artifact_path,
        "sha256": artifact_sha256,
    }

    if before != after or any(
        kind != "file"
        or _STATE_NAME.fullmatch(name) is None
        or _RECOVERY_MARKER.fullmatch(name) is not None
        for name, kind in after
    ):
        return _summary(
            "RECOVERY_BLOCKED",
            "RECOVERY_BLOCKED",
            **base,
        )

    state_status = str(state["status"])
    if state_status == "DIAGNOSTIC_READY":
        return _summary("DIAGNOSTIC_READY", "COMPLETE", **base)
    if state_status == "INTEGRITY_BLOCKED":
        return _summary("INTEGRITY_BLOCKED", "INTEGRITY_BLOCKED", **base)

    today = now.astimezone(timezone.utc).date()
    start_day = datetime.strptime(str(state["start_day"]), "%Y-%m-%d").date()
    next_expected = datetime.strptime(
        str(state["next_expected_day"]), "%Y-%m-%d"
    ).date()
    if today < start_day:
        return _summary("WAITING_FOR_DAY", "PRE_START", **base)
    if next_expected < today:
        return _summary("CAPTURE_OVERDUE", "OVERDUE_UTC_DAY", **base)
    if next_expected == today:
        return _summary("WAITING_FOR_DAY", "CURRENT_UTC_DAY", **base)
    return _summary("WAITING_FOR_DAY", "FUTURE_UTC_DAY", **base)


def _namespace_snapshot(namespace: Path) -> tuple[tuple[str, str], ...]:
    entries: list[tuple[str, str]] = []
    with os.scandir(namespace) as scan:
        for entry in scan:
            if entry.is_symlink():
                kind = "symlink"
            elif entry.is_file(follow_symlinks=False):
                kind = "file"
            elif entry.is_dir(follow_symlinks=False):
                kind = "directory"
            else:
                kind = "other"
            entries.append((entry.name, kind))
    return tuple(sorted(entries))


def _read_stable_regular_bytes(path: Path) -> bytes:
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or path.is_symlink():
        raise ValueError("microstructure state is not a regular non-symlink file")
    raw_bytes = path.read_bytes()
    after = path.lstat()
    if (
        not stat.S_ISREG(after.st_mode)
        or path.is_symlink()
        or before.st_dev != after.st_dev
        or before.st_ino != after.st_ino
        or before.st_size != after.st_size
        or after.st_size != len(raw_bytes)
        or before.st_mtime_ns != after.st_mtime_ns
    ):
        raise ValueError("microstructure state changed while being observed")
    return raw_bytes


def _relative_artifact_path(root: Path, path: Path) -> str:
    resolved_root = root.resolve()
    resolved_path = path.resolve(strict=True)
    try:
        relative = resolved_path.relative_to(resolved_root)
    except ValueError as error:
        raise ValueError("microstructure state escapes canonical state root") from error
    return str(relative).replace("\\", "/")


def _summary(
    status: str,
    lag_classification: str,
    *,
    diagnostic_id: str | None = None,
    start_day: str | None = None,
    accepted_day_count: int | None = None,
    required_day_count: int | None = None,
    next_expected_day: str | None = None,
    artifact_path: str | None = None,
    sha256: str | None = None,
) -> dict[str, Any]:
    return {
        "status": status,
        "diagnostic_id": diagnostic_id,
        "start_day": start_day,
        "accepted_day_count": accepted_day_count,
        "required_day_count": required_day_count,
        "next_expected_day": next_expected_day,
        "artifact_path": artifact_path,
        "sha256": sha256,
        "lag_classification": lag_classification,
    }
