from __future__ import annotations

import hashlib
import json
import re
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import PurePosixPath
from typing import Any


DOCUMENT_TYPE = "BTC_UTC_DAY_3PCT_POST_SHOCK_ACTIONABILITY_V1"
CAPABILITY_SCOPE = "CAUSAL_TIMING_AND_BYTE_INTEGRITY_ONLY"
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
TERMINAL_RESULT_TYPE = "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_RESULT_V1"
SHOCK_DIAGNOSTIC_TYPE = "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V1"
FILL_POLICY = "FIRST_COMPLETE_UTC_HOUR_OPEN_STRICTLY_AFTER_DECISION_V1"
TERMINAL_DISPOSITIONS = frozenset(
    {
        "CONTINUATION_FACTOR_READY_FOR_MANAGER_REVIEW",
        "REVERSAL_FACTOR_READY_FOR_MANAGER_REVIEW",
        "NO_DIRECTIONAL_POST_SHOCK_FACTOR_CLOSE",
    }
)

_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_DECIMAL_TEXT = re.compile(r"^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$")
_UTC_TIMESTAMP = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)


def build_actionability_record(
    *,
    terminal_result_path: str,
    terminal_result_sha256: str,
    terminal_result_bytes: bytes,
    manager_review_id: str,
    manager_reviewed_at: str,
    manager_terminal_result_sha256: str,
    contract_activated_at: str,
    untouched_future_evidence_start: str,
    shock_diagnostic_path: str,
    shock_diagnostic_sha256: str,
    shock_diagnostic_bytes: bytes,
    fill_observation_path: str,
    fill_observation_sha256: str,
    fill_observation_bytes: bytes,
    sealed_at: str,
) -> dict[str, Any]:
    """Build one parent-neutral actionability record from immutable source bytes."""
    result = _parse_canonical_object(terminal_result_bytes, "terminal result")
    _validate_terminal_result_source(result)
    result_hash = sha256_bytes(terminal_result_bytes)
    if _require_hex64(terminal_result_sha256, "terminal result SHA-256") != result_hash:
        raise ValueError("terminal result byte hash mismatch")
    if manager_terminal_result_sha256 != result_hash:
        raise ValueError("Manager review terminal-result hash mismatch")

    diagnostic = _parse_canonical_object(shock_diagnostic_bytes, "shock diagnostic")
    diagnostic_fields = _validate_shock_diagnostic_source(diagnostic)
    diagnostic_hash = sha256_bytes(shock_diagnostic_bytes)
    if _require_hex64(shock_diagnostic_sha256, "shock diagnostic SHA-256") != diagnostic_hash:
        raise ValueError("shock diagnostic byte hash mismatch")

    fill_source = _parse_canonical_object(fill_observation_bytes, "fill observation")
    _validate_fill_source(fill_source)
    fill_hash = sha256_bytes(fill_observation_bytes)
    if _require_hex64(fill_observation_sha256, "fill observation SHA-256") != fill_hash:
        raise ValueError("fill observation byte hash mismatch")

    reviewed = _timestamp(manager_reviewed_at, "Manager reviewed_at")
    activated = _timestamp(contract_activated_at, "contract_activated_at")
    future_start = _timestamp(
        untouched_future_evidence_start, "untouched future evidence start"
    )
    target_received = diagnostic_fields["target_received"]
    diagnostic_sealed = diagnostic_fields["sealed_at"]
    decision_available = max(activated, reviewed, target_received, diagnostic_sealed)

    record = {
        "schema_version": "1",
        "document_type": DOCUMENT_TYPE,
        "capability_scope": CAPABILITY_SCOPE,
        "terminal_result": {
            "artifact_path": _artifact_path(terminal_result_path, "terminal result path"),
            "artifact_sha256": result_hash,
            "document_type": result["document_type"],
            "terminal": result["terminal"],
            "disposition": result["disposition"],
            "sealed_at": result["sealed_at"],
            "latest_outcome_day": result["latest_outcome_day"],
            "cumulative_chain_binding": result["cumulative_chain_binding"],
        },
        "manager_review": {
            "review_id": _identifier(manager_review_id, "Manager review id"),
            "reviewed_at": _timestamp_text(reviewed),
            "terminal_result_sha256": _require_hex64(
                manager_terminal_result_sha256,
                "Manager review terminal-result SHA-256",
            ),
        },
        "evidence_contract": {
            "contract_activated_at": _timestamp_text(activated),
            "untouched_future_evidence_start": _timestamp_text(future_start),
        },
        "shock_diagnostic": {
            "artifact_path": _artifact_path(
                shock_diagnostic_path, "shock diagnostic path"
            ),
            "artifact_sha256": diagnostic_hash,
            "diagnostic_type": diagnostic["diagnostic_type"],
            "eligibility": diagnostic["eligibility"],
            "t0": _timestamp_text(diagnostic_fields["t0"]),
            "target_received_at": _timestamp_text(target_received),
            "sealed_at": _timestamp_text(diagnostic_sealed),
        },
        "decision": {
            "decision_available_at": _timestamp_text(decision_available),
            "fill_policy": FILL_POLICY,
        },
        "fill_observation": {
            "source_contract_id": fill_source["source_contract_id"],
            "source_contract_sha256": fill_source["source_contract_sha256"],
            "artifact_path": _artifact_path(
                fill_observation_path, "fill observation path"
            ),
            "artifact_sha256": fill_hash,
            "interval_start": fill_source["interval_start"],
            "observed_at": fill_source["observed_at"],
            "received_at": fill_source["received_at"],
            "open_price": fill_source["open"],
        },
        "sealed_at": _timestamp_text(_timestamp(sealed_at, "record sealed_at")),
        "authorization": AUTHORIZATION,
    }
    validate_actionability_record(record)
    validate_actionability_source_bindings(
        record,
        terminal_result_bytes=terminal_result_bytes,
        shock_diagnostic_bytes=shock_diagnostic_bytes,
        fill_observation_bytes=fill_observation_bytes,
    )
    return record


