from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import canonical_json_document_bytes
from .local_node import (
    AUTHORIZATION,
    ID_PATTERN,
    SHA256_PATTERN,
    STATE_AUTHORITY,
    TIMER_AUTHORITY,
)


DOCUMENT_TYPE = "LOCAL_RESEARCH_STRATEGY_PATH_V1"
OUTPUT_CLASSES = {"MECHANISM_CONCLUSION"}
PATH_STATUSES = {
    "DIRECT_TO_FROZEN_HYPOTHESIS": "FROZEN_HYPOTHESIS_MANIFEST",
    "DIRECT_TO_MATCHED_CAPITAL_EXPERIMENT": "MATCHED_CAPITAL_EXPERIMENT",
}
TOP_LEVEL_KEYS = {
    "schema_version",
    "document_type",
    "admission_id",
    "issued_at",
    "manager_thread_id",
    "task_id",
    "task_sha256",
    "dispatch_id",
    "dispatch_sha256",
    "intent_id",
    "intent_sha256",
    "output_class",
    "decision_time",
    "candidate_path",
    "economics",
    "disposition",
    "authorization",
    "state_authority",
    "timer_authority",
}
DECISION_TIME_KEYS = {
    "availability_status",
    "feature_name",
    "availability_rule",
    "decision_clock",
    "post_outcome_dependency",
}
CANDIDATE_PATH_KEYS = {
    "status",
    "parent_strategy_id",
    "matched_comparator_id",
    "positive_next_step",
    "maximum_additional_research_steps",
    "existing_adapter_or_direct_runner",
    "implementation_before_economic_test",
}
ECONOMIC_KEYS = {
    "equal_capital_comparator_required",
    "fees_required",
    "adverse_slippage_required",
    "total_pnl_required",
    "drawdown_required",
    "inventory_path_required",
    "holding_age_required",
}
DISPOSITION_KEYS = {
    "negative_closes_family",
    "insufficient_stops_without_permission",
    "independent_forward_or_oos_boundary_preserved",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    extra = sorted(set(value) - expected)
    if missing or extra:
        raise ValueError(f"{label} keys mismatch: missing={missing} extra={extra}")


def _string(value: Any, label: str, *, minimum: int, maximum: int) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError(f"{label} must be a string of length {minimum}..{maximum}")
    return value


def _pattern(value: Any, label: str, pattern: re.Pattern[str]) -> str:
    text = _string(value, label, minimum=1, maximum=1000)
    if pattern.fullmatch(text) is None:
        raise ValueError(f"{label} has an invalid format")
    return text


def _timestamp(value: Any, label: str) -> None:
    text = _string(value, label, minimum=1, maximum=64)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{label} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{label} must include a timezone")


def _require_true_fields(value: dict[str, Any], keys: set[str], label: str) -> None:
    for key in keys:
        if value[key] is not True:
            raise ValueError(f"{label}.{key} must be true")


def validate_local_strategy_path(value: Any) -> dict[str, Any]:
    strategy = _object(value, "local research strategy path")
    _exact_keys(strategy, TOP_LEVEL_KEYS, "local research strategy path")
    if strategy["schema_version"] != "1" or strategy["document_type"] != DOCUMENT_TYPE:
        raise ValueError("strategy path version or document type is unsupported")
    _pattern(strategy["admission_id"], "admission_id", ID_PATTERN)
    _timestamp(strategy["issued_at"], "issued_at")
    _string(strategy["manager_thread_id"], "manager_thread_id", minimum=1, maximum=128)
    for key in ("task_id", "dispatch_id", "intent_id"):
        _pattern(strategy[key], key, ID_PATTERN)
    for key in ("task_sha256", "dispatch_sha256", "intent_sha256"):
        _pattern(strategy[key], key, SHA256_PATTERN)
    if strategy["output_class"] not in OUTPUT_CLASSES:
        raise ValueError("strategy path output_class must be MECHANISM_CONCLUSION")

    decision = _object(strategy["decision_time"], "decision_time")
    _exact_keys(decision, DECISION_TIME_KEYS, "decision_time")
    if decision["availability_status"] != "KNOWN_BEFORE_DECISION":
        raise ValueError("strategy feature must be known before the decision")
    _string(decision["feature_name"], "decision_time.feature_name", minimum=3, maximum=200)
    _string(decision["availability_rule"], "decision_time.availability_rule", minimum=20, maximum=1000)
    _string(decision["decision_clock"], "decision_time.decision_clock", minimum=3, maximum=200)
    if decision["post_outcome_dependency"] != "DENY":
        raise ValueError("strategy path must deny post-outcome decision inputs")

    candidate = _object(strategy["candidate_path"], "candidate_path")
    _exact_keys(candidate, CANDIDATE_PATH_KEYS, "candidate_path")
    expected_next = PATH_STATUSES.get(candidate["status"])
    if expected_next is None or candidate["positive_next_step"] != expected_next:
        raise ValueError("candidate path status and positive next step are inconsistent")
    _string(candidate["parent_strategy_id"], "candidate_path.parent_strategy_id", minimum=3, maximum=200)
    _string(candidate["matched_comparator_id"], "candidate_path.matched_comparator_id", minimum=3, maximum=200)
    steps = candidate["maximum_additional_research_steps"]
    if isinstance(steps, bool) or not isinstance(steps, int) or not 0 <= steps <= 1:
        raise ValueError("candidate path must require at most one additional research step")
    if candidate["existing_adapter_or_direct_runner"] is not True:
        raise ValueError("candidate path requires an existing adapter or direct runner")
    if candidate["implementation_before_economic_test"] != "DENY":
        raise ValueError("candidate path must not require another implementation slice")

    economics = _object(strategy["economics"], "economics")
    _exact_keys(economics, ECONOMIC_KEYS, "economics")
    _require_true_fields(economics, ECONOMIC_KEYS, "economics")

    disposition = _object(strategy["disposition"], "disposition")
    _exact_keys(disposition, DISPOSITION_KEYS, "disposition")
    _require_true_fields(disposition, DISPOSITION_KEYS, "disposition")

    if strategy["authorization"] != AUTHORIZATION:
        raise ValueError("strategy path authorization is unsupported")
    if strategy["state_authority"] != STATE_AUTHORITY:
        raise ValueError("strategy path state authority must remain SERVER_CANONICAL")
    if strategy["timer_authority"] != TIMER_AUTHORITY:
        raise ValueError("strategy path timer authority must remain CODEX_CLOUD_OPS_ONLY")
    return strategy


def load_and_validate_local_strategy_path(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("local research strategy path must be strict UTF-8 JSON") from error
    if raw != canonical_json_document_bytes(value):
        raise ValueError("local research strategy path must use canonical JSON bytes")
    return validate_local_strategy_path(value), raw
