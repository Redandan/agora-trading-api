from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import hashlib
import re
from pathlib import Path
from typing import Any

from .microstructure_source_contract import (
    ContractViolation,
    canonical_json_bytes,
    file_sha256,
    load_json_bytes_strict,
    load_json_strict,
    validate_v3_day_bundle,
)


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
CONTRACT_ID = "OKX_MICROSTRUCTURE_DISCOVERY_RECOVERY_V3R1"
CONTRACT_SHA256 = "6448b47a373dca743df6492593582660461382b639fdb77aa897ffa5a9f604bd"
V3_DAY_SCHEMA_SHA256 = "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709"
V3_DIAGNOSTIC_CONTRACT_SHA256 = (
    "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a"
)
BINDING_SCHEMA_SHA256 = "1d07c67e6668ba8f7f01ebcb4a71d702e855cc6d40bb2e6260dbb30f97c2e60b"
COMPLETE_SCHEMA_SHA256 = "a75aea4e247cdc134c441e5de33c2773a984c076eda8f1cdd85a0c3440260fb2"
REJECTION_SCHEMA_SHA256 = "833e1cd3a0239987a8bc80caacb0abcecb5f00803816af09334c0674b5a04497"
STATE_SCHEMA_SHA256 = "12046ee0b3c814522bff6497f7028ae68da70884066e00c16a71d22e9ca5905d"

BINDING_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_DISCOVERY_SOURCE_BINDING_V3R1"
COMPLETE_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_DISCOVERY_COMPLETE_ENVELOPE_V3R1"
REJECTION_SCHEMA_VERSION = (
    "OKX_MICROSTRUCTURE_DISCOVERY_REJECTION_ENVELOPE_V3R1"
)
STATE_SCHEMA_VERSION = "OKX_MICROSTRUCTURE_DISCOVERY_INTAKE_STATE_V3R1"
STATE_TYPE = "SERVER_CANONICAL_MICROSTRUCTURE_DISCOVERY_V3R1_INTAKE"
CALENDAR_DAY_BUDGET = 42
REQUIRED_STREAK_DAYS = 14
ZERO_SHA256 = "0" * 64
SELECTION_RULE = "FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK"
REJECTION_CANONICALIZATION = (
    "UTF-8 compact JSON excluding envelope_seal; object keys sorted lexicographically"
)
COMPLETE_ENVELOPE_CANONICALIZATION = REJECTION_CANONICALIZATION
BUNDLE_DOCUMENT_CANONICALIZATION = (
    "UTF-8 compact JSON including seal; object keys sorted lexicographically"
)
ENVELOPE_DOCUMENT_CANONICALIZATION = (
    "UTF-8 compact JSON including envelope_seal; object keys sorted lexicographically"
)
CALENDAR_CHAIN_ALGORITHM = (
    "SHA-256(UTF-8(previous_chain_sha256 + LF + day + LF + disposition + LF + "
    "artifact_sha256 + LF + reason_or_dash)); first previous is 64 zeroes"
)
SELECTED_STREAK_CHAIN_ALGORITHM = (
    "SHA-256(UTF-8(previous_chain_sha256 + LF + day + LF + bundle_sha256 + LF + "
    "envelope_sha256)); first previous is 64 zeroes"
)

PACKAGE_DIR = Path(__file__).resolve().parent
CONTRACT_PATH = (
    PACKAGE_DIR / "okx-microstructure-discovery-recovery-contract.v3r1.json"
)
BINDING_SCHEMA_PATH = (
    PACKAGE_DIR / "okx-microstructure-discovery-source-binding.v3r1.schema.json"
)
COMPLETE_SCHEMA_PATH = (
    PACKAGE_DIR / "okx-microstructure-discovery-complete-envelope.v3r1.schema.json"
)
REJECTION_SCHEMA_PATH = (
    PACKAGE_DIR / "okx-microstructure-discovery-rejection-envelope.v3r1.schema.json"
)
STATE_SCHEMA_PATH = (
    PACKAGE_DIR / "okx-microstructure-discovery-intake-state.v3r1.schema.json"
)
V3_DAY_SCHEMA_PATH = PACKAGE_DIR / "okx-microstructure-forward-day.v3.schema.json"
V3_DIAGNOSTIC_CONTRACT_PATH = (
    PACKAGE_DIR / "okx-microstructure-forward-diagnostic-contract.v3.json"
)

GENERATION_PATTERN = re.compile(
    r"^okx-btcusdt-microstructure-discovery-v3r1-"
    r"(?P<start>[0-9]{8})-(?P<revision>r[0-9]+)$"
)
DIAGNOSTIC_PATTERN = re.compile(
    r"^okx-btcusdt-microstructure-forward-v3r1-"
    r"(?P<start>[0-9]{8})-(?P<revision>r[0-9]+)$"
)
RELEASE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_CHANNELS = {"trades", "books5"}

ALLOWED_REJECTION_REASONS = (
    "SERVICE_UPGRADE_NOTICE_64008",
    "TRANSPORT_DISCONNECT_UNPROVED_GAP",
    "PROCESS_RESTART_BEFORE_DAY_COMPLETE",
    "HOST_REBOOT_BEFORE_DAY_COMPLETE",
    "DUAL_CHANNEL_NOT_READY_AT_DAY_START",
)
TERMINAL_STATUSES = {
    "DIAGNOSTIC_READY",
    "NO_COMPLETE_STREAK_CLOSE",
    "INTEGRITY_BLOCKED",
}
BLOCKING_FAILURE_CODES = {
    "CONTRACT_HASH_MISMATCH",
    "WRONG_IDENTITY",
    "WRONG_DAY",
    "NON_ATOMIC_DELIVERY",
    "CONFLICTING_DUPLICATE",
    "UNKNOWN_EVENT",
    "MARKET_INTEGRITY_FAILURE",
    "UNSAFE_FILESYSTEM_STATE",
    "BACKFILL_FORBIDDEN",
    "SECOND_CLOCK_OR_WRITER_FORBIDDEN",
}