def validate_actionability_record(value: Any) -> None:
    root_keys = {
        "schema_version",
        "document_type",
        "capability_scope",
        "terminal_result",
        "manager_review",
        "evidence_contract",
        "shock_diagnostic",
        "decision",
        "fill_observation",
        "sealed_at",
        "authorization",
    }
    _exact_object(value, root_keys, "actionability record")
    _constants(
        value,
        {
            "schema_version": "1",
            "document_type": DOCUMENT_TYPE,
            "capability_scope": CAPABILITY_SCOPE,
            "authorization": AUTHORIZATION,
        },
        "actionability record",
    )

    result = value["terminal_result"]
    _exact_object(
        result,
        {
            "artifact_path",
            "artifact_sha256",
            "document_type",
            "terminal",
            "disposition",
            "sealed_at",
            "latest_outcome_day",
            "cumulative_chain_binding",
        },
        "terminal result binding",
    )
    _artifact_path(result["artifact_path"], "terminal result path")
    _require_hex64(result["artifact_sha256"], "terminal result SHA-256")
    if result["document_type"] != TERMINAL_RESULT_TYPE:
        raise ValueError("terminal result document type mismatch")
    if result["terminal"] is not True or result["disposition"] not in TERMINAL_DISPOSITIONS:
        raise ValueError("actionability requires a terminal factor disposition")
    result_sealed = _timestamp(result["sealed_at"], "terminal result sealed_at")
    latest_outcome = _day(result["latest_outcome_day"], "latest outcome day")
    _require_hex64(result["cumulative_chain_binding"], "cumulative chain binding")

    review = value["manager_review"]
    _exact_object(
        review,
        {"review_id", "reviewed_at", "terminal_result_sha256"},
        "Manager review",
    )
    _identifier(review["review_id"], "Manager review id")
    reviewed = _timestamp(review["reviewed_at"], "Manager reviewed_at")
    if review["terminal_result_sha256"] != result["artifact_sha256"]:
        raise ValueError("Manager review is not bound to the terminal result")
    if reviewed < result_sealed:
        raise ValueError("Manager review predates terminal result sealing")

    contract = value["evidence_contract"]
    _exact_object(
        contract,
        {"contract_activated_at", "untouched_future_evidence_start"},
        "evidence contract",
    )
    activated = _timestamp(contract["contract_activated_at"], "contract_activated_at")
    future_start = _timestamp(
        contract["untouched_future_evidence_start"],
        "untouched future evidence start",
    )
    if activated < reviewed:
        raise ValueError("actionability contract predates Manager review")
    if future_start <= activated or future_start <= result_sealed:
        raise ValueError("future evidence does not start after activation and discovery seal")
    first_after_latest_outcome = datetime.combine(
        latest_outcome + timedelta(days=1), time.min, tzinfo=timezone.utc
    )
    if future_start < first_after_latest_outcome:
        raise ValueError("future evidence overlaps the terminal discovery day")

    diagnostic = value["shock_diagnostic"]
    _exact_object(
        diagnostic,
        {
            "artifact_path",
            "artifact_sha256",
            "diagnostic_type",
            "eligibility",
            "t0",
            "target_received_at",
            "sealed_at",
        },
        "shock diagnostic binding",
    )
    _artifact_path(diagnostic["artifact_path"], "shock diagnostic path")
    _require_hex64(diagnostic["artifact_sha256"], "shock diagnostic SHA-256")
    if diagnostic["diagnostic_type"] != SHOCK_DIAGNOSTIC_TYPE:
        raise ValueError("shock diagnostic document type mismatch")
    if diagnostic["eligibility"] != "FORWARD_FACTOR_ELIGIBLE":
        raise ValueError("shock diagnostic is not forward-factor eligible")
    t0 = _timestamp(diagnostic["t0"], "shock t0")
    target_received = _timestamp(
        diagnostic["target_received_at"], "target evidence received_at"
    )
    diagnostic_sealed = _timestamp(diagnostic["sealed_at"], "diagnostic sealed_at")
    if t0 < future_start:
        raise ValueError("shock t0 predates untouched future evidence")
    if target_received < t0:
        raise ValueError("target evidence was received before its complete-day boundary")
    if diagnostic_sealed < target_received:
        raise ValueError("shock diagnostic predates target evidence receipt")

    decision = value["decision"]
    _exact_object(decision, {"decision_available_at", "fill_policy"}, "decision")
    if decision["fill_policy"] != FILL_POLICY:
        raise ValueError("actionability fill policy mismatch")
    decision_available = _timestamp(
        decision["decision_available_at"], "decision_available_at"
    )
    required_decision = max(activated, reviewed, target_received, diagnostic_sealed)
    if decision_available != required_decision:
        raise ValueError("decision_available_at is not the maximum artifact clock")

    fill = value["fill_observation"]
    _exact_object(
        fill,
        {
            "source_contract_id",
            "source_contract_sha256",
            "artifact_path",
            "artifact_sha256",
            "interval_start",
            "observed_at",
            "received_at",
            "open_price",
        },
        "fill observation binding",
    )
    _identifier(fill["source_contract_id"], "fill source contract id")
    _require_hex64(fill["source_contract_sha256"], "fill source contract SHA-256")
    _artifact_path(fill["artifact_path"], "fill observation path")
    _require_hex64(fill["artifact_sha256"], "fill observation SHA-256")
    interval_start = _timestamp(fill["interval_start"], "fill interval_start")
    observed = _timestamp(fill["observed_at"], "fill observed_at")
    received = _timestamp(fill["received_at"], "fill received_at")
    expected_fill = first_complete_utc_hour_after(decision_available)
    if interval_start != expected_fill or observed != expected_fill:
        raise ValueError("fill does not use the first complete UTC-hour open")
    if received < observed:
        raise ValueError("fill receipt predates its observation")
    _positive_decimal(fill["open_price"], "fill open price")
    if _timestamp(value["sealed_at"], "record sealed_at") < received:
        raise ValueError("actionability record predates fill receipt")


