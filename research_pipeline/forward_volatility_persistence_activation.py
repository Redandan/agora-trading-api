from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .forward_trigger_lineage import (
    ActiveForwardTriggerLineage,
    ROOT_TRIGGER_FINGERPRINT,
    ROOT_TRIGGER_ID,
    resolve_active_forward_trigger_lineage,
)
from .forward_volatility_persistence import (
    ACCEPTED_TASK_ID,
    ACCEPTED_TASK_SHA256,
    ACTIVATION_DOCUMENT_TYPE,
    _validate_activation_receipt,
)
from .models import RESEARCH_AUTHORIZATION, parse_timestamp
from .storage import ResearchStore


ACTIVATION_STATE_KEY = "btc_utc_day_3pct_forward_volatility_persistence_activation"
ACTIVATION_RECEIPT_RETIRED = (
    "ACTIVATION_RECEIPT_RETIRED_BY_LAWFUL_ROLLOVER"
)
ACCEPTED_IMPLEMENTATION_COMMIT = "5f4040de9e6a90864f4dc92477e5e377787d6a62"
ACCEPTED_RESULT_SHA256 = (
    "d77880b64c3b21a3786e1bc398ffafde2258471f3e9fa85a589aec1d92db11e2"
)
EVALUATOR_SCHEMA_SHA256 = (
    "0562230e380f81082c3f4e57f2ce3b6fbee7e0af89d3f01019e1c5aa7e320352"
)
EVALUATOR_MODULE_SHA256 = (
    "070b8354599d570c1ddfacdfcbf07c82d1108f4c4c0570d20b9c0fdffd38fdc3"
)
DEFAULT_WORKER_ROOT = Path("/opt/agora-research-worker")
EVALUATOR_SCHEMA_RELATIVE = Path(
    "research_pipeline/btc-utc-day-3pct-forward-volatility-persistence.v1.schema.json"
)
EVALUATOR_MODULE_RELATIVE = Path(
    "research_pipeline/forward_volatility_persistence.py"
)
ACTIVATION_MODULE_RELATIVE = Path(
    "research_pipeline/forward_volatility_persistence_activation.py"
)
HEARTBEAT_MODULE_RELATIVE = Path("research_pipeline/heartbeat.py")
ACCEPTED_RESULT_RELATIVE = Path(
    "research_pipeline/examples/"
    "local-research-result.btc-utc-day-3pct-forward-volatility-persistence-"
    "evaluator-acceptance-recovery.v1.json"
)