BINDING_KEYS = {
    "schema_version",
    "authorization",
    "generation_id",
    "diagnostic_id",
    "recovery_contract_sha256",
    "v3_day_schema_sha256",
    "v3_diagnostic_contract_sha256",
    "complete_envelope_schema_sha256",
    "rejection_envelope_schema_sha256",
    "intake_state_schema_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
    "start_day",
    "end_day",
    "calendar_day_budget",
    "required_consecutive_complete_days",
    "selection_rule",
}
COMPLETE_KEYS = {
    "schema_version",
    "envelope_type",
    "authorization",
    "generation_id",
    "diagnostic_id",
    "recovery_contract_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
    "producer_identity",
    "day",
    "calendar_index",
    "bundle_name",
    "bundle_size_bytes",
    "bundle_sha256",
    "published_at",
    "idempotency_key",
    "delivery_semantics",
    "envelope_seal",
}
STATE_KEYS = {
    "schema_version",
    "state_type",
    "authorization",
    "generation_id",
    "diagnostic_id",
    "recovery_contract_sha256",
    "binding_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
    "state_authority",
    "intake_identity",
    "network_access",
    "research_lifecycle_clock",
    "cloud_schedule_count",
    "start_day",
    "end_day",
    "calendar_day_budget",
    "required_consecutive_complete_days",
    "calendar_dispositions",
    "current_streak",
    "nonselected_complete_prefixes",
    "selected_streak",
    "status",
    "next_calendar_day",
    "calendar_chain_head_sha256",
    "selected_streak_chain_head_sha256",
    "failure",
    "readiness",
}
COMPLETE_DAY_KEYS = {"day", "bundle_sha256", "envelope_sha256", "accepted_at"}
DISPOSITION_KEYS = {
    "day",
    "calendar_index",
    "disposition",
    "artifact_sha256",
    "reason",
    "recorded_at",
    "cumulative_chain_sha256",
}
REJECTION_KEYS = {
    "schema_version",
    "envelope_type",
    "authorization",
    "generation_id",
    "recovery_contract_sha256",
    "producer_release_id",
    "producer_manifest_sha256",
    "producer_identity",
    "day",
    "calendar_index",
    "reason",
    "observation",
    "sanitized_control_event",
    "rejected_at",
    "idempotency_key",
    "delivery_semantics",
    "envelope_seal",
}
OBSERVATION_KEYS = {
    "started_at",
    "last_observed_at",
    "acknowledged_channels",
    "completed_minute_count",
    "data_message_count",
    "control_event_count",
    "raw_arrival_chain_sha256",
    "control_event_chain_sha256",
}


class DiscoveryRecoveryBlocked(ValueError):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def _block(code: str, detail: str) -> None:
    raise DiscoveryRecoveryBlocked(code, detail)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        _block("CONTRACT_HASH_MISMATCH", f"{label} must be an object")
    return value


def _exact_keys(value: dict[str, Any], keys: set[str], label: str) -> None:
    if set(value) != keys:
        _block("CONTRACT_HASH_MISMATCH", f"{label} keys changed")


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        _block("CONTRACT_HASH_MISMATCH", f"{label} must be lowercase SHA-256")
    return value