def validate_actionability_source_bindings(
    value: Any,
    *,
    terminal_result_bytes: bytes,
    shock_diagnostic_bytes: bytes,
    fill_observation_bytes: bytes,
) -> None:
    validate_actionability_record(value)
    result = _parse_canonical_object(terminal_result_bytes, "terminal result")
    _validate_terminal_result_source(result)
    diagnostic = _parse_canonical_object(shock_diagnostic_bytes, "shock diagnostic")
    diagnostic_fields = _validate_shock_diagnostic_source(diagnostic)
    fill_source = _parse_canonical_object(fill_observation_bytes, "fill observation")
    _validate_fill_source(fill_source)

    result_binding = value["terminal_result"]
    if result_binding["artifact_sha256"] != sha256_bytes(terminal_result_bytes):
        raise ValueError("terminal result source bytes drifted")
    expected_result = {
        "document_type": result["document_type"],
        "terminal": result["terminal"],
        "disposition": result["disposition"],
        "sealed_at": result["sealed_at"],
        "latest_outcome_day": result["latest_outcome_day"],
        "cumulative_chain_binding": result["cumulative_chain_binding"],
    }
    for key, expected in expected_result.items():
        if result_binding[key] != expected:
            raise ValueError(f"terminal result {key} binding drifted")

    diagnostic_binding = value["shock_diagnostic"]
    if diagnostic_binding["artifact_sha256"] != sha256_bytes(shock_diagnostic_bytes):
        raise ValueError("shock diagnostic source bytes drifted")
    expected_diagnostic = {
        "diagnostic_type": diagnostic["diagnostic_type"],
        "eligibility": diagnostic["eligibility"],
        "t0": _timestamp_text(diagnostic_fields["t0"]),
        "target_received_at": _timestamp_text(diagnostic_fields["target_received"]),
        "sealed_at": _timestamp_text(diagnostic_fields["sealed_at"]),
    }
    for key, expected in expected_diagnostic.items():
        if diagnostic_binding[key] != expected:
            raise ValueError(f"shock diagnostic {key} binding drifted")

    fill_binding = value["fill_observation"]
    if fill_binding["artifact_sha256"] != sha256_bytes(fill_observation_bytes):
        raise ValueError("fill observation source bytes drifted")
    expected_fill = {
        "source_contract_id": fill_source["source_contract_id"],
        "source_contract_sha256": fill_source["source_contract_sha256"],
        "interval_start": fill_source["interval_start"],
        "observed_at": fill_source["observed_at"],
        "received_at": fill_source["received_at"],
        "open_price": fill_source["open"],
    }
    for key, expected in expected_fill.items():
        if fill_binding[key] != expected:
            raise ValueError(f"fill observation {key} binding drifted")


