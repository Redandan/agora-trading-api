from __future__ import annotations

from dataclasses import dataclass
from datetime import date
import os
from pathlib import Path
from typing import Any, Callable

from .microstructure_source_contract import (
    ContractViolation,
    accept_intake_day,
    accept_v3_intake_day,
    block_intake_state,
    block_v3_intake_state,
    canonical_json_bytes,
    initial_intake_state,
    initial_v3_intake_state,
    load_json_bytes_strict,
    validate_intake_state,
    validate_v3_intake_state,
)


@dataclass(frozen=True, slots=True)
class ObservedDelivery:
    intake_identity: str
    network_access: str
    producer_identity: str
    delivered_via_atomic_rename: bool
    source_path_is_symlink: bool
    overwrite_attempted: bool
    historical_backfill_requested: bool
    candle_chain_reuse_requested: bool
    research_lifecycle_action_requested: bool

    def validate(self) -> None:
        if self.intake_identity != "agora-research":
            raise ContractViolation("WRONG_IDENTITY", "intake identity is not authorized")
        if self.network_access != "DENY":
            raise ContractViolation(
                "LIFECYCLE_CLOCK_FORBIDDEN",
                "intake network policy must remain DENY",
            )
        if self.producer_identity != "agora-evidence-source":
            raise ContractViolation("WRONG_IDENTITY", "producer identity is not authorized")
        if self.delivered_via_atomic_rename is not True:
            raise ContractViolation(
                "NON_ATOMIC_DELIVERY", "delivery was not observed through atomic rename"
            )
        if self.source_path_is_symlink is not False:
            raise ContractViolation("SYMLINK_REJECT", "symlink delivery is forbidden")
        if self.overwrite_attempted is not False:
            raise ContractViolation("OVERWRITE_REJECT", "drop overwrite is forbidden")
        if self.historical_backfill_requested is not False:
            raise ContractViolation("BACKFILL_FORBIDDEN", "historical backfill is forbidden")
        if self.candle_chain_reuse_requested is not False:
            raise ContractViolation(
                "CANDLE_CHAIN_REUSE_FORBIDDEN", "candle-chain reuse is forbidden"
            )
        if self.research_lifecycle_action_requested is not False:
            raise ContractViolation(
                "LIFECYCLE_CLOCK_FORBIDDEN",
                "intake cannot enqueue, select, retry, or time research actions",
            )


@dataclass(frozen=True, slots=True)
class IntakeResult:
    disposition: str
    state_bytes: bytes