_HEX40 = re.compile(r"^[0-9a-f]{40}$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_RELEASE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_MANIFEST_LINE = re.compile(r"^([0-9a-f]{64})  ([^\r\n]+)$")
_PROVENANCE_KEYS = {
    "schema_version",
    "release_id",
    "source_git_commit",
    "source_git_branch",
    "source_git_dirty",
    "source_manifest_sha256",
    "installed_at",
}


class ActivationIntegrityError(ValueError):
    """The activation lineage, receipt, or immutable release conflicts."""


class _ActivationUnavailable(RuntimeError):
    """A not-yet-installed release prerequisite is absent."""


@dataclass(frozen=True)
class ActivationDecision:
    receipt: dict[str, Any] | None
    created: bool
    status: str


@dataclass(frozen=True)
class _ReleaseIdentity:
    release_id: str
    source_commit: str
    manifest_sha256: str


def prepare_forward_volatility_persistence_activation(
    store: ResearchStore,
    *,
    now: datetime,
    previous_success: Any,
    existing_receipt: Any,
    worker_root: Path = DEFAULT_WORKER_ROOT,
    control_current: Path | None = None,
    activation_module_path: Path | None = None,
    expected_root_uid: int = 0,
    enforce_posix_permissions: bool = os.name != "nt",
) -> ActivationDecision:
    """Create at most one release-bound receipt for the existing heartbeat writer.

    This function never writes canonical state.  Its caller already owns the
    ResearchStore lock and must atomically persist a newly returned receipt
    before invoking the evaluator.
    """

    current = _aware_utc(now, "activation now")
    lineage = resolve_active_forward_trigger_lineage(store)

    if existing_receipt is not None:
        if lineage is None:
            raise ActivationIntegrityError(
                "volatility activation receipt exists without forward lineage"
            )
        validated = _validated_existing_receipt(existing_receipt, current=current)
        matches_current_leaf = (
            validated["leaf_trigger_id"] == lineage.leaf_trigger["trigger_id"]
            and validated["leaf_trigger_fingerprint"]
            == lineage.leaf_trigger["fingerprint"]
        )
        if not matches_current_leaf and not _receipt_binds_verified_ancestor(
            lineage, validated
        ):
            raise ActivationIntegrityError(
                "volatility activation receipt conflicts with current lineage"
            )
        bound_created_at = _receipt_bound_created_at(lineage, validated)
        activated_at = parse_timestamp(
            validated["activated_at"], "activation activated_at"
        ).astimezone(timezone.utc)
        if activated_at <= bound_created_at:
            raise ActivationIntegrityError(
                "volatility activation receipt predates bound successor observation"
            )
        try:
            _require_release_identity(
                worker_root=Path(worker_root),
                control_current=control_current,
                activation_module_path=activation_module_path,
                expected_root_uid=expected_root_uid,
                enforce_posix_permissions=enforce_posix_permissions,
                unavailable_is_dormant=False,
                current=current,
            )
        except _ActivationUnavailable as error:
            raise ActivationIntegrityError(
                "receipt-bound release metadata is absent"
            ) from error
        if not matches_current_leaf:
            # Preserve the immutable receipt for its original verified leaf, but
            # never let it authorize evidence on a later lawful rollover leaf.
            # Reactivation requires a separately versioned receipt.
            return ActivationDecision(
                validated, False, ACTIVATION_RECEIPT_RETIRED
            )
        # The receipt preserves the immutable release that first activated the
        # evaluator.  A later clean Worker release is allowed to carry that
        # receipt forward only after the current immutable release independently
        # proves the same frozen evaluator schema/module and accepted result.
        # Rebinding the receipt to the new release would rewrite provenance;
        # requiring the historical release id to remain current would make every
        # lawful Worker upgrade an integrity incident.
        return ActivationDecision(validated, False, "ACTIVATION_RECEIPT_REVALIDATED")

    if lineage is None or not lineage.rolled_over:
        return ActivationDecision(None, False, "ACTIVATION_DORMANT_AWAITING_ROLLOVER")

    closed_at = _eligible_successor_closed_at(lineage)
    if previous_success is None:
        return ActivationDecision(
            None, False, "ACTIVATION_DORMANT_AWAITING_POST_ROLLOVER_HEARTBEAT"
        )
    prior = _parse_canonical_timestamp(previous_success, "heartbeat last_success")
    if prior < closed_at or current == prior:
        return ActivationDecision(
            None, False, "ACTIVATION_DORMANT_AWAITING_POST_ROLLOVER_HEARTBEAT"
        )
    if prior > current:
        raise ActivationIntegrityError("heartbeat last_success is in the future")

    try:
        release = _require_release_identity(
            worker_root=Path(worker_root),
            control_current=control_current,
            activation_module_path=activation_module_path,
            expected_root_uid=expected_root_uid,
            enforce_posix_permissions=enforce_posix_permissions,
            unavailable_is_dormant=True,
            current=current,
        )
    except _ActivationUnavailable:
        return ActivationDecision(
            None, False, "ACTIVATION_DORMANT_RELEASE_PROVENANCE_UNAVAILABLE"
        )

    receipt = _build_receipt(
        lineage=lineage,
        activated_at=_iso_utc(current),
        release=release,
    )
    try:
        validated = _validate_activation_receipt(receipt, current=current)
    except ValueError as error:
        raise ActivationIntegrityError(
            "new volatility activation receipt failed evaluator validation"
        ) from error
    return ActivationDecision(validated, True, "ACTIVATION_RECEIPT_READY_TO_PERSIST")


def _receipt_binds_verified_ancestor(
    lineage: ActiveForwardTriggerLineage, receipt: dict[str, Any]
) -> bool:
    receipt_id = receipt["leaf_trigger_id"]
    receipt_fingerprint = receipt["leaf_trigger_fingerprint"]
    return any(
        trigger_id == receipt_id and fingerprint == receipt_fingerprint
        for trigger_id, fingerprint, _ in lineage.trigger_identities[:-1]
    )


def _receipt_bound_created_at(
    lineage: ActiveForwardTriggerLineage, receipt: dict[str, Any]
) -> datetime:
    if not lineage.trigger_identities:
        if (
            receipt["leaf_trigger_id"] != lineage.leaf_trigger["trigger_id"]
            or receipt["leaf_trigger_fingerprint"]
            != lineage.leaf_trigger["fingerprint"]
        ):
            raise ActivationIntegrityError(
                "volatility activation receipt conflicts with current lineage"
            )
        return _eligible_successor_closed_at(lineage)
    receipt_id = receipt["leaf_trigger_id"]
    receipt_fingerprint = receipt["leaf_trigger_fingerprint"]
    matches = [
        created_at
        for trigger_id, fingerprint, created_at in lineage.trigger_identities
        if trigger_id == receipt_id and fingerprint == receipt_fingerprint
    ]
    if len(matches) != 1 or receipt_id == ROOT_TRIGGER_ID:
        raise ActivationIntegrityError(
            "volatility activation receipt bound leaf is not a unique successor"
        )
    return _parse_canonical_timestamp(matches[0], "receipt-bound leaf created_at")


def _eligible_successor_closed_at(lineage: ActiveForwardTriggerLineage) -> datetime:
    if not lineage.rolled_over:
        raise ActivationIntegrityError(
            "volatility activation receipt cannot bind the discovery root"
        )
    if len(lineage.trigger_ids) < 2:
        raise ActivationIntegrityError(
            "volatility activation successor lineage is incomplete"
        )
    # The lineage resolver has already verified every predecessor/successor
    # closure and prohibited forks.  Bind the latest active descendant so a
    # second lawful missed-window rollover cannot permanently brick an
    # outcome-neutral evaluator before its first activation.
    leaf_created_at = _parse_canonical_timestamp(
        lineage.leaf_trigger.get("created_at"), "successor created_at"
    )
    return leaf_created_at


def _validated_existing_receipt(
    value: Any, *, current: datetime
) -> dict[str, Any]:
    try:
        return _validate_activation_receipt(value, current=current)
    except ValueError as error:
        raise ActivationIntegrityError(
            "existing volatility activation receipt is invalid"
        ) from error


def _require_release_identity(
    *,
    worker_root: Path,
    control_current: Path | None,
    activation_module_path: Path | None,
    expected_root_uid: int,
    enforce_posix_permissions: bool,
    unavailable_is_dormant: bool,
    current: datetime,
) -> _ReleaseIdentity:
    control = (
        Path(control_current)
        if control_current is not None
        else _default_control_current(worker_root)
    )
    module = (
        Path(activation_module_path)
        if activation_module_path is not None
        else Path(__file__)
    )

    if not os.path.lexists(control):
        if unavailable_is_dormant:
            raise _ActivationUnavailable("control-current is absent")
        raise ActivationIntegrityError("receipt-bound control-current is absent")
    try:
        control_details = control.lstat()
    except OSError as error:
        raise ActivationIntegrityError("control-current cannot be inspected") from error
    if not stat.S_ISLNK(control_details.st_mode):
        raise ActivationIntegrityError("control-current is not a symlink")
    if control_details.st_uid != expected_root_uid:
        raise ActivationIntegrityError("control-current is not root-owned")

    try:
        resolved_module = module.resolve(strict=True)
        release = resolved_module.parents[1]
        control_target = control.resolve(strict=True)
        root = worker_root.resolve(strict=True)
    except (IndexError, OSError, RuntimeError) as error:
        raise ActivationIntegrityError("current release path cannot be resolved") from error
    releases = root / "releases"
    if release.parent != releases or control_target != release:
        raise ActivationIntegrityError(
            "running module is not the current immutable control release"
        )
    if resolved_module != release / ACTIVATION_MODULE_RELATIVE:
        raise ActivationIntegrityError("running activation module path is invalid")
    if _RELEASE_ID.fullmatch(release.name) is None:
        raise ActivationIntegrityError("current release id is invalid")

    for directory, label in (
        (root, "worker root"),
        (releases, "release root"),
        (release, "current release"),
        (release / ".release", "release metadata directory"),
        (release / "research_pipeline", "research package directory"),
    ):
        _require_safe_directory(
            directory,
            label,
            expected_root_uid,
            enforce_posix_permissions=enforce_posix_permissions,
        )

    manifest_path = release / ".release" / "source.sha256"
    provenance_path = release / ".release" / "provenance.json"
    try:
        manifest_bytes = _read_stable_root_file(
            manifest_path,
            "release manifest",
            expected_root_uid,
            enforce_posix_permissions=enforce_posix_permissions,
        )
        provenance_bytes = _read_stable_root_file(
            provenance_path,
            "release provenance",
            expected_root_uid,
            enforce_posix_permissions=enforce_posix_permissions,
        )
    except _ActivationUnavailable:
        if unavailable_is_dormant:
            raise
        raise ActivationIntegrityError("receipt-bound release metadata is absent")

    manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
    provenance = _parse_provenance(provenance_bytes, current=current)
    if (
        provenance["release_id"] != release.name
        or provenance["source_manifest_sha256"] != manifest_hash
    ):
        raise ActivationIntegrityError("release provenance identity mismatch")

    entries = _parse_manifest(manifest_bytes)
    required = {
        EVALUATOR_SCHEMA_RELATIVE: EVALUATOR_SCHEMA_SHA256,
        EVALUATOR_MODULE_RELATIVE: EVALUATOR_MODULE_SHA256,
        ACCEPTED_RESULT_RELATIVE: ACCEPTED_RESULT_SHA256,
        ACTIVATION_MODULE_RELATIVE: None,
        HEARTBEAT_MODULE_RELATIVE: None,
    }
    for relative, frozen_hash in required.items():
        manifest_value = entries.get(relative.as_posix())
        if manifest_value is None:
            raise ActivationIntegrityError(
                f"release manifest is missing {relative.as_posix()}"
            )
        if frozen_hash is not None and manifest_value != frozen_hash:
            raise ActivationIntegrityError(
                f"release manifest frozen hash mismatch for {relative.as_posix()}"
            )
        try:
            content = _read_stable_root_file(
                release / relative,
                relative.as_posix(),
                expected_root_uid,
                enforce_posix_permissions=enforce_posix_permissions,
            )
        except _ActivationUnavailable as error:
            raise ActivationIntegrityError(
                f"manifest-bound release file is absent: {relative.as_posix()}"
            ) from error
        if hashlib.sha256(content).hexdigest() != manifest_value:
            raise ActivationIntegrityError(
                f"manifest-bound release file changed: {relative.as_posix()}"
            )

    return _ReleaseIdentity(
        release_id=release.name,
        source_commit=provenance["source_git_commit"],
        manifest_sha256=manifest_hash,
    )


def _default_control_current(worker_root: Path) -> Path:
    configured = os.environ.get("AGORA_RESEARCH_APP_DIR") or os.environ.get("APP_DIR")
    return Path(configured) if configured else worker_root / "control-current"


def _require_safe_directory(
    path: Path,
    label: str,
    expected_root_uid: int,
    *,
    enforce_posix_permissions: bool,
) -> None:
    try:
        details = path.lstat()
    except FileNotFoundError as error:
        raise _ActivationUnavailable(f"{label} is absent") from error
    except OSError as error:
        raise ActivationIntegrityError(f"{label} cannot be inspected") from error
    if path.is_symlink() or not stat.S_ISDIR(details.st_mode):
        raise ActivationIntegrityError(f"{label} is not a regular directory")
    if details.st_uid != expected_root_uid:
        raise ActivationIntegrityError(f"{label} is not root-owned")
    if enforce_posix_permissions and details.st_mode & (
        stat.S_IWGRP | stat.S_IWOTH
    ):
        raise ActivationIntegrityError(f"{label} is group- or world-writable")


def _read_stable_root_file(
    path: Path,
    label: str,
    expected_root_uid: int,
    *,
    enforce_posix_permissions: bool,
) -> bytes:
    try:
        before = path.lstat()
    except FileNotFoundError as error:
        raise _ActivationUnavailable(f"{label} is absent") from error
    except OSError as error:
        raise ActivationIntegrityError(f"{label} cannot be inspected") from error
    if path.is_symlink() or not stat.S_ISREG(before.st_mode):
        raise ActivationIntegrityError(f"{label} is not a regular non-link file")
    if before.st_uid != expected_root_uid:
        raise ActivationIntegrityError(f"{label} is not root-owned")
    if enforce_posix_permissions and before.st_mode & (
        stat.S_IWGRP | stat.S_IWOTH
    ):
        raise ActivationIntegrityError(f"{label} is group- or world-writable")
    try:
        content = path.read_bytes()
        after = path.lstat()
    except OSError as error:
        raise ActivationIntegrityError(f"{label} could not be read stably") from error
    identity_before = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_mode,
        before.st_uid,
    )
    identity_after = (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
        after.st_mode,
        after.st_uid,
    )
    if identity_before != identity_after or len(content) != before.st_size:
        raise ActivationIntegrityError(f"{label} changed while being read")
    return content