def canonical_bytes(value: dict[str, Any]) -> bytes:
    validate_actionability_record(value)
    return _raw_canonical_bytes(value)


def actionability_sha256(value: dict[str, Any]) -> str:
    return sha256_bytes(canonical_bytes(value))


def sha256_bytes(value: bytes) -> str:
    if not isinstance(value, bytes):
        raise ValueError("SHA-256 input must be bytes")
    return hashlib.sha256(value).hexdigest()


def first_complete_utc_hour_after(value: datetime | str) -> datetime:
    instant = _timestamp(value, "decision_available_at") if isinstance(value, str) else value
    if not isinstance(instant, datetime) or instant.tzinfo is None:
        raise ValueError("decision_available_at must be timezone-aware")
    instant = instant.astimezone(timezone.utc)
    return instant.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)


def _validate_terminal_result_source(value: Any) -> None:
    keys = {
        "schema_version",
        "document_type",
        "trigger_id",
        "trigger_fingerprint",
        "snapshot_key",
        "latest_outcome_day",
        "cumulative_chain_binding",
        "sealed_at",
        "disposition",
        "terminal",
        "episodes",
        "gate_evidence",
        "statistics",
        "guardrails",
        "authorization",
    }
    _exact_object(value, keys, "terminal result source")
    _constants(
        value,
        {
            "schema_version": "1",
            "document_type": TERMINAL_RESULT_TYPE,
            "authorization": AUTHORIZATION,
        },
        "terminal result source",
    )
    if value["terminal"] is not True or value["disposition"] not in TERMINAL_DISPOSITIONS:
        raise ValueError("terminal result source is WAIT or nonterminal")
    latest = _day(value["latest_outcome_day"], "terminal latest outcome day")
    chain = _require_hex64(value["cumulative_chain_binding"], "terminal chain binding")
    if value["snapshot_key"] != f"{latest.isoformat()}:{chain}":
        raise ValueError("terminal result snapshot identity mismatch")
    _timestamp(value["sealed_at"], "terminal result sealed_at")
    if not isinstance(value["episodes"], list) or not value["episodes"]:
        raise ValueError("terminal result source has no episodes")
    latest_episode = value["episodes"][-1]
    if not isinstance(latest_episode, dict):
        raise ValueError("terminal result latest episode is malformed")
    latest_reference = latest_episode.get("outcome_day_reference")
    if not isinstance(latest_reference, dict):
        raise ValueError("terminal result latest outcome reference is malformed")
    if latest_reference.get("day") != latest.isoformat() or latest_reference.get("chain_head") != chain:
        raise ValueError("terminal result latest outcome binding mismatch")
    for key in ("gate_evidence", "statistics", "guardrails"):
        if not isinstance(value[key], dict):
            raise ValueError(f"terminal result {key} is malformed")