def _day(value: Any, label: str) -> date:
    if not isinstance(value, str):
        _block("WRONG_DAY", f"{label} must be an ISO date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise DiscoveryRecoveryBlocked("WRONG_DAY", f"{label} is invalid") from error
    if parsed.isoformat() != value:
        _block("WRONG_DAY", f"{label} must be canonical")
    return parsed


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        _block("CONTRACT_HASH_MISMATCH", f"{label} must be canonical UTC")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise DiscoveryRecoveryBlocked(
            "CONTRACT_HASH_MISMATCH", f"{label} is invalid"
        ) from error
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        _block("CONTRACT_HASH_MISMATCH", f"{label} must be UTC")
    return parsed


def _utc_text(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        _block("CONTRACT_HASH_MISMATCH", "timestamp must be timezone-aware UTC")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _canonical_sha256(value: Any, *, exclude_key: str | None = None) -> str:
    return hashlib.sha256(
        canonical_json_bytes(value, exclude_key=exclude_key)
    ).hexdigest()


def _calendar_chain(
    previous: str,
    day_value: str,
    disposition: str,
    artifact_sha256: str,
    reason: str | None,
) -> str:
    payload = "\n".join(
        (previous, day_value, disposition, artifact_sha256, reason or "-")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _streak_chain(records: list[dict[str, Any]]) -> str:
    chain = ZERO_SHA256
    for record in records:
        payload = "\n".join(
            (
                chain,
                record["day"],
                record["bundle_sha256"],
                record["envelope_sha256"],
            )
        ).encode("utf-8")
        chain = hashlib.sha256(payload).hexdigest()
    return chain


def _readiness(disposition: str = "NOT_READY") -> dict[str, Any]:
    return {
        "disposition": disposition,
        "candidate_authorized": False,
        "oos_authorized": False,
        "pnl_claim_authorized": False,
        "promotion_authorized": False,
        "performance_value": "MISSING_PROOF",
    }


def validate_frozen_files() -> dict[str, str]:
    expected = {
        CONTRACT_PATH: CONTRACT_SHA256,
        BINDING_SCHEMA_PATH: BINDING_SCHEMA_SHA256,
        COMPLETE_SCHEMA_PATH: COMPLETE_SCHEMA_SHA256,
        REJECTION_SCHEMA_PATH: REJECTION_SCHEMA_SHA256,
        STATE_SCHEMA_PATH: STATE_SCHEMA_SHA256,
        V3_DAY_SCHEMA_PATH: V3_DAY_SCHEMA_SHA256,
        V3_DIAGNOSTIC_CONTRACT_PATH: V3_DIAGNOSTIC_CONTRACT_SHA256,
    }
    for path, expected_hash in expected.items():
        if file_sha256(path) != expected_hash:
            _block("CONTRACT_HASH_MISMATCH", f"{path.name} hash changed")
    contract = load_json_strict(CONTRACT_PATH)
    if (
        contract.get("contract_id") != CONTRACT_ID
        or contract.get("schema_version") != "3R1"
        or contract.get("authorization") != AUTHORIZATION
    ):
        _block("CONTRACT_HASH_MISMATCH", "recovery contract identity changed")
    for path in (
        BINDING_SCHEMA_PATH,
        COMPLETE_SCHEMA_PATH,
        REJECTION_SCHEMA_PATH,
        STATE_SCHEMA_PATH,
    ):
        schema = load_json_strict(path)
        if schema.get("additionalProperties") is not False:
            _block("CONTRACT_HASH_MISMATCH", f"{path.name} is not closed")
    return {path.name: expected_hash for path, expected_hash in expected.items()}


def build_source_binding(
    *,
    generation_id: str,
    diagnostic_id: str,
    producer_release_id: str,
    producer_manifest_sha256: str,
    start_day: date,
    as_of_day: date,
) -> dict[str, Any]:
    if start_day <= as_of_day:
        _block("BACKFILL_FORBIDDEN", "start_day must be strictly future")
    value = {
        "schema_version": BINDING_SCHEMA_VERSION,
        "authorization": AUTHORIZATION,
        "generation_id": generation_id,
        "diagnostic_id": diagnostic_id,
        "recovery_contract_sha256": CONTRACT_SHA256,
        "v3_day_schema_sha256": V3_DAY_SCHEMA_SHA256,
        "v3_diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
        "complete_envelope_schema_sha256": COMPLETE_SCHEMA_SHA256,
        "rejection_envelope_schema_sha256": REJECTION_SCHEMA_SHA256,
        "intake_state_schema_sha256": STATE_SCHEMA_SHA256,
        "producer_release_id": producer_release_id,
        "producer_manifest_sha256": producer_manifest_sha256,
        "start_day": start_day.isoformat(),
        "end_day": (start_day + timedelta(days=CALENDAR_DAY_BUDGET - 1)).isoformat(),
        "calendar_day_budget": CALENDAR_DAY_BUDGET,
        "required_consecutive_complete_days": REQUIRED_STREAK_DAYS,
        "selection_rule": SELECTION_RULE,
    }
    return validate_source_binding(value)


def validate_source_binding(value: Any) -> dict[str, Any]:
    binding = _object(value, "source binding")
    _exact_keys(binding, BINDING_KEYS, "source binding")
    constants = {
        "schema_version": BINDING_SCHEMA_VERSION,
        "authorization": AUTHORIZATION,
        "recovery_contract_sha256": CONTRACT_SHA256,
        "v3_day_schema_sha256": V3_DAY_SCHEMA_SHA256,
        "v3_diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
        "complete_envelope_schema_sha256": COMPLETE_SCHEMA_SHA256,
        "rejection_envelope_schema_sha256": REJECTION_SCHEMA_SHA256,
        "intake_state_schema_sha256": STATE_SCHEMA_SHA256,
        "calendar_day_budget": CALENDAR_DAY_BUDGET,
        "required_consecutive_complete_days": REQUIRED_STREAK_DAYS,
        "selection_rule": SELECTION_RULE,
    }
    for key, expected in constants.items():
        if binding[key] != expected:
            _block("CONTRACT_HASH_MISMATCH", f"binding {key} changed")
    generation_match = (
        GENERATION_PATTERN.fullmatch(binding["generation_id"])
        if isinstance(binding["generation_id"], str)
        else None
    )
    if generation_match is None:
        _block("WRONG_IDENTITY", "generation_id is invalid")
    diagnostic_match = (
        DIAGNOSTIC_PATTERN.fullmatch(binding["diagnostic_id"])
        if isinstance(binding["diagnostic_id"], str)
        else None
    )
    if diagnostic_match is None:
        _block("WRONG_IDENTITY", "diagnostic_id is invalid")
    if (
        not isinstance(binding["producer_release_id"], str)
        or len(binding["producer_release_id"]) > 128
        or RELEASE_PATTERN.fullmatch(binding["producer_release_id"]) is None
    ):
        _block("WRONG_IDENTITY", "producer release identity is invalid")
    _sha256(binding["producer_manifest_sha256"], "producer manifest")
    start = _day(binding["start_day"], "binding.start_day")
    end = _day(binding["end_day"], "binding.end_day")
    expected_start_token = start.strftime("%Y%m%d")
    if (
        generation_match.group("start") != expected_start_token
        or diagnostic_match.group("start") != expected_start_token
        or generation_match.group("revision")
        != diagnostic_match.group("revision")
    ):
        _block(
            "WRONG_IDENTITY",
            "generation and diagnostic identity must match start day and revision",
        )
    if end != start + timedelta(days=CALENDAR_DAY_BUDGET - 1):
        _block("CONTRACT_HASH_MISMATCH", "binding calendar window changed")
    return deepcopy(binding)


def initial_intake_state(binding_value: Any) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    state = {
        "schema_version": STATE_SCHEMA_VERSION,
        "state_type": STATE_TYPE,
        "authorization": AUTHORIZATION,
        "generation_id": binding["generation_id"],
        "diagnostic_id": binding["diagnostic_id"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "binding_sha256": _canonical_sha256(binding),
        "producer_release_id": binding["producer_release_id"],
        "producer_manifest_sha256": binding["producer_manifest_sha256"],
        "state_authority": "SERVER_CANONICAL",
        "intake_identity": "agora-research",
        "network_access": "DENY",
        "research_lifecycle_clock": "CODEX_CLOUD_OPS_ONLY",
        "cloud_schedule_count": 1,
        "start_day": binding["start_day"],
        "end_day": binding["end_day"],
        "calendar_day_budget": CALENDAR_DAY_BUDGET,
        "required_consecutive_complete_days": REQUIRED_STREAK_DAYS,
        "calendar_dispositions": [],
        "current_streak": [],
        "nonselected_complete_prefixes": [],
        "selected_streak": None,
        "status": "WAITING_FOR_CALENDAR_DAY",
        "next_calendar_day": binding["start_day"],
        "calendar_chain_head_sha256": ZERO_SHA256,
        "selected_streak_chain_head_sha256": None,
        "failure": None,
        "readiness": _readiness(),
    }
    return validate_intake_state(state, binding)


def canonical_intake_state_bytes(value: Any, binding_value: Any) -> bytes:
    return canonical_json_bytes(validate_intake_state(value, binding_value))


def load_canonical_intake_state_bytes(
    raw_bytes: bytes, binding_value: Any
) -> dict[str, Any]:
    try:
        state = load_json_bytes_strict(raw_bytes, "V3R1 intake state")
    except ContractViolation as error:
        _block(error.code, str(error))
    if raw_bytes != canonical_json_bytes(state):
        _block("CONTRACT_HASH_MISMATCH", "V3R1 intake state bytes are not canonical")
    return validate_intake_state(state, binding_value)


def _validate_complete_record(value: Any, label: str) -> dict[str, Any]:
    record = _object(value, label)
    _exact_keys(record, COMPLETE_DAY_KEYS, label)
    _day(record["day"], f"{label}.day")
    _sha256(record["bundle_sha256"], f"{label}.bundle_sha256")
    _sha256(record["envelope_sha256"], f"{label}.envelope_sha256")
    _timestamp(record["accepted_at"], f"{label}.accepted_at")
    return record


def _all_complete_records(state: dict[str, Any]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for prefix in state["nonselected_complete_prefixes"]:
        records.extend(prefix)
    records.extend(state["current_streak"])
    if state["selected_streak"] is not None:
        records.extend(state["selected_streak"])
    return records


def validate_intake_state(value: Any, binding_value: Any) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    state = _object(value, "intake state")
    _exact_keys(state, STATE_KEYS, "intake state")
    constants = {
        "schema_version": STATE_SCHEMA_VERSION,
        "state_type": STATE_TYPE,
        "authorization": AUTHORIZATION,
        "generation_id": binding["generation_id"],
        "diagnostic_id": binding["diagnostic_id"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "binding_sha256": _canonical_sha256(binding),
        "producer_release_id": binding["producer_release_id"],
        "producer_manifest_sha256": binding["producer_manifest_sha256"],
        "state_authority": "SERVER_CANONICAL",
        "intake_identity": "agora-research",
        "network_access": "DENY",
        "research_lifecycle_clock": "CODEX_CLOUD_OPS_ONLY",
        "cloud_schedule_count": 1,
        "start_day": binding["start_day"],
        "end_day": binding["end_day"],
        "calendar_day_budget": CALENDAR_DAY_BUDGET,
        "required_consecutive_complete_days": REQUIRED_STREAK_DAYS,
    }
    for key, expected in constants.items():
        if state[key] != expected:
            code = (
                "SECOND_CLOCK_OR_WRITER_FORBIDDEN"
                if key in {"research_lifecycle_clock", "cloud_schedule_count"}
                else "CONTRACT_HASH_MISMATCH"
            )
            _block(code, f"state {key} changed")

    dispositions = state["calendar_dispositions"]
    if not isinstance(dispositions, list) or len(dispositions) > CALENDAR_DAY_BUDGET:
        _block("CONTRACT_HASH_MISMATCH", "calendar dispositions are invalid")
    start = _day(state["start_day"], "state.start_day")
    end = _day(state["end_day"], "state.end_day")
    chain = ZERO_SHA256
    complete_dispositions: dict[str, dict[str, Any]] = {}
    last_recorded_at: datetime | None = None
    for index, raw in enumerate(dispositions):
        record = _object(raw, f"calendar_dispositions[{index}]")
        _exact_keys(record, DISPOSITION_KEYS, f"calendar_dispositions[{index}]")
        expected_day = start + timedelta(days=index)
        if _day(record["day"], f"calendar_dispositions[{index}].day") != expected_day:
            _block("WRONG_DAY", "calendar dispositions are not exact and contiguous")
        if record["calendar_index"] != index + 1:
            _block("CONTRACT_HASH_MISMATCH", "calendar index changed")
        if record["disposition"] not in {"COMPLETE", "SOURCE_LIVENESS_REJECTED"}:
            _block("CONTRACT_HASH_MISMATCH", "calendar disposition is invalid")
        artifact = _sha256(record["artifact_sha256"], "disposition artifact")
        reason = record["reason"]
        if record["disposition"] == "COMPLETE":
            if reason is not None:
                _block("CONTRACT_HASH_MISMATCH", "complete day has rejection reason")
            complete_dispositions[record["day"]] = record
        elif reason not in ALLOWED_REJECTION_REASONS:
            _block("UNKNOWN_EVENT", "rejection reason is not allowlisted")
        recorded_at = _timestamp(record["recorded_at"], "disposition.recorded_at")
        if last_recorded_at is not None and recorded_at < last_recorded_at:
            _block("CONTRACT_HASH_MISMATCH", "disposition time regressed")
        last_recorded_at = recorded_at
        chain = _calendar_chain(
            chain, record["day"], record["disposition"], artifact, reason
        )
        if record["cumulative_chain_sha256"] != chain:
            _block("CONTRACT_HASH_MISMATCH", "calendar chain changed")
    if state["calendar_chain_head_sha256"] != chain:
        _block("CONTRACT_HASH_MISMATCH", "calendar chain head changed")

    current = state["current_streak"]
    prefixes = state["nonselected_complete_prefixes"]
    selected = state["selected_streak"]
    if not isinstance(current, list) or len(current) > REQUIRED_STREAK_DAYS:
        _block("CONTRACT_HASH_MISMATCH", "current streak is invalid")
    if not isinstance(prefixes, list):
        _block("CONTRACT_HASH_MISMATCH", "nonselected prefixes are invalid")
    for prefix_index, prefix in enumerate(prefixes):
        if not isinstance(prefix, list) or not 1 <= len(prefix) < REQUIRED_STREAK_DAYS:
            _block("CONTRACT_HASH_MISMATCH", "nonselected prefix length is invalid")
        for index, record in enumerate(prefix):
            _validate_complete_record(record, f"prefix[{prefix_index}][{index}]")
            if index and _day(record["day"], "prefix day") != _day(
                prefix[index - 1]["day"], "prefix prior day"
            ) + timedelta(days=1):
                _block("WRONG_DAY", "nonselected prefix is not contiguous")
    for index, record in enumerate(current):
        _validate_complete_record(record, f"current_streak[{index}]")
        if index and _day(record["day"], "current day") != _day(
            current[index - 1]["day"], "current prior day"
        ) + timedelta(days=1):
            _block("WRONG_DAY", "current streak is not contiguous")
    if selected is not None:
        if not isinstance(selected, list) or len(selected) != REQUIRED_STREAK_DAYS:
            _block("CONTRACT_HASH_MISMATCH", "selected streak must contain 14 days")
        for index, record in enumerate(selected):
            _validate_complete_record(record, f"selected_streak[{index}]")
            if index and _day(record["day"], "selected day") != _day(
                selected[index - 1]["day"], "selected prior day"
            ) + timedelta(days=1):
                _block("WRONG_DAY", "selected streak is not contiguous")
    all_complete = _all_complete_records(state)
    by_day: dict[str, dict[str, Any]] = {}
    for record in all_complete:
        if record["day"] in by_day:
            _block("CONFLICTING_DUPLICATE", "complete day appears twice")
        by_day[record["day"]] = record
    if set(by_day) != set(complete_dispositions):
        _block("CONTRACT_HASH_MISMATCH", "complete-day inventory is incomplete")
    last_complete_acceptance: datetime | None = None
    for day_text, disposition in complete_dispositions.items():
        if by_day[day_text]["envelope_sha256"] != disposition["artifact_sha256"]:
            _block("CONTRACT_HASH_MISMATCH", "complete disposition hash changed")
        complete_day = _day(day_text, "complete day")
        accepted_at = _timestamp(by_day[day_text]["accepted_at"], "accepted_at")
        source_recorded_at = _timestamp(
            disposition["recorded_at"], "complete source recorded_at"
        )
        if accepted_at < source_recorded_at:
            _block("CONTRACT_HASH_MISMATCH", "acceptance precedes source publication")
        if last_complete_acceptance is not None and accepted_at < last_complete_acceptance:
            _block("CONTRACT_HASH_MISMATCH", "complete acceptance time regressed")
        last_complete_acceptance = accepted_at
        earliest_acceptance = datetime.combine(
            complete_day + timedelta(days=1), datetime.min.time(), tzinfo=timezone.utc
        )
        if accepted_at < earliest_acceptance:
            _block("WRONG_DAY", "complete day was accepted before UTC close")

    complete_runs: list[list[str]] = []
    active_run: list[str] = []
    for disposition in dispositions:
        if disposition["disposition"] == "COMPLETE":
            active_run.append(disposition["day"])
        elif active_run:
            complete_runs.append(active_run)
            active_run = []
    if active_run:
        complete_runs.append(active_run)
    actual_prefix_days = [
        [record["day"] for record in prefix] for prefix in prefixes
    ]
    actual_current_days = [record["day"] for record in current]
    actual_selected_days = (
        None if selected is None else [record["day"] for record in selected]
    )

    expected_status: str
    expected_next: str | None
    expected_selected_chain: str | None
    declared_blocked = state["status"] == "INTEGRITY_BLOCKED"
    if declared_blocked:
        if selected is not None or len(dispositions) >= CALENDAR_DAY_BUDGET:
            _block("CONTRACT_HASH_MISMATCH", "blocked state conflicts with terminal state")
        expected_status = "INTEGRITY_BLOCKED"
        expected_next = None
        expected_selected_chain = None
    elif selected is not None:
        if current:
            _block("CONTRACT_HASH_MISMATCH", "terminal selected streak must be frozen")
        expected_status = "DIAGNOSTIC_READY"
        expected_next = None
        expected_selected_chain = _streak_chain(selected)
    elif len(dispositions) == CALENDAR_DAY_BUDGET:
        if current:
            _block("CONTRACT_HASH_MISMATCH", "deadline prefix was not archived")
        expected_status = "NO_COMPLETE_STREAK_CLOSE"
        expected_next = None
        expected_selected_chain = None
    else:
        expected_status = (
            "BUILDING_CONSECUTIVE_STREAK" if current else "WAITING_FOR_CALENDAR_DAY"
        )
        expected_next = (start + timedelta(days=len(dispositions))).isoformat()
        expected_selected_chain = None
    if state["status"] != expected_status:
        _block("CONTRACT_HASH_MISMATCH", "state status changed")
    if state["next_calendar_day"] != expected_next:
        _block("WRONG_DAY", "next calendar day changed")
    if state["selected_streak_chain_head_sha256"] != expected_selected_chain:
        _block("CONTRACT_HASH_MISMATCH", "selected streak chain changed")

    if selected is not None:
        if (
            not complete_runs
            or len(complete_runs[-1]) != REQUIRED_STREAK_DAYS
            or actual_selected_days != complete_runs[-1]
            or actual_prefix_days != complete_runs[:-1]
            or actual_current_days
        ):
            _block("CONTRACT_HASH_MISMATCH", "selected streak grouping changed")
    elif expected_status == "NO_COMPLETE_STREAK_CLOSE":
        if actual_prefix_days != complete_runs or actual_current_days:
            _block("CONTRACT_HASH_MISMATCH", "deadline streak grouping changed")
    else:
        trailing_complete = bool(
            dispositions and dispositions[-1]["disposition"] == "COMPLETE"
        )
        expected_prefix_days = complete_runs[:-1] if trailing_complete else complete_runs
        expected_current_days = complete_runs[-1] if trailing_complete else []
        if (
            actual_prefix_days != expected_prefix_days
            or actual_current_days != expected_current_days
        ):
            _block("CONTRACT_HASH_MISMATCH", "active streak grouping changed")

    if declared_blocked:
        failure = _object(state["failure"], "state failure")
        _exact_keys(failure, {"code", "day", "detail"}, "state failure")
        if failure["code"] not in BLOCKING_FAILURE_CODES:
            _block("CONTRACT_HASH_MISMATCH", "failure code is invalid")
        expected_failure_day = (start + timedelta(days=len(dispositions))).isoformat()
        if failure["day"] != expected_failure_day:
            _block("WRONG_DAY", "failure day changed")
        if (
            not isinstance(failure["detail"], str)
            or not 1 <= len(failure["detail"]) <= 500
        ):
            _block("CONTRACT_HASH_MISMATCH", "failure detail is invalid")
    elif state["failure"] is not None:
        _block("CONTRACT_HASH_MISMATCH", "non-blocked state contains failure")
    expected_readiness = _readiness(
        "FROZEN_V3R1_DISCOVERY_ANALYSIS_ONLY"
        if expected_status == "DIAGNOSTIC_READY"
        else "NO_COMPLETE_STREAK_CLOSE"
        if expected_status == "NO_COMPLETE_STREAK_CLOSE"
        else "INTEGRITY_BLOCKED"
        if expected_status == "INTEGRITY_BLOCKED"
        else "NOT_READY"
    )
    if state["readiness"] != expected_readiness:
        _block("CONTRACT_HASH_MISMATCH", "readiness boundary changed")
    if end != start + timedelta(days=CALENDAR_DAY_BUDGET - 1):
        _block("CONTRACT_HASH_MISMATCH", "state deadline changed")
    return deepcopy(state)


def classify_control_event(value: Any) -> dict[str, Any]:
    event = _object(value, "control event")
    event_type = event.get("event")
    if not isinstance(event_type, str):
        _block("UNKNOWN_EVENT", "control event type is missing")
    if event_type == "subscribe":
        argument = _object(event.get("arg"), "subscribe.arg")
        channel = argument.get("channel")
        instrument = argument.get("instId")
        if channel not in EXPECTED_CHANNELS or instrument != "BTC-USDT":
            _block("WRONG_IDENTITY", "subscription acknowledgement changed")
        return {"action": "ACKNOWLEDGE", "channel": channel, "sanitized": None}
    if event_type == "channel-conn-count":
        channel = event.get("channel")
        count = event.get("connCount")
        if channel not in EXPECTED_CHANNELS or not isinstance(count, str) or not count.isdigit():
            _block("UNKNOWN_EVENT", "channel connection count is invalid")
        return {
            "action": "SEAL_CONTROL_EVENT_AND_CONTINUE",
            "channel": channel,
            "sanitized": {"event": event_type, "code": None},
        }
    if event_type == "notice" and event.get("code") == "64008":
        return {
            "action": "REJECT_ACTIVE_DAY",
            "reason": "SERVICE_UPGRADE_NOTICE_64008",
            "sanitized": {"event": "notice", "code": "64008"},
        }
    _block("UNKNOWN_EVENT", f"event {event_type} is not allowlisted")


def next_control_event_chain(previous: str, raw_event_bytes: bytes) -> str:
    _sha256(previous, "previous control event chain")
    if not isinstance(raw_event_bytes, bytes) or not raw_event_bytes:
        _block("UNKNOWN_EVENT", "raw control event bytes are unavailable")
    return hashlib.sha256(previous.encode("ascii") + b"\n" + raw_event_bytes).hexdigest()


def build_complete_envelope(
    *,
    binding_value: Any,
    bundle_value: Any,
    raw_bundle_bytes: bytes,
    day: date,
    published_at: datetime,
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    if not isinstance(raw_bundle_bytes, bytes) or not raw_bundle_bytes:
        _block("CONTRACT_HASH_MISMATCH", "complete bundle bytes are unavailable")
    try:
        validated_bundle = validate_v3_day_bundle(
            bundle_value, raw_bytes=raw_bundle_bytes
        )
    except ContractViolation as error:
        _block(error.code, str(error))
    if validated_bundle["day"] != day:
        _block("WRONG_DAY", "complete bundle day changed")
    start = _day(binding["start_day"], "binding.start_day")
    calendar_index = (day - start).days + 1
    if not 1 <= calendar_index <= CALENDAR_DAY_BUDGET:
        _block("WRONG_DAY", "complete day is outside the frozen calendar")
    published_text = _utc_text(published_at)
    value = {
        "schema_version": COMPLETE_SCHEMA_VERSION,
        "envelope_type": "IMMUTABLE_COMPLETE_MICROSTRUCTURE_DAY",
        "authorization": AUTHORIZATION,
        "generation_id": binding["generation_id"],
        "diagnostic_id": binding["diagnostic_id"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "producer_release_id": binding["producer_release_id"],
        "producer_manifest_sha256": binding["producer_manifest_sha256"],
        "producer_identity": "agora-evidence-source",
        "day": day.isoformat(),
        "calendar_index": calendar_index,
        "bundle_name": f"okx-btc-usdt-microstructure-{day.isoformat()}.json",
        "bundle_size_bytes": len(raw_bundle_bytes),
        "bundle_sha256": validated_bundle["bundle_sha256"],
        "published_at": published_text,
        "idempotency_key": (
            f"{binding['generation_id']}:{day.isoformat()}:"
            f"{validated_bundle['bundle_sha256']}"
        ),
        "delivery_semantics": {
            "transport": "MICROSTRUCTURE_V3R1_ONE_WAY_DROP",
            "bundle_document_canonicalization": BUNDLE_DOCUMENT_CANONICALIZATION,
            "envelope_document_canonicalization": ENVELOPE_DOCUMENT_CANONICALIZATION,
            "atomic_rename": True,
            "overwrite": False,
            "source_read_after_publish": False,
            "symlinks": False,
            "canonical_state_access": False,
            "partial_market_aggregates": False,
            "repair_retry_stitch_backfill": False,
        },
        "envelope_seal": None,
    }
    value["envelope_seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _canonical_sha256(value, exclude_key="envelope_seal"),
        "canonicalization": COMPLETE_ENVELOPE_CANONICALIZATION,
        "sealed_at": published_text,
    }
    validate_complete_envelope(
        value,
        bundle_value=bundle_value,
        raw_envelope_bytes=canonical_json_bytes(value),
        raw_bundle_bytes=raw_bundle_bytes,
        binding_value=binding,
        expected_day=day,
        delivered_via_atomic_rename=True,
        source_path_is_symlink=False,
        overwrite_attempted=False,
        observed_producer_identity="agora-evidence-source",
    )
    return value


def validate_complete_envelope(
    value: Any,
    *,
    bundle_value: Any,
    raw_envelope_bytes: bytes,
    raw_bundle_bytes: bytes,
    binding_value: Any,
    expected_day: date,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    observed_producer_identity: str,
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    envelope = _object(value, "complete envelope")
    _exact_keys(envelope, COMPLETE_KEYS, "complete envelope")
    if raw_envelope_bytes != canonical_json_bytes(envelope):
        _block("CONTRACT_HASH_MISMATCH", "complete envelope bytes are not canonical")
    if observed_producer_identity != "agora-evidence-source":
        _block("WRONG_IDENTITY", "observed producer identity changed")
    if source_path_is_symlink:
        _block("UNSAFE_FILESYSTEM_STATE", "symlink delivery is forbidden")
    if overwrite_attempted:
        _block("CONFLICTING_DUPLICATE", "overwrite is forbidden")
    if not delivered_via_atomic_rename:
        _block("NON_ATOMIC_DELIVERY", "atomic rename is required")
    constants = {
        "schema_version": COMPLETE_SCHEMA_VERSION,
        "envelope_type": "IMMUTABLE_COMPLETE_MICROSTRUCTURE_DAY",
        "authorization": AUTHORIZATION,
        "generation_id": binding["generation_id"],
        "diagnostic_id": binding["diagnostic_id"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "producer_release_id": binding["producer_release_id"],
        "producer_manifest_sha256": binding["producer_manifest_sha256"],
        "producer_identity": "agora-evidence-source",
    }
    for key, expected in constants.items():
        if envelope[key] != expected:
            _block("WRONG_IDENTITY", f"complete envelope {key} changed")
    if _day(envelope["day"], "complete envelope day") != expected_day:
        _block("WRONG_DAY", "complete envelope day changed")
    start = _day(binding["start_day"], "binding.start_day")
    if envelope["calendar_index"] != (expected_day - start).days + 1:
        _block("WRONG_DAY", "complete envelope calendar index changed")
    if not isinstance(raw_bundle_bytes, bytes) or not raw_bundle_bytes:
        _block("CONTRACT_HASH_MISMATCH", "complete bundle bytes are unavailable")
    try:
        validated_bundle = validate_v3_day_bundle(
            bundle_value, raw_bytes=raw_bundle_bytes
        )
    except ContractViolation as error:
        _block(error.code, str(error))
    if validated_bundle["day"] != expected_day:
        _block("WRONG_DAY", "complete bundle day changed")
    bundle_hash = validated_bundle["bundle_sha256"]
    if envelope["bundle_name"] != (
        f"okx-btc-usdt-microstructure-{expected_day.isoformat()}.json"
    ):
        _block("WRONG_DAY", "complete bundle name changed")
    if envelope["bundle_size_bytes"] != len(raw_bundle_bytes):
        _block("CONTRACT_HASH_MISMATCH", "complete bundle size changed")
    if envelope["bundle_sha256"] != bundle_hash:
        _block("CONTRACT_HASH_MISMATCH", "complete bundle hash changed")
    if envelope["idempotency_key"] != (
        f"{binding['generation_id']}:{expected_day.isoformat()}:{bundle_hash}"
    ):
        _block("CONTRACT_HASH_MISMATCH", "complete idempotency key changed")
    expected_delivery = {
        "transport": "MICROSTRUCTURE_V3R1_ONE_WAY_DROP",
        "bundle_document_canonicalization": BUNDLE_DOCUMENT_CANONICALIZATION,
        "envelope_document_canonicalization": ENVELOPE_DOCUMENT_CANONICALIZATION,
        "atomic_rename": True,
        "overwrite": False,
        "source_read_after_publish": False,
        "symlinks": False,
        "canonical_state_access": False,
        "partial_market_aggregates": False,
        "repair_retry_stitch_backfill": False,
    }
    if envelope["delivery_semantics"] != expected_delivery:
        _block("CONTRACT_HASH_MISMATCH", "complete delivery semantics changed")
    published_at = _timestamp(envelope["published_at"], "complete published_at")
    sealed_at = _timestamp(
        _object(bundle_value, "complete bundle")["seal"]["sealed_at"],
        "complete bundle seal time",
    )
    if published_at < sealed_at:
        _block("NON_ATOMIC_DELIVERY", "complete publication precedes bundle seal")
    seal = _object(envelope["envelope_seal"], "complete envelope seal")
    if set(seal) != {"algorithm", "payload_sha256", "canonicalization", "sealed_at"}:
        _block("CONTRACT_HASH_MISMATCH", "complete envelope seal keys changed")
    if (
        seal["algorithm"] != "SHA-256"
        or seal["canonicalization"] != COMPLETE_ENVELOPE_CANONICALIZATION
        or _timestamp(seal["sealed_at"], "complete envelope seal time")
        != published_at
        or seal["payload_sha256"]
        != _canonical_sha256(envelope, exclude_key="envelope_seal")
    ):
        _block("CONTRACT_HASH_MISMATCH", "complete envelope seal changed")
    return {
        "day": expected_day,
        "bundle_sha256": bundle_hash,
        "envelope_sha256": hashlib.sha256(raw_envelope_bytes).hexdigest(),
        "published_at": published_at,
    }


def build_rejection_envelope(
    *,
    binding_value: Any,
    day: date,
    reason: str,
    started_at: datetime | None,
    last_observed_at: datetime | None,
    acknowledged_channels: list[str],
    completed_minute_count: int,
    data_message_count: int,
    control_event_count: int,
    raw_arrival_chain_sha256: str,
    control_event_chain_sha256: str,
    rejected_at: datetime,
    sanitized_control_event: dict[str, str] | None,
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    start = _day(binding["start_day"], "binding.start_day")
    calendar_index = (day - start).days + 1
    if not 1 <= calendar_index <= CALENDAR_DAY_BUDGET:
        _block("WRONG_DAY", "rejection day is outside the frozen calendar")
    value = {
        "schema_version": REJECTION_SCHEMA_VERSION,
        "envelope_type": "IMMUTABLE_SOURCE_LIVENESS_DAY_REJECTION",
        "authorization": AUTHORIZATION,
        "generation_id": binding["generation_id"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "producer_release_id": binding["producer_release_id"],
        "producer_manifest_sha256": binding["producer_manifest_sha256"],
        "producer_identity": "agora-evidence-source",
        "day": day.isoformat(),
        "calendar_index": calendar_index,
        "reason": reason,
        "observation": {
            "started_at": None if started_at is None else _utc_text(started_at),
            "last_observed_at": (
                None if last_observed_at is None else _utc_text(last_observed_at)
            ),
            "acknowledged_channels": sorted(acknowledged_channels),
            "completed_minute_count": completed_minute_count,
            "data_message_count": data_message_count,
            "control_event_count": control_event_count,
            "raw_arrival_chain_sha256": raw_arrival_chain_sha256,
            "control_event_chain_sha256": control_event_chain_sha256,
        },
        "sanitized_control_event": sanitized_control_event,
        "rejected_at": _utc_text(rejected_at),
        "idempotency_key": (
            f"{binding['generation_id']}:{day.isoformat()}:SOURCE_LIVENESS_REJECTED"
        ),
        "delivery_semantics": {
            "transport": "MICROSTRUCTURE_V3R1_ONE_WAY_DROP",
            "atomic_rename": True,
            "overwrite": False,
            "source_read_after_publish": False,
            "symlinks": False,
            "canonical_state_access": False,
            "partial_market_aggregates": False,
            "repair_retry_stitch_backfill": False,
        },
        "envelope_seal": None,
    }
    value["envelope_seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": _canonical_sha256(value, exclude_key="envelope_seal"),
        "canonicalization": REJECTION_CANONICALIZATION,
        "sealed_at": _utc_text(rejected_at),
    }
    validate_rejection_envelope(
        value,
        raw_bytes=canonical_json_bytes(value),
        binding_value=binding,
        expected_day=day,
        delivered_via_atomic_rename=True,
        source_path_is_symlink=False,
        overwrite_attempted=False,
        observed_producer_identity="agora-evidence-source",
    )
    return value


def validate_rejection_envelope(
    value: Any,
    *,
    raw_bytes: bytes,
    binding_value: Any,
    expected_day: date,
    delivered_via_atomic_rename: bool,
    source_path_is_symlink: bool,
    overwrite_attempted: bool,
    observed_producer_identity: str,
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    envelope = _object(value, "rejection envelope")
    _exact_keys(envelope, REJECTION_KEYS, "rejection envelope")
    if raw_bytes != canonical_json_bytes(envelope):
        _block("CONTRACT_HASH_MISMATCH", "rejection envelope bytes are not canonical")
    constants = {
        "schema_version": REJECTION_SCHEMA_VERSION,
        "envelope_type": "IMMUTABLE_SOURCE_LIVENESS_DAY_REJECTION",
        "authorization": AUTHORIZATION,
        "generation_id": binding["generation_id"],
        "recovery_contract_sha256": CONTRACT_SHA256,
        "producer_release_id": binding["producer_release_id"],
        "producer_manifest_sha256": binding["producer_manifest_sha256"],
        "producer_identity": "agora-evidence-source",
    }
    for key, expected in constants.items():
        if envelope[key] != expected:
            _block("WRONG_IDENTITY", f"rejection {key} changed")
    if observed_producer_identity != "agora-evidence-source":
        _block("WRONG_IDENTITY", "observed producer identity changed")
    if source_path_is_symlink:
        _block("UNSAFE_FILESYSTEM_STATE", "symlink delivery is forbidden")
    if overwrite_attempted:
        _block("CONFLICTING_DUPLICATE", "overwrite is forbidden")
    if not delivered_via_atomic_rename:
        _block("NON_ATOMIC_DELIVERY", "atomic rename is required")
    if _day(envelope["day"], "rejection.day") != expected_day:
        _block("WRONG_DAY", "rejection day changed")
    start = _day(binding["start_day"], "binding.start_day")
    if envelope["calendar_index"] != (expected_day - start).days + 1:
        _block("WRONG_DAY", "rejection calendar index changed")
    reason = envelope["reason"]
    if reason not in ALLOWED_REJECTION_REASONS:
        _block("UNKNOWN_EVENT", "rejection reason is not allowlisted")
    observation = _object(envelope["observation"], "rejection observation")
    _exact_keys(observation, OBSERVATION_KEYS, "rejection observation")
    started = (
        None
        if observation["started_at"] is None
        else _timestamp(observation["started_at"], "observation.started_at")
    )
    last = (
        None
        if observation["last_observed_at"] is None
        else _timestamp(observation["last_observed_at"], "observation.last_observed_at")
    )
    rejected = _timestamp(envelope["rejected_at"], "rejected_at")
    if started is not None and last is not None and last < started:
        _block("CONTRACT_HASH_MISMATCH", "observation time regressed")
    if last is not None and rejected < last:
        _block("CONTRACT_HASH_MISMATCH", "rejection precedes last observation")
    channels = observation["acknowledged_channels"]
    if (
        not isinstance(channels, list)
        or channels != sorted(set(channels))
        or any(channel not in EXPECTED_CHANNELS for channel in channels)
    ):
        _block("WRONG_IDENTITY", "acknowledged channels changed")
    for key, maximum in (
        ("completed_minute_count", 1439),
        ("data_message_count", None),
        ("control_event_count", None),
    ):
        count = observation[key]
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            _block("CONTRACT_HASH_MISMATCH", f"{key} is invalid")
        if maximum is not None and count > maximum:
            _block("CONTRACT_HASH_MISMATCH", f"{key} exceeds its bound")
    _sha256(observation["raw_arrival_chain_sha256"], "raw arrival chain")
    _sha256(observation["control_event_chain_sha256"], "control event chain")
    sanitized = envelope["sanitized_control_event"]
    if reason == "SERVICE_UPGRADE_NOTICE_64008":
        if sanitized != {"event": "notice", "code": "64008"}:
            _block("UNKNOWN_EVENT", "service-upgrade rejection lacks exact notice")
    elif sanitized is not None:
        _block("UNKNOWN_EVENT", "non-notice rejection contains control event")
    if envelope["idempotency_key"] != (
        f"{binding['generation_id']}:{expected_day.isoformat()}:SOURCE_LIVENESS_REJECTED"
    ):
        _block("CONTRACT_HASH_MISMATCH", "rejection idempotency key changed")
    if envelope["delivery_semantics"] != {
        "transport": "MICROSTRUCTURE_V3R1_ONE_WAY_DROP",
        "atomic_rename": True,
        "overwrite": False,
        "source_read_after_publish": False,
        "symlinks": False,
        "canonical_state_access": False,
        "partial_market_aggregates": False,
        "repair_retry_stitch_backfill": False,
    }:
        _block("CONTRACT_HASH_MISMATCH", "rejection delivery semantics changed")
    seal = _object(envelope["envelope_seal"], "rejection envelope seal")
    if set(seal) != {"algorithm", "payload_sha256", "canonicalization", "sealed_at"}:
        _block("CONTRACT_HASH_MISMATCH", "rejection seal keys changed")
    if seal["algorithm"] != "SHA-256" or seal["canonicalization"] != REJECTION_CANONICALIZATION:
        _block("CONTRACT_HASH_MISMATCH", "rejection seal contract changed")
    if _timestamp(seal["sealed_at"], "rejection seal time") != rejected:
        _block("CONTRACT_HASH_MISMATCH", "rejection seal time changed")
    if seal["payload_sha256"] != _canonical_sha256(envelope, exclude_key="envelope_seal"):
        _block("CONTRACT_HASH_MISMATCH", "rejection payload seal changed")
    return {
        "day": expected_day,
        "reason": reason,
        "envelope_sha256": hashlib.sha256(raw_bytes).hexdigest(),
        "rejected_at": rejected,
    }


def _require_advancing_state(state: dict[str, Any]) -> None:
    if state["status"] in TERMINAL_STATUSES:
        _block("CONFLICTING_DUPLICATE", "terminal state cannot advance")


def block_intake_state(
    state_value: Any,
    *,
    binding_value: Any,
    code: str,
    detail: str,
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    state = validate_intake_state(state_value, binding)
    _require_advancing_state(state)
    if code not in BLOCKING_FAILURE_CODES:
        _block("CONTRACT_HASH_MISMATCH", "failure code is invalid")
    if not isinstance(detail, str) or not 1 <= len(detail) <= 500:
        _block("CONTRACT_HASH_MISMATCH", "failure detail is invalid")
    failure_day = state["next_calendar_day"]
    if failure_day is None:
        _block("CONFLICTING_DUPLICATE", "terminal state cannot be blocked")
    state["status"] = "INTEGRITY_BLOCKED"
    state["next_calendar_day"] = None
    state["failure"] = {"code": code, "day": failure_day, "detail": detail}
    state["readiness"] = _readiness("INTEGRITY_BLOCKED")
    return validate_intake_state(state, binding)


def _append_calendar_disposition(
    state: dict[str, Any],
    *,
    day: date,
    disposition: str,
    artifact_sha256: str,
    reason: str | None,
    recorded_at: datetime,
) -> None:
    expected_day = _day(state["next_calendar_day"], "next_calendar_day")
    if day < expected_day:
        _block("BACKFILL_FORBIDDEN", "prior calendar day cannot be replayed")
    if day > expected_day:
        _block("WRONG_DAY", "calendar day cannot be skipped")
    previous = state["calendar_chain_head_sha256"]
    chain = _calendar_chain(
        previous, day.isoformat(), disposition, artifact_sha256, reason
    )
    state["calendar_dispositions"].append(
        {
            "day": day.isoformat(),
            "calendar_index": len(state["calendar_dispositions"]) + 1,
            "disposition": disposition,
            "artifact_sha256": artifact_sha256,
            "reason": reason,
            "recorded_at": _utc_text(recorded_at),
            "cumulative_chain_sha256": chain,
        }
    )
    state["calendar_chain_head_sha256"] = chain


def advance_complete_day(
    state_value: Any,
    *,
    binding_value: Any,
    day: date,
    bundle_sha256: str,
    envelope_sha256: str,
    accepted_at: datetime,
    source_recorded_at: datetime | None = None,
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    state = validate_intake_state(state_value, binding)
    _require_advancing_state(state)
    bundle_hash = _sha256(bundle_sha256, "bundle_sha256")
    envelope_hash = _sha256(envelope_sha256, "envelope_sha256")
    _append_calendar_disposition(
        state,
        day=day,
        disposition="COMPLETE",
        artifact_sha256=envelope_hash,
        reason=None,
        recorded_at=(
            accepted_at if source_recorded_at is None else source_recorded_at
        ),
    )
    state["current_streak"].append(
        {
            "day": day.isoformat(),
            "bundle_sha256": bundle_hash,
            "envelope_sha256": envelope_hash,
            "accepted_at": _utc_text(accepted_at),
        }
    )
    if len(state["current_streak"]) == REQUIRED_STREAK_DAYS:
        state["selected_streak"] = deepcopy(state["current_streak"])
        state["current_streak"] = []
        state["status"] = "DIAGNOSTIC_READY"
        state["next_calendar_day"] = None
        state["selected_streak_chain_head_sha256"] = _streak_chain(
            state["selected_streak"]
        )
        state["readiness"] = _readiness("FROZEN_V3R1_DISCOVERY_ANALYSIS_ONLY")
    elif len(state["calendar_dispositions"]) == CALENDAR_DAY_BUDGET:
        state["nonselected_complete_prefixes"].append(state["current_streak"])
        state["current_streak"] = []
        state["status"] = "NO_COMPLETE_STREAK_CLOSE"
        state["next_calendar_day"] = None
        state["readiness"] = _readiness("NO_COMPLETE_STREAK_CLOSE")
    else:
        state["status"] = "BUILDING_CONSECUTIVE_STREAK"
        state["next_calendar_day"] = (day + timedelta(days=1)).isoformat()
    return validate_intake_state(state, binding)


def advance_complete_envelope(
    state_value: Any,
    complete_value: Any,
    bundle_value: Any,
    *,
    raw_complete_bytes: bytes,
    raw_bundle_bytes: bytes,
    binding_value: Any,
    accepted_at: datetime,
    delivered_via_atomic_rename: bool = True,
    source_path_is_symlink: bool = False,
    overwrite_attempted: bool = False,
    observed_producer_identity: str = "agora-evidence-source",
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    state = validate_intake_state(state_value, binding)
    _require_advancing_state(state)
    expected_day = _day(state["next_calendar_day"], "next_calendar_day")
    validated = validate_complete_envelope(
        complete_value,
        bundle_value=bundle_value,
        raw_envelope_bytes=raw_complete_bytes,
        raw_bundle_bytes=raw_bundle_bytes,
        binding_value=binding,
        expected_day=expected_day,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        observed_producer_identity=observed_producer_identity,
    )
    if accepted_at < validated["published_at"]:
        _block("CONTRACT_HASH_MISMATCH", "acceptance precedes complete publication")
    return advance_complete_day(
        state,
        binding_value=binding,
        day=expected_day,
        bundle_sha256=validated["bundle_sha256"],
        envelope_sha256=validated["envelope_sha256"],
        accepted_at=accepted_at,
        source_recorded_at=validated["published_at"],
    )


def advance_rejected_day(
    state_value: Any,
    rejection_value: Any,
    *,
    raw_rejection_bytes: bytes,
    binding_value: Any,
    delivered_via_atomic_rename: bool = True,
    source_path_is_symlink: bool = False,
    overwrite_attempted: bool = False,
    observed_producer_identity: str = "agora-evidence-source",
) -> dict[str, Any]:
    binding = validate_source_binding(binding_value)
    state = validate_intake_state(state_value, binding)
    _require_advancing_state(state)
    expected_day = _day(state["next_calendar_day"], "next_calendar_day")
    validated = validate_rejection_envelope(
        rejection_value,
        raw_bytes=raw_rejection_bytes,
        binding_value=binding,
        expected_day=expected_day,
        delivered_via_atomic_rename=delivered_via_atomic_rename,
        source_path_is_symlink=source_path_is_symlink,
        overwrite_attempted=overwrite_attempted,
        observed_producer_identity=observed_producer_identity,
    )
    _append_calendar_disposition(
        state,
        day=expected_day,
        disposition="SOURCE_LIVENESS_REJECTED",
        artifact_sha256=validated["envelope_sha256"],
        reason=validated["reason"],
        recorded_at=validated["rejected_at"],
    )
    if state["current_streak"]:
        state["nonselected_complete_prefixes"].append(state["current_streak"])
        state["current_streak"] = []
    if len(state["calendar_dispositions"]) == CALENDAR_DAY_BUDGET:
        state["status"] = "NO_COMPLETE_STREAK_CLOSE"
        state["next_calendar_day"] = None
        state["readiness"] = _readiness("NO_COMPLETE_STREAK_CLOSE")
    else:
        state["status"] = "WAITING_FOR_CALENDAR_DAY"
        state["next_calendar_day"] = (expected_day + timedelta(days=1)).isoformat()
    return validate_intake_state(state, binding)