def _parse_provenance(content: bytes, *, current: datetime) -> dict[str, Any]:
    try:
        value = json.loads(content.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ActivationIntegrityError("release provenance is malformed") from error
    if not isinstance(value, dict) or set(value) != _PROVENANCE_KEYS:
        raise ActivationIntegrityError("release provenance fields are invalid")
    if value.get("schema_version") != "1":
        raise ActivationIntegrityError("release provenance schema is invalid")
    if _RELEASE_ID.fullmatch(str(value.get("release_id", ""))) is None:
        raise ActivationIntegrityError("release provenance id is invalid")
    if _HEX40.fullmatch(str(value.get("source_git_commit", ""))) is None:
        raise ActivationIntegrityError("release provenance source commit is invalid")
    if not isinstance(value.get("source_git_branch"), str) or not value["source_git_branch"]:
        raise ActivationIntegrityError("release provenance source branch is invalid")
    if value.get("source_git_dirty") is not False:
        raise ActivationIntegrityError("release provenance is dirty")
    if _HEX64.fullmatch(str(value.get("source_manifest_sha256", ""))) is None:
        raise ActivationIntegrityError("release provenance manifest hash is invalid")
    installed_at = _parse_canonical_timestamp(value.get("installed_at"), "installed_at")
    if installed_at > current:
        raise ActivationIntegrityError("release provenance installation time is future")
    return value


def _parse_manifest(content: bytes) -> dict[str, str]:
    try:
        lines = content.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ActivationIntegrityError("release manifest is not UTF-8") from error
    if not lines or lines != sorted(lines):
        raise ActivationIntegrityError("release manifest is empty or not sorted")
    entries: dict[str, str] = {}
    for line in lines:
        match = _MANIFEST_LINE.fullmatch(line)
        if match is None:
            raise ActivationIntegrityError("release manifest line is malformed")
        relative = Path(match.group(2))
        normalized = relative.as_posix()
        if (
            relative.is_absolute()
            or "\\" in match.group(2)
            or ".." in relative.parts
            or normalized in entries
        ):
            raise ActivationIntegrityError("release manifest path is unsafe or duplicate")
        entries[normalized] = match.group(1)
    return entries


def _build_receipt(
    *,
    lineage: ActiveForwardTriggerLineage,
    activated_at: str,
    release: _ReleaseIdentity,
) -> dict[str, Any]:
    return {
        "schema_version": "1",
        "document_type": ACTIVATION_DOCUMENT_TYPE,
        "activated_at": activated_at,
        "implementation_commit": ACCEPTED_IMPLEMENTATION_COMMIT,
        "accepted_task_id": ACCEPTED_TASK_ID,
        "accepted_task_sha256": ACCEPTED_TASK_SHA256,
        "accepted_result_sha256": ACCEPTED_RESULT_SHA256,
        "evaluator_schema_sha256": EVALUATOR_SCHEMA_SHA256,
        "evaluator_module_sha256": EVALUATOR_MODULE_SHA256,
        "worker_release_id": release.release_id,
        "worker_source_commit": release.source_commit,
        "worker_manifest_sha256": release.manifest_sha256,
        "root_trigger_id": ROOT_TRIGGER_ID,
        "root_trigger_fingerprint": ROOT_TRIGGER_FINGERPRINT,
        "leaf_trigger_id": lineage.leaf_trigger["trigger_id"],
        "leaf_trigger_fingerprint": lineage.leaf_trigger["fingerprint"],
        "authorization": RESEARCH_AUTHORIZATION,
    }


def _aware_utc(value: datetime, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ActivationIntegrityError(f"{label} must be timezone-aware")
    return value.astimezone(timezone.utc)


def _parse_canonical_timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise ActivationIntegrityError(f"{label} must be a canonical timestamp")
    try:
        parsed = parse_timestamp(value, label).astimezone(timezone.utc)
    except ValueError as error:
        raise ActivationIntegrityError(f"{label} is invalid") from error
    if _iso_utc(parsed) != value:
        raise ActivationIntegrityError(f"{label} is not canonical")
    return parsed


def _iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