def _validate_shock_diagnostic_source(value: Any) -> dict[str, datetime]:
    keys = {
        "schema_version",
        "diagnostic_type",
        "trigger_id",
        "trigger_fingerprint",
        "source",
        "observation_unit",
        "threshold_return",
        "prior_day",
        "target_day",
        "contract_activated_at",
        "sealed_at",
        "eligibility",
        "path",
        "guardrails",
        "authorization",
    }
    _exact_object(value, keys, "shock diagnostic source")
    _constants(
        value,
        {
            "schema_version": "1",
            "diagnostic_type": SHOCK_DIAGNOSTIC_TYPE,
            "eligibility": "FORWARD_FACTOR_ELIGIBLE",
            "authorization": AUTHORIZATION,
        },
        "shock diagnostic source",
    )
    target = value["target_day"]
    _exact_object(
        target,
        {"day", "artifact_path", "artifact_sha256", "chain_head", "received_at"},
        "shock target reference",
    )
    target_day = _day(target["day"], "shock target day")
    _artifact_path(target["artifact_path"], "shock target artifact path")
    _require_hex64(target["artifact_sha256"], "shock target artifact SHA-256")
    _require_hex64(target["chain_head"], "shock target chain head")
    target_received = _timestamp(target["received_at"], "shock target received_at")
    sealed = _timestamp(value["sealed_at"], "shock diagnostic sealed_at")
    if not isinstance(value["path"], dict) or value["path"].get("qualifies") is not True:
        raise ValueError("shock diagnostic source is not a qualifying shock")
    t0 = datetime.combine(target_day + timedelta(days=1), time.min, tzinfo=timezone.utc)
    return {"t0": t0, "target_received": target_received, "sealed_at": sealed}


def _validate_fill_source(value: Any) -> None:
    _exact_object(
        value,
        {
            "source_contract_id",
            "source_contract_sha256",
            "interval_start",
            "observed_at",
            "received_at",
            "open",
        },
        "fill observation source",
    )
    _identifier(value["source_contract_id"], "fill source contract id")
    _require_hex64(value["source_contract_sha256"], "fill source contract SHA-256")
    interval = _timestamp(value["interval_start"], "fill interval_start")
    observed = _timestamp(value["observed_at"], "fill observed_at")
    received = _timestamp(value["received_at"], "fill received_at")
    if interval != observed:
        raise ValueError("fill source interval and observation clocks differ")
    if received < observed:
        raise ValueError("fill source receipt predates observation")
    _positive_decimal(value["open"], "fill source open")


def _parse_canonical_object(value: bytes, label: str) -> dict[str, Any]:
    if not isinstance(value, bytes):
        raise ValueError(f"{label} must be bytes")

    def reject_constant(token: str) -> None:
        raise ValueError(f"{label} contains a non-finite JSON number")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, item in pairs:
            if key in result:
                raise ValueError(f"{label} contains a duplicate key")
            result[key] = item
        return result

    try:
        decoded = value.decode("utf-8")
        parsed = json.loads(
            decoded,
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not canonical UTF-8 JSON") from exc
    if not isinstance(parsed, dict):
        raise ValueError(f"{label} must be a JSON object")
    if _raw_canonical_bytes(parsed) != value:
        raise ValueError(f"{label} bytes are not canonical")
    return parsed


def _raw_canonical_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _exact_object(value: Any, keys: set[str], label: str) -> None:
    if not isinstance(value, dict) or set(value) != keys:
        raise ValueError(f"{label} fields are not exact")


def _constants(value: dict[str, Any], expected: dict[str, Any], label: str) -> None:
    for key, item in expected.items():
        if value.get(key) != item:
            raise ValueError(f"{label} {key} mismatch")


def _require_hex64(value: Any, label: str) -> str:
    if not isinstance(value, str) or _HEX64.fullmatch(value) is None:
        raise ValueError(f"{label} must be lowercase SHA-256")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _IDENTIFIER.fullmatch(value) is None:
        raise ValueError(f"{label} is invalid")
    return value


def _artifact_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ValueError(f"{label} is not a contained relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError(f"{label} is not a contained relative path")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or _UTC_TIMESTAMP.fullmatch(value) is None:
        raise ValueError(f"{label} must be canonical second-precision UTC")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ValueError(f"{label} is invalid") from exc
    return parsed.astimezone(timezone.utc)


def _timestamp_text(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _day(value: Any, label: str) -> date:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"{label} is invalid") from exc
    if parsed.isoformat() != value:
        raise ValueError(f"{label} is not canonical")
    return parsed


def _positive_decimal(value: Any, label: str) -> Decimal:
    if not isinstance(value, str) or _DECIMAL_TEXT.fullmatch(value) is None:
        raise ValueError(f"{label} is not canonical decimal text")
    try:
        parsed = Decimal(value)
    except InvalidOperation as exc:
        raise ValueError(f"{label} is invalid") from exc
    if not parsed.is_finite() or parsed <= 0:
        raise ValueError(f"{label} must be positive and finite")
    return parsed