class RecoveryBlocked(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class _IntakeProfile:
    initial: Callable[..., dict[str, Any]]
    validate: Callable[[Any], dict[str, Any]]
    accept: Callable[..., dict[str, Any]]
    block: Callable[..., dict[str, Any]]


_V2_INTAKE = _IntakeProfile(
    initial=initial_intake_state,
    validate=validate_intake_state,
    accept=accept_intake_day,
    block=block_intake_state,
)
_V3_INTAKE = _IntakeProfile(
    initial=initial_v3_intake_state,
    validate=validate_v3_intake_state,
    accept=accept_v3_intake_day,
    block=block_v3_intake_state,
)


def initial_state_bytes(
    diagnostic_id: str,
    start_day: date,
    *,
    as_of_day: date,
) -> bytes:
    return _initial_state_bytes(
        _V2_INTAKE, diagnostic_id, start_day, as_of_day=as_of_day
    )


def initial_v3_state_bytes(
    diagnostic_id: str,
    start_day: date,
    *,
    as_of_day: date,
) -> bytes:
    return _initial_state_bytes(
        _V3_INTAKE, diagnostic_id, start_day, as_of_day=as_of_day
    )


def _initial_state_bytes(
    profile: _IntakeProfile,
    diagnostic_id: str,
    start_day: date,
    *,
    as_of_day: date,
) -> bytes:
    state = profile.initial(
        diagnostic_id,
        start_day,
        as_of_day=as_of_day,
    )
    return _canonical_state_bytes(profile, state)


def canonical_state_bytes(state: Any) -> bytes:
    return _canonical_state_bytes(_V2_INTAKE, state)


def canonical_v3_state_bytes(state: Any) -> bytes:
    return _canonical_state_bytes(_V3_INTAKE, state)


def _canonical_state_bytes(profile: _IntakeProfile, state: Any) -> bytes:
    validated = profile.validate(state)
    return canonical_json_bytes(validated)


def load_canonical_state_bytes(raw_bytes: bytes) -> dict[str, Any]:
    return _load_canonical_state_bytes(_V2_INTAKE, raw_bytes)


def load_canonical_v3_state_bytes(raw_bytes: bytes) -> dict[str, Any]:
    return _load_canonical_state_bytes(_V3_INTAKE, raw_bytes)


def _load_canonical_state_bytes(
    profile: _IntakeProfile, raw_bytes: bytes
) -> dict[str, Any]:
    state = load_json_bytes_strict(raw_bytes, "microstructure intake state")
    if raw_bytes != canonical_json_bytes(state):
        raise ContractViolation(
            "HASH_MISMATCH",
            "intake state bytes must be compact sorted-key canonical JSON",
        )
    return profile.validate(state)


def apply_observed_delivery(
    state_bytes: bytes,
    envelope_bytes: bytes,
    bundle_bytes: bytes,
    *,
    observed: ObservedDelivery,
    accepted_at: str,
) -> IntakeResult:
    return _apply_observed_delivery(
        _V2_INTAKE,
        state_bytes,
        envelope_bytes,
        bundle_bytes,
        observed=observed,
        accepted_at=accepted_at,
    )


def apply_observed_v3_delivery(
    state_bytes: bytes,
    envelope_bytes: bytes,
    bundle_bytes: bytes,
    *,
    observed: ObservedDelivery,
    accepted_at: str,
) -> IntakeResult:
    return _apply_observed_delivery(
        _V3_INTAKE,
        state_bytes,
        envelope_bytes,
        bundle_bytes,
        observed=observed,
        accepted_at=accepted_at,
    )


def _apply_observed_delivery(
    profile: _IntakeProfile,
    state_bytes: bytes,
    envelope_bytes: bytes,
    bundle_bytes: bytes,
    *,
    observed: ObservedDelivery,
    accepted_at: str,
) -> IntakeResult:
    state = _load_canonical_state_bytes(profile, state_bytes)
    failure_day = _expected_failure_day(state)
    try:
        observed.validate()
        envelope = load_json_bytes_strict(envelope_bytes, "drop envelope")
        bundle = load_json_bytes_strict(bundle_bytes, "day bundle")
        next_state = profile.accept(
            state,
            envelope,
            bundle,
            raw_envelope_bytes=envelope_bytes,
            raw_bundle_bytes=bundle_bytes,
            accepted_at=accepted_at,
            observed_producer_identity=observed.producer_identity,
            delivered_via_atomic_rename=observed.delivered_via_atomic_rename,
            source_path_is_symlink=observed.source_path_is_symlink,
            overwrite_attempted=observed.overwrite_attempted,
            historical_backfill_requested=observed.historical_backfill_requested,
            candle_chain_reuse_requested=observed.candle_chain_reuse_requested,
            research_lifecycle_action_requested=(
                observed.research_lifecycle_action_requested
            ),
        )
        next_bytes = _canonical_state_bytes(profile, next_state)
        if next_bytes == state_bytes:
            disposition = "IDEMPOTENT_DUPLICATE"
        else:
            disposition = str(next_state["status"])
        return IntakeResult(disposition=disposition, state_bytes=next_bytes)
    except ContractViolation as error:
        detail = str(error).strip() or error.code
        blocked = profile.block(
            state,
            code=error.code,
            day=failure_day,
            detail=detail[:500],
        )
        profile.validate(blocked)
        return IntakeResult(
            disposition="INTEGRITY_BLOCKED",
            state_bytes=canonical_json_bytes(blocked),
        )


def state_lock_path(state_path: Path) -> Path:
    return state_path.with_name(f".{state_path.name}.lock")


def state_temp_path(state_path: Path) -> Path:
    return state_path.with_name(f".{state_path.name}.tmp")


def commit_canonical_state(state_path: Path, next_state_bytes: bytes) -> None:
    load_canonical_state_bytes(next_state_bytes)
    state_path = Path(state_path)
    state_directory = state_path.parent
    if not state_directory.is_dir() or state_directory.is_symlink():
        raise RecoveryBlocked("RECOVERY_BLOCKED_STATE_DIRECTORY")
    if state_path.is_symlink():
        raise RecoveryBlocked("RECOVERY_BLOCKED_STATE_SYMLINK")

    lock_path = state_lock_path(state_path)
    temp_path = state_temp_path(state_path)
    try:
        lock_path.mkdir(mode=0o700)
    except FileExistsError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_LOCK_PRESENT") from error
    except OSError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_LOCK_CREATE") from error

    if os.path.lexists(temp_path):
        raise RecoveryBlocked("RECOVERY_BLOCKED_STALE_TEMP")

    try:
        with temp_path.open("xb") as stream:
            stream.write(next_state_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, state_path)
        _fsync_directory(state_directory)
        lock_path.rmdir()
        _fsync_directory(state_directory)
    except RecoveryBlocked:
        raise
    except FileExistsError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_TEMP_PRESENT") from error
    except OSError as error:
        raise RecoveryBlocked("RECOVERY_BLOCKED_ATOMIC_COMMIT") from error


def _expected_failure_day(state: dict[str, Any]) -> date:
    next_expected_day = state.get("next_expected_day")
    if isinstance(next_expected_day, str):
        return date.fromisoformat(next_expected_day)
    accepted_days = state.get("accepted_days")
    if isinstance(accepted_days, list) and accepted_days:
        return date.fromisoformat(str(accepted_days[-1]["day"]))
    return date.fromisoformat(str(state["start_day"]))


def _fsync_directory(directory: Path) -> None:
    if os.name == "nt":
        return
    descriptor = os.open(
        directory,
        os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
